package com.cubical.roproperties.data

import android.content.Context
import com.cubical.roproperties.root.RootShell
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PropertyRepository(
    private val context: Context,
    private val shell: RootShell = RootShell(),
) {
    suspend fun probeRoot(): RootCapability {
        val id = shell.root("id -u")
        if (!id.successful || id.output.lineSequence().lastOrNull()?.trim() != "0") {
            return RootCapability(
                rootGranted = false,
                detail = id.output.ifBlank { "Root access was denied or no su binary was found." },
            )
        }

        val resetProp = shell.root("command -v resetprop")
        val implementationResult = shell.root(
            "if command -v magisk >/dev/null 2>&1; then magisk -V 2>/dev/null; " +
                "elif command -v ksud >/dev/null 2>&1; then ksud --version 2>/dev/null; " +
                "else echo unknown; fi",
        )
        return RootCapability(
            rootGranted = true,
            resetPropAvailable = resetProp.successful && resetProp.output.isNotBlank(),
            implementation = implementationResult.output.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() },
            detail = if (resetProp.successful) null else "Root works, but resetprop is unavailable.",
        )
    }

    suspend fun loadProperties(includePersistentOverrides: Boolean): List<AndroidProperty> {
        val getProp = shell.plain("getprop")
        if (!getProp.successful) error(getProp.output.ifBlank { "Unable to read Android properties." })
        val overrides = if (includePersistentOverrides) readPersistentOverrides() else emptyMap()
        return PropertyParser.parseGetProp(getProp.output)
            .asSequence()
            .filter { (name, _) -> name.startsWith("ro.") }
            .map { (name, value) ->
                AndroidProperty(
                    name = name,
                    value = value,
                    description = PropertyDescriptionRegistry.description(name),
                    category = PropertyDescriptionRegistry.category(name),
                    risk = PropertyDescriptionRegistry.risk(name),
                    persistentValue = overrides[name],
                )
            }
            .sortedBy { it.name }
            .toList()
    }

    suspend fun applyRuntime(name: String, value: String): EditResult {
        validate(name, value)?.let { return EditResult(false, it) }
        val result = shell.root(
            "resetprop -n ${RootShell.quote(name)} ${RootShell.quote(value)}",
        )
        if (!result.successful) {
            return EditResult(false, result.output.ifBlank { "resetprop failed." })
        }
        val verified = shell.root("getprop ${RootShell.quote(name)}")
        return if (verified.successful && verified.output == value) {
            EditResult(true, "Applied until the next reboot.")
        } else {
            EditResult(false, "The command completed, but the live value did not match.")
        }
    }

    suspend fun applyPersistent(name: String, value: String): EditResult {
        validate(name, value)?.let { return EditResult(false, it) }
        val overrides = readPersistentOverrides().apply { put(name, value) }
        val install = installModule(overrides)
        if (!install.success) return install

        val runtime = applyRuntime(name, value)
        return if (runtime.success) {
            EditResult(true, "Applied now and saved for future boots.")
        } else {
            EditResult(
                true,
                "Saved for the next boot, but live apply failed: ${runtime.message}",
            )
        }
    }

    suspend fun removePersistent(name: String): EditResult {
        val overrides = readPersistentOverrides()
        if (overrides.remove(name) == null) {
            return EditResult(true, "No persistent override existed.")
        }
        val install = installModule(overrides)
        return if (install.success) {
            EditResult(true, "Persistent override removed. The current live value remains until reboot or another edit.")
        } else {
            install
        }
    }

    suspend fun createBackup(properties: List<AndroidProperty>): File = withContext(Dispatchers.IO) {
        val directory = File(context.filesDir, "backups").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val file = File(directory, "ro-properties-$stamp.prop")
        file.writeText(
            properties.sortedBy { it.name }.joinToString("\n", postfix = "\n") {
                "${it.name}=${it.value}"
            },
        )
        directory.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(10)
            ?.forEach { it.delete() }
        file
    }

    suspend fun backupCount(): Int = withContext(Dispatchers.IO) {
        File(context.filesDir, "backups").listFiles()?.count { it.isFile } ?: 0
    }

    suspend fun latestBackupValue(name: String): String? = withContext(Dispatchers.IO) {
        val latest = File(context.filesDir, "backups")
            .listFiles()
            ?.filter { it.isFile }
            ?.maxByOrNull { it.lastModified() }
            ?: return@withContext null
        PropertyParser.parseSystemProp(latest.readText())[name]
    }

    private suspend fun readPersistentOverrides(): LinkedHashMap<String, String> {
        val result = shell.root(
            "if [ -f $MODULE_PATH/system.prop ]; then cat $MODULE_PATH/system.prop; fi",
        )
        return if (result.successful) PropertyParser.parseSystemProp(result.output) else linkedMapOf()
    }

    private suspend fun installModule(overrides: Map<String, String>): EditResult = withContext(Dispatchers.IO) {
        val staging = File(context.cacheDir, "roproperties-module").apply { mkdirs() }
        val moduleProp = File(staging, "module.prop").apply {
            writeText(
                """
                id=roproperties_persist
                name=RO Properties persistent overrides
                version=1.0
                versionCode=1
                author=RO Properties
                description=Persistent ro.* overrides managed by the RO Properties app
                """.trimIndent() + "\n",
            )
        }
        val systemProp = File(staging, "system.prop").apply {
            writeText(PropertyParser.toSystemProp(overrides))
        }
        val serviceScript = File(staging, "service.sh").apply {
            writeText(
                """
                #!/system/bin/sh
                PROP_FILE="${'$'}{0%/*}/system.prop"
                [ -f "${'$'}PROP_FILE" ] || exit 0
                while IFS='=' read -r key value; do
                    case "${'$'}key" in ''|'#'*) continue ;; esac
                    resetprop -n "${'$'}key" "${'$'}value"
                done < "${'$'}PROP_FILE"
                """.trimIndent() + "\n",
            )
        }

        val command = listOf(
            "mkdir -p $MODULE_PATH",
            "cp ${RootShell.quote(moduleProp.absolutePath)} $MODULE_PATH/module.prop",
            "cp ${RootShell.quote(systemProp.absolutePath)} $MODULE_PATH/system.prop",
            "cp ${RootShell.quote(serviceScript.absolutePath)} $MODULE_PATH/service.sh",
            "chmod 0644 $MODULE_PATH/module.prop $MODULE_PATH/system.prop",
            "chmod 0755 $MODULE_PATH/service.sh",
            "rm -f $MODULE_PATH/disable $MODULE_PATH/remove",
        ).joinToString(" && ")
        val result = shell.root(command)
        if (result.successful) {
            EditResult(true, "Persistent module updated.")
        } else {
            EditResult(false, result.output.ifBlank { "Could not update the root module." })
        }
    }

    private fun validate(name: String, value: String): String? = when {
        !name.matches(Regex("^ro\\.[A-Za-z0-9_.-]+$")) -> "Only valid ro.* property names can be edited."
        value.contains('\n') || value.contains('\r') || value.contains('\u0000') -> "Property values cannot contain line breaks or null bytes."
        value.length > 4096 -> "Property values longer than 4096 characters are not supported."
        else -> null
    }

    companion object {
        private const val MODULE_PATH = "/data/adb/modules/roproperties_persist"
    }
}
