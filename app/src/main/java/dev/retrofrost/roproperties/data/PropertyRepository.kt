package dev.retrofrost.roproperties.data

import dev.retrofrost.roproperties.io.ImportedPropertyValue
import dev.retrofrost.roproperties.model.AndroidProperty
import dev.retrofrost.roproperties.model.EditMode
import dev.retrofrost.roproperties.model.EditResult
import dev.retrofrost.roproperties.model.RootCapabilities

class PropertyRepository(
    private val shell: ShellExecutor = ShellExecutor(),
) {
    suspend fun loadProperties(): List<AndroidProperty> {
        val result = shell.shell("getprop")
        if (!result.ok) return emptyList()

        return result.output
            .lineSequence()
            .mapNotNull(::parsePropertyLine)
            .filter { it.name.startsWith("ro.") }
            .sortedBy { it.name }
            .toList()
    }

    suspend fun detectCapabilities(): RootCapabilities {
        val rootCheck = shell.root("id")
        if (!rootCheck.ok || !rootCheck.output.contains("uid=0")) {
            return RootCapabilities()
        }

        val resetProp = shell.root("command -v resetprop >/dev/null 2>&1 && echo yes || echo no")
            .output.lineSequence().lastOrNull() == "yes"
        val modules = shell.root("test -d /data/adb/modules && echo yes || echo no")
            .output.lineSequence().lastOrNull() == "yes"
        val framework = shell.root(
            "if command -v magisk >/dev/null 2>&1; then echo Magisk; " +
                "elif test -d /data/adb/ksu; then echo KernelSU; " +
                "elif test -d /data/adb/ap; then echo APatch; " +
                "else echo 'Root shell'; fi"
        ).output.lineSequence().lastOrNull().orEmpty().ifBlank { "Root shell" }

        return RootCapabilities(
            rootAvailable = true,
            resetPropAvailable = resetProp,
            modulePersistenceAvailable = modules,
            framework = framework,
        )
    }

    suspend fun apply(name: String, value: String, mode: EditMode): EditResult {
        return applyWithCapabilities(name, value, mode, detectCapabilities())
    }

    suspend fun applyBatch(values: List<ImportedPropertyValue>, mode: EditMode): List<EditResult> {
        val capabilities = detectCapabilities()
        return values.map { value ->
            applyWithCapabilities(value.name, value.value, mode, capabilities)
        }
    }

    private suspend fun applyWithCapabilities(
        name: String,
        value: String,
        mode: EditMode,
        caps: RootCapabilities,
    ): EditResult {
        val validation = validate(name, value)
        if (validation != null) return EditResult(false, validation)

        if (!caps.rootAvailable) {
            return EditResult(false, "Root access was not granted.")
        }

        var runtimeApplied = false
        var persistentApplied = false
        val messages = mutableListOf<String>()

        if (mode == EditMode.RUNTIME || mode == EditMode.BOTH) {
            if (!caps.resetPropAvailable) {
                messages += "Runtime editing is unavailable because resetprop was not found."
            } else {
                val runtime = applyRuntime(name, value)
                runtimeApplied = runtime.success
                messages += runtime.message
            }
        }

        if (mode == EditMode.PERSISTENT || mode == EditMode.BOTH) {
            if (!caps.modulePersistenceAvailable) {
                messages += "Persistent editing is unavailable because /data/adb/modules is not supported by this root setup."
            } else {
                val persistent = applyPersistent(name, value)
                persistentApplied = persistent.success
                messages += persistent.message
            }
        }

        val requiredRuntime = mode == EditMode.RUNTIME || mode == EditMode.BOTH
        val requiredPersistent = mode == EditMode.PERSISTENT || mode == EditMode.BOTH
        val success = (!requiredRuntime || runtimeApplied) && (!requiredPersistent || persistentApplied)

        return EditResult(
            success = success,
            message = messages.joinToString(" "),
            runtimeApplied = runtimeApplied,
            persistentApplied = persistentApplied,
        )
    }

    private suspend fun applyRuntime(name: String, value: String): EditResult {
        val command = "resetprop ${shellQuote(name)} ${shellQuote(value)}"
        val result = shell.root(command)
        if (!result.ok) {
            return EditResult(false, "resetprop failed: ${result.output.ifBlank { "unknown error" }}")
        }

        val verified = shell.shell("getprop ${shellQuote(name)}").output.trim()
        return if (verified == value) {
            EditResult(true, "Runtime value applied and verified.", runtimeApplied = true)
        } else {
            EditResult(false, "resetprop returned successfully, but getprop did not report the requested value.")
        }
    }

    private suspend fun applyPersistent(name: String, value: String): EditResult {
        val key = shellQuote(name)
        val newValue = shellQuote(value)
        val command = """
            set -eu
            MOD=/data/adb/modules/roproperties
            mkdir -p "${'$'}MOD"
            cat > "${'$'}MOD/module.prop" <<'ROP_MODULE_EOF'
            id=roproperties
            name=ROProperties persistent overrides
            version=1.0
            versionCode=1
            author=ROProperties
            description=Persistent ro.* overrides managed by the ROProperties app
            ROP_MODULE_EOF
            touch "${'$'}MOD/system.prop"
            TMP="${'$'}MOD/system.prop.tmp"
            awk -F= -v key=$key '${'$'}1 != key { print }' "${'$'}MOD/system.prop" > "${'$'}TMP"
            printf '%s=%s\n' $key $newValue >> "${'$'}TMP"
            mv "${'$'}TMP" "${'$'}MOD/system.prop"
            chmod 0755 "${'$'}MOD"
            chmod 0644 "${'$'}MOD/module.prop" "${'$'}MOD/system.prop"
        """.trimIndent()

        val result = shell.root(command)
        return if (result.ok) {
            EditResult(
                success = true,
                message = "Persistent override saved; it will be applied by the root module framework on the next boot.",
                persistentApplied = true,
            )
        } else {
            EditResult(false, "Could not save persistent override: ${result.output.ifBlank { "unknown error" }}")
        }
    }

    private fun parsePropertyLine(line: String): AndroidProperty? {
        val match = PROPERTY_LINE.matchEntire(line.trim()) ?: return null
        return AndroidProperty(match.groupValues[1], match.groupValues[2])
    }

    private fun validate(name: String, value: String): String? {
        if (!name.startsWith("ro.")) return "Only ro.* properties can be edited."
        if (!PROPERTY_NAME.matches(name)) return "The property name contains unsupported characters."
        if ('\n' in value || '\r' in value) return "Property values cannot contain line breaks."
        if (value.toByteArray(Charsets.UTF_8).size > 91) {
            return "This value is longer than Android's traditional property value limit (91 UTF-8 bytes)."
        }
        return null
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private companion object {
        val PROPERTY_LINE = Regex("^\\[([^]]+)]\\s*:\\s*\\[(.*)]$")
        val PROPERTY_NAME = Regex("[A-Za-z0-9_.-]+")
    }
}
