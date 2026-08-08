package dev.retrofrost.roproperties.data

import android.os.Build
import dev.retrofrost.roproperties.io.ImportedPropertyValue
import dev.retrofrost.roproperties.model.AndroidProperty
import dev.retrofrost.roproperties.model.EditMode
import dev.retrofrost.roproperties.model.EditResult
import dev.retrofrost.roproperties.model.PropertyPolicyClassifier
import dev.retrofrost.roproperties.model.PropertyVerification
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

    suspend fun loadPersistentOverrides(): Map<String, String> {
        val result = shell.root("cat /data/adb/modules/roproperties/system.prop 2>/dev/null || true")
        if (!result.ok && result.output.isBlank()) return emptyMap()
        return result.output.lineSequence()
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isBlank() || trimmed.startsWith('#') || '=' !in trimmed) return@mapNotNull null
                val name = trimmed.substringBefore('=').trim()
                val value = trimmed.substringAfter('=', "")
                if (!name.startsWith("ro.")) null else name to value
            }
            .toMap()
    }

    fun frameworkCachedValue(name: String): String? = when (name) {
        "ro.product.model" -> Build.MODEL
        "ro.product.brand" -> Build.BRAND
        "ro.product.device" -> Build.DEVICE
        "ro.product.manufacturer" -> Build.MANUFACTURER
        "ro.product.name" -> Build.PRODUCT
        "ro.build.fingerprint" -> Build.FINGERPRINT
        "ro.build.id" -> Build.ID
        "ro.build.display.id" -> Build.DISPLAY
        "ro.build.tags" -> Build.TAGS
        "ro.build.type" -> Build.TYPE
        "ro.build.user" -> Build.USER
        "ro.build.host" -> Build.HOST
        "ro.build.version.release" -> Build.VERSION.RELEASE
        "ro.build.version.sdk" -> Build.VERSION.SDK_INT.toString()
        "ro.build.version.security_patch" -> Build.VERSION.SECURITY_PATCH
        else -> null
    }

    suspend fun detectCapabilities(): RootCapabilities {
        val rootCheck = shell.root("id")
        if (!rootCheck.ok || !rootCheck.output.contains("uid=0")) return RootCapabilities()

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

        return RootCapabilities(true, resetProp, modules, framework)
    }

    suspend fun apply(name: String, value: String, mode: EditMode): EditResult =
        applyWithCapabilities(name, value, mode, detectCapabilities())

    suspend fun applyBatch(values: List<ImportedPropertyValue>, mode: EditMode): List<EditResult> {
        val capabilities = detectCapabilities()
        return values.map { applyWithCapabilities(it.name, it.value, mode, capabilities) }
    }

    suspend fun reboot(): EditResult {
        val caps = detectCapabilities()
        if (!caps.rootAvailable) return EditResult(false, "Root access is required to reboot.")
        val result = shell.root("reboot")
        return if (result.ok) EditResult(true, "Reboot requested.")
        else EditResult(false, "Could not reboot: ${result.output.ifBlank { "unknown error" }}")
    }

    private suspend fun applyWithCapabilities(
        name: String,
        value: String,
        mode: EditMode,
        caps: RootCapabilities,
    ): EditResult {
        val validation = validate(name, value)
        if (validation != null) return EditResult(false, validation)

        val policy = PropertyPolicyClassifier.policyFor(name)
        if (!policy.canOverride) {
            return EditResult(
                success = false,
                message = "${policy.editability.label}: ${policy.warning}",
                rebootRecommended = false,
            )
        }
        if (!caps.rootAvailable) return EditResult(false, "Root access was not granted.")

        var runtimeApplied = false
        var persistentApplied = false
        var verification: PropertyVerification? = null
        val messages = mutableListOf<String>()

        if (mode == EditMode.RUNTIME || mode == EditMode.BOTH) {
            if (!caps.resetPropAvailable) {
                messages += "Live override unavailable because resetprop was not found."
            } else {
                val runtime = applyRuntime(name, value)
                runtimeApplied = runtime.success
                verification = runtime.verification
                messages += runtime.message
            }
        }

        if (mode == EditMode.PERSISTENT || mode == EditMode.BOTH) {
            if (!caps.modulePersistenceAvailable) {
                messages += "Persistent override unavailable because /data/adb/modules is not supported by this root setup."
            } else {
                val persistent = applyPersistent(name, value)
                persistentApplied = persistent.success
                messages += persistent.message
            }
        }

        val requiredRuntime = mode == EditMode.RUNTIME || mode == EditMode.BOTH
        val requiredPersistent = mode == EditMode.PERSISTENT || mode == EditMode.BOTH
        val success = (!requiredRuntime || runtimeApplied) && (!requiredPersistent || persistentApplied)
        val rebootRecommended = persistentApplied || policy.cachedSensitive

        if (verification?.propertyServiceMatches == true && verification.frameworkMapped && !verification.frameworkMatches) {
            messages += "Property service changed, but ROProperties' already-running Android framework cache still reports '${verification.frameworkValue}'. Reboot (or at minimum a fresh consumer process) is required for a reliable identity change."
        } else if (runtimeApplied && policy.cachedSensitive) {
            messages += "The property-service write succeeded, but this property is cache/boot-sensitive; that does not prove the subsystem changed behaviour."
        }

        return EditResult(
            success = success,
            message = messages.joinToString(" "),
            runtimeApplied = runtimeApplied,
            persistentApplied = persistentApplied,
            rebootRecommended = rebootRecommended,
            verification = verification,
        )
    }

    private suspend fun applyRuntime(name: String, value: String): EditResult {
        val result = shell.root("resetprop ${shellQuote(name)} ${shellQuote(value)}")
        if (!result.ok) {
            return EditResult(false, "resetprop failed: ${result.output.ifBlank { "unknown error" }}")
        }

        val serviceValue = shell.shell("getprop ${shellQuote(name)}").output.trim()
        val verification = PropertyVerification(
            requestedValue = value,
            propertyServiceValue = serviceValue,
            frameworkValue = frameworkCachedValue(name),
        )
        return if (verification.propertyServiceMatches) {
            EditResult(
                success = true,
                message = "Live property-service value applied and verified with getprop.",
                runtimeApplied = true,
                verification = verification,
            )
        } else {
            EditResult(
                success = false,
                message = "resetprop returned successfully, but getprop still reports '$serviceValue'.",
                verification = verification,
            )
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
                message = "Persistent override saved for the next boot.",
                persistentApplied = true,
                rebootRecommended = true,
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
