package dev.retrofrost.roproperties.knowledge

import dev.retrofrost.roproperties.model.AndroidProperty
import dev.retrofrost.roproperties.model.KnowledgeConfidence
import dev.retrofrost.roproperties.model.PropertyExplanation
import dev.retrofrost.roproperties.model.RiskLevel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object PropertyKnowledgeEngine {
    private data class Definition(
        val meaning: String,
        val origin: String = "AOSP / Android platform",
        val confidence: KnowledgeConfidence = KnowledgeConfidence.DOCUMENTED,
        val risk: RiskLevel = RiskLevel.MEDIUM,
        val consumers: String = "Android framework and system components",
        val knownValues: Map<String, String> = emptyMap(),
        val valueInterpreter: ((String) -> String)? = null,
    )

    private val booleanValues = linkedMapOf(
        "1" to "Enabled / true for this flag.",
        "0" to "Disabled / false for this flag.",
        "true" to "Enabled / true for this flag.",
        "false" to "Disabled / false for this flag.",
    )

    private val definitions = mapOf(
        "ro.build.type" to Definition(
            meaning = "Android build variant selected when the system image was built.",
            risk = RiskLevel.HIGH,
            consumers = "Framework debugging policy, build tooling and apps that inspect build identity.",
            knownValues = linkedMapOf(
                "user" to "Production-oriented build with debugging features restricted.",
                "userdebug" to "User-like build with additional debugging and root-oriented development capabilities compiled in.",
                "eng" to "Engineering build intended for platform development with the least restrictive defaults.",
            ),
        ),
        "ro.build.tags" to Definition(
            meaning = "Tags describing how the build was signed or classified.",
            risk = RiskLevel.HIGH,
            knownValues = linkedMapOf(
                "release-keys" to "Typically indicates a production build signed with release keys.",
                "test-keys" to "Typically indicates a development/test build signed with test keys.",
                "dev-keys" to "Development-key build tag used by some platform builds.",
            ),
        ),
        "ro.build.version.release" to Definition(
            meaning = "User-visible Android platform release version baked into the build.",
            risk = RiskLevel.HIGH,
            consumers = "Framework APIs, About phone UI and apps that inspect Build.VERSION.RELEASE.",
        ),
        "ro.build.version.sdk" to Definition(
            meaning = "Android SDK/API level reported by the running build.",
            risk = RiskLevel.CRITICAL,
            consumers = "Android framework compatibility logic and apps through Build.VERSION.SDK_INT.",
            valueInterpreter = { value -> value.toIntOrNull()?.let { "API level $it. Changing the string does not install or remove APIs from the framework." } ?: "Expected to be a numeric Android API level; this value is unusual." },
        ),
        "ro.build.version.preview_sdk" to Definition(
            meaning = "Preview SDK revision for a pre-release Android platform; normally zero on a final release.",
            risk = RiskLevel.HIGH,
            valueInterpreter = { value -> if (value == "0") "Final/non-preview SDK state." else "Preview SDK revision $value." },
        ),
        "ro.build.version.codename" to Definition(
            meaning = "Platform development codename exposed by Build.VERSION.CODENAME.",
            risk = RiskLevel.HIGH,
            knownValues = mapOf("REL" to "Final release build rather than an active development codename."),
        ),
        "ro.build.version.security_patch" to Definition(
            meaning = "Android security patch level declared by the build.",
            risk = RiskLevel.HIGH,
            consumers = "Framework security-patch reporting, compatibility checks and apps reading Build.VERSION.SECURITY_PATCH.",
            valueInterpreter = { value -> "Declared security patch level: $value. Changing it only spoofs the declaration; it does not install security fixes." },
        ),
        "ro.build.fingerprint" to Definition(
            meaning = "Canonical build fingerprint identifying the software build and product configuration.",
            risk = RiskLevel.CRITICAL,
            consumers = "Framework build identity, update/compatibility logic, attestation-related inputs and applications.",
            valueInterpreter = ::fingerprintMeaning,
        ),
        "ro.build.description" to Definition(
            meaning = "Human-readable description generated for the Android build.",
            risk = RiskLevel.MEDIUM,
        ),
        "ro.build.id" to Definition(
            meaning = "Build identifier assigned to the platform release/build branch.",
            risk = RiskLevel.HIGH,
        ),
        "ro.build.display.id" to Definition(
            meaning = "Human-facing build identifier commonly shown in system information screens.",
            risk = RiskLevel.MEDIUM,
        ),
        "ro.build.date.utc" to Definition(
            meaning = "Build timestamp expressed as Unix time in seconds.",
            risk = RiskLevel.LOW,
            valueInterpreter = ::unixTimeMeaning,
        ),
        "ro.product.first_api_level" to Definition(
            meaning = "First Android API level with which this product originally launched; used for compatibility requirements that depend on launch version.",
            risk = RiskLevel.CRITICAL,
            consumers = "Compatibility framework, VTS/CTS rules and vendor/platform compatibility decisions.",
            valueInterpreter = { value -> "Device launch API level: $value. Spoofing this cannot retroactively change the device's vendor implementation." },
        ),
        "ro.board.first_api_level" to Definition(
            meaning = "First API level associated with the device board/vendor implementation.",
            risk = RiskLevel.CRITICAL,
            consumers = "Vendor compatibility and platform compatibility checks.",
        ),
        "ro.vendor.api_level" to Definition(
            meaning = "API level associated with the vendor implementation on devices that expose it.",
            origin = "Vendor / Android compatibility layer",
            risk = RiskLevel.CRITICAL,
        ),
        "ro.debuggable" to Definition(
            meaning = "Build-time flag indicating whether the system was built as debuggable.",
            risk = RiskLevel.CRITICAL,
            consumers = "init, adb/framework debugging policy and native/platform debugging paths.",
            knownValues = booleanValues,
        ),
        "ro.secure" to Definition(
            meaning = "Legacy/core Android security flag used by init/adb behaviour on platform builds.",
            risk = RiskLevel.CRITICAL,
            knownValues = mapOf(
                "1" to "Secure production-style setting on normal Android builds.",
                "0" to "Less restrictive development-style setting; changing the property alone does not rebuild adbd or remove compiled security restrictions.",
            ),
        ),
        "ro.adb.secure" to Definition(
            meaning = "Controls whether ADB authentication is expected on builds/components that honour this property.",
            risk = RiskLevel.CRITICAL,
            consumers = "ADB daemon and related platform startup logic when supported by the build.",
            knownValues = booleanValues,
        ),
        "ro.treble.enabled" to Definition(
            meaning = "Declares whether the device uses the Android Treble system/vendor architecture.",
            risk = RiskLevel.CRITICAL,
            consumers = "Framework/vendor compatibility and Treble-aware platform components.",
            knownValues = booleanValues,
        ),
        "ro.virtual_ab.enabled" to Definition(
            meaning = "Declares support for Virtual A/B seamless updates.",
            risk = RiskLevel.CRITICAL,
            consumers = "Update Engine, snapshot/update infrastructure and framework update logic.",
            knownValues = booleanValues,
        ),
        "ro.virtual_ab.compression.enabled" to Definition(
            meaning = "Declares whether compressed Virtual A/B snapshots are enabled.",
            risk = RiskLevel.CRITICAL,
            consumers = "Virtual A/B snapshot/update infrastructure.",
            knownValues = booleanValues,
        ),
        "ro.apex.updatable" to Definition(
            meaning = "Declares whether updatable APEX modules are supported/enabled for the build.",
            risk = RiskLevel.CRITICAL,
            consumers = "APEX manager and Android modular system component infrastructure.",
            knownValues = booleanValues,
        ),
        "ro.control_privapp_permissions" to Definition(
            meaning = "Controls how privileged-permission allowlist violations are handled.",
            risk = RiskLevel.CRITICAL,
            consumers = "Package Manager privileged permission enforcement.",
            knownValues = linkedMapOf(
                "enforce" to "Enforce privileged-permission allowlists.",
                "log" to "Log allowlist violations instead of enforcing all of them.",
                "disable" to "Disable this allowlist enforcement mode on builds that support the value.",
            ),
        ),
        "ro.crypto.state" to Definition(
            meaning = "Reports the device data-encryption state.",
            risk = RiskLevel.CRITICAL,
            consumers = "vold, framework storage/encryption logic and system UI reporting.",
            knownValues = mapOf(
                "encrypted" to "User-data storage is reported as encrypted.",
                "unencrypted" to "User-data storage is reported as unencrypted.",
                "unsupported" to "Encryption is reported as unsupported by this implementation.",
            ),
        ),
        "ro.crypto.type" to Definition(
            meaning = "Reports the Android data-encryption scheme in use.",
            risk = RiskLevel.CRITICAL,
            knownValues = mapOf(
                "file" to "File-based encryption (FBE).",
                "block" to "Legacy block/full-disk encryption style.",
            ),
        ),
        "ro.zygote" to Definition(
            meaning = "Selects the zygote process architecture configuration used to launch Android app processes.",
            risk = RiskLevel.CRITICAL,
            consumers = "init zygote service definitions and Android runtime startup.",
            knownValues = linkedMapOf(
                "zygote64_32" to "Primary 64-bit zygote with secondary 32-bit zygote.",
                "zygote32_64" to "Primary 32-bit zygote with secondary 64-bit zygote.",
                "zygote64" to "64-bit-only zygote configuration.",
                "zygote32" to "32-bit-only zygote configuration.",
            ),
        ),
        "ro.sf.lcd_density" to Definition(
            meaning = "Default logical display density configured for SurfaceFlinger/framework display scaling.",
            risk = RiskLevel.MEDIUM,
            consumers = "Display/framework resource scaling and density calculations.",
            valueInterpreter = { value -> value.toIntOrNull()?.let { "$it dpi logical density." } ?: "Expected to be a numeric logical density in dpi." },
        ),
        "ro.config.low_ram" to Definition(
            meaning = "Declares a low-RAM device configuration so Android can choose lower-memory behaviour.",
            risk = RiskLevel.HIGH,
            consumers = "Framework memory policy and apps querying ActivityManager.isLowRamDevice().",
            knownValues = booleanValues,
        ),
        "ro.opengles.version" to Definition(
            meaning = "Encoded OpenGL ES version reported as the device's required graphics feature version.",
            risk = RiskLevel.HIGH,
            consumers = "Package Manager graphics feature reporting and application compatibility.",
            valueInterpreter = ::openGlMeaning,
        ),
        "ro.vndk.version" to Definition(
            meaning = "VNDK/vendor interface version associated with the running vendor/system compatibility boundary.",
            risk = RiskLevel.CRITICAL,
            consumers = "Native linker namespaces and vendor/system native compatibility logic.",
        ),
        "ro.boot.verifiedbootstate" to Definition(
            meaning = "Android Verified Boot state propagated from bootloader/AVB information.",
            origin = "Bootloader / AVB boot property",
            risk = RiskLevel.CRITICAL,
            consumers = "Verified-boot reporting and components that inspect boot trust state.",
            knownValues = linkedMapOf(
                "green" to "Boot chain is reported as verified with the expected trusted key/state.",
                "yellow" to "Boot image is verified with a user-settable/custom trusted key on implementations using this state.",
                "orange" to "Bootloader is reported unlocked, so verified boot cannot provide the normal locked-device trust guarantee.",
                "red" to "Verification failure / untrusted boot state.",
            ),
        ),
        "ro.boot.vbmeta.device_state" to Definition(
            meaning = "Bootloader lock state propagated from AVB/vbmeta boot information.",
            origin = "Bootloader / AVB boot property",
            risk = RiskLevel.CRITICAL,
            knownValues = mapOf(
                "locked" to "Bootloader/device state is reported locked.",
                "unlocked" to "Bootloader/device state is reported unlocked.",
            ),
        ),
        "ro.boot.flash.locked" to Definition(
            meaning = "Boot property reporting whether flashing/bootloader state is locked.",
            origin = "Bootloader boot property",
            risk = RiskLevel.CRITICAL,
            knownValues = booleanValues,
        ),
        "ro.boot.slot_suffix" to Definition(
            meaning = "Suffix of the currently selected A/B boot slot, when the device uses slots.",
            origin = "Bootloader / boot control",
            risk = RiskLevel.CRITICAL,
            knownValues = mapOf("_a" to "Slot A.", "_b" to "Slot B."),
        ),
    )

    fun explain(property: AndroidProperty): PropertyExplanation {
        val exact = definitions[property.name]
        val pattern = exact ?: patternedDefinition(property.name)
        val origin = pattern?.origin ?: inferOrigin(property.name)
        val confidence = pattern?.confidence ?: KnowledgeConfidence.INFERRED
        val risk = pattern?.risk ?: inferRisk(property.name)
        val meaning = pattern?.meaning ?: inferredPropertyMeaning(property.name, origin)
        val valueMeaning = pattern?.knownValues?.get(property.value)
            ?: pattern?.knownValues?.get(property.value.lowercase())
            ?: pattern?.valueInterpreter?.invoke(property.value)
            ?: genericValueMeaning(property.name, property.value)

        return PropertyExplanation(
            propertyMeaning = meaning,
            valueMeaning = valueMeaning,
            knownValues = pattern?.knownValues ?: emptyMap(),
            origin = origin,
            confidence = confidence,
            risk = risk,
            consumers = pattern?.consumers ?: inferConsumers(property.name),
            editBehaviour = editBehaviour(property.name),
            rebootRequirement = "Runtime resetprop changes are immediate at the property-service level, but already-running components may have cached the old value. Persistent overrides require a reboot to take effect during the next boot.",
        )
    }

    private fun patternedDefinition(name: String): Definition? = when {
        name.matches(Regex("ro\\.product(?:\\.[^.]+)?\\.model")) -> Definition(
            meaning = "Product model name exposed for this Android partition/product identity.",
            risk = RiskLevel.MEDIUM,
            consumers = "Build product identity APIs, system UI and applications.",
            valueInterpreter = { "Reported model: $it." },
        )
        name.matches(Regex("ro\\.product(?:\\.[^.]+)?\\.manufacturer")) -> Definition(
            meaning = "Manufacturer name exposed for this product/partition identity.",
            risk = RiskLevel.MEDIUM,
            valueInterpreter = { "Reported manufacturer: $it." },
        )
        name.matches(Regex("ro\\.product(?:\\.[^.]+)?\\.brand")) -> Definition(
            meaning = "Brand identifier exposed for this product/partition identity.",
            risk = RiskLevel.MEDIUM,
            valueInterpreter = { "Reported brand: $it." },
        )
        name.matches(Regex("ro\\.product(?:\\.[^.]+)?\\.device")) -> Definition(
            meaning = "Device/codename identifier exposed for this product/partition identity.",
            risk = RiskLevel.HIGH,
            valueInterpreter = { "Reported device identifier: $it." },
        )
        name.matches(Regex("ro\\.product(?:\\.[^.]+)?\\.name")) -> Definition(
            meaning = "Product name identifier exposed for this Android partition/product identity.",
            risk = RiskLevel.HIGH,
            valueInterpreter = { "Reported product name: $it." },
        )
        name.contains("abilist") -> Definition(
            meaning = "Ordered list of application binary interfaces (ABIs) that this build reports as supported.",
            risk = RiskLevel.CRITICAL,
            consumers = "Package Manager, native library selection and Android runtime process architecture.",
            valueInterpreter = { value -> "Reported ABIs: ${value.split(',').joinToString()}. Changing the list cannot add CPU/runtime support that is absent from the system." },
        )
        name.endsWith(".fingerprint") -> Definition(
            meaning = "Build fingerprint for this partition or product identity.",
            risk = RiskLevel.CRITICAL,
            consumers = "Build identity and compatibility/update components that inspect this partition's fingerprint.",
            valueInterpreter = ::fingerprintMeaning,
        )
        name.endsWith(".security_patch") -> Definition(
            meaning = "Security patch level declared for this Android partition/component.",
            risk = RiskLevel.HIGH,
            valueInterpreter = { "Declared patch level: $it. Editing it does not install security fixes." },
        )
        name.endsWith(".build.date.utc") || name.endsWith(".date.utc") -> Definition(
            meaning = "Unix build timestamp for this partition/component.",
            risk = RiskLevel.LOW,
            valueInterpreter = ::unixTimeMeaning,
        )
        looksBoolean(name) -> Definition(
            meaning = inferredPropertyMeaning(name, inferOrigin(name)),
            origin = inferOrigin(name),
            confidence = KnowledgeConfidence.INFERRED,
            risk = inferRisk(name),
            consumers = inferConsumers(name),
            knownValues = booleanValues,
        )
        else -> null
    }

    private fun inferOrigin(name: String): String = when {
        name.startsWith("ro.boot.") -> "Bootloader / kernel / init boot property"
        name.startsWith("ro.vendor.") -> "Vendor partition / device implementation"
        name.startsWith("ro.odm.") -> "ODM partition / device implementation"
        name.startsWith("ro.product.") -> "Product partition / product identity"
        name.startsWith("ro.system_ext.") -> "System_ext partition"
        name.startsWith("ro.system.") -> "System partition / Android platform"
        name.startsWith("ro.samsung.") -> "Samsung-specific Android property"
        name.startsWith("ro.miui.") || name.startsWith("ro.xiaomi.") -> "Xiaomi/MIUI-specific Android property"
        name.startsWith("ro.oplus.") || name.startsWith("ro.vendor.oplus.") -> "OPPO/OnePlus/OPlus-specific Android property"
        name.startsWith("ro.mtk.") || name.startsWith("ro.mediatek.") -> "MediaTek-specific Android property"
        name.startsWith("ro.qcom.") || name.startsWith("ro.qualcomm.") -> "Qualcomm-specific Android property"
        name.startsWith("ro.google.") -> "Google-specific Android property"
        else -> "Android system / device-specific property"
    }

    private fun inferredPropertyMeaning(name: String, origin: String): String {
        val topic = name
            .removePrefix("ro.")
            .split('.')
            .filterNot { it in setOf("vendor", "odm", "product", "system", "system_ext", "boot") }
            .joinToString(" ") { token -> token.replace('_', ' ') }
            .ifBlank { "device configuration" }
        return "Undocumented or uncatalogued $origin property related to “$topic”. The exact semantics depend on the component that defines and reads it; ROProperties is intentionally marking this explanation as inferred."
    }

    private fun genericValueMeaning(name: String, value: String): String {
        if (value.isEmpty()) return "Empty value. This usually means no concrete value was supplied, but the exact meaning is property-specific."
        if (looksBoolean(name) && value.lowercase() in booleanValues) return booleanValues.getValue(value.lowercase())
        if (value == "user" || value == "userdebug" || value == "eng") return "Build-style token “$value”; its exact effect here depends on this property's consumer."
        if (value == "REL") return "Release/final-build token commonly used by Android version metadata."
        if (value.contains(',') && !value.contains(' ')) return "Comma-separated list containing ${value.split(',').size} entries: $value"
        if (value.matches(Regex("-?\\d+"))) return "Numeric value $value. No documented mapping is available for this specific property, so ROProperties will not invent one."
        if (value.equals("true", true) || value.equals("false", true)) return "Boolean-looking value “$value”; exact semantics are property-specific."
        if (value.startsWith("/")) return "Filesystem path: $value"
        if (value.matches(Regex("0x[0-9a-fA-F]+"))) return "Hexadecimal value $value; no documented mapping is currently catalogued for this property."
        return "Current literal value: “$value”. No authoritative value mapping is catalogued for this property, so its exact meaning is left explicitly unresolved."
    }

    private fun inferRisk(name: String): RiskLevel = when {
        name.contains("verifiedboot", true) ||
            name.contains("vbmeta", true) ||
            name.contains("crypto", true) ||
            name.contains("zygote", true) ||
            name.contains("abi", true) ||
            name.contains("treble", true) ||
            name.contains("vndk", true) ||
            name.contains("api_level", true) ||
            name.contains("debuggable", true) ||
            name.contains("secure", true) ||
            name.contains("slot", true) ||
            name.contains("dynamic_partition", true) -> RiskLevel.CRITICAL
        name.contains("fingerprint", true) ||
            name.contains("build.type", true) ||
            name.contains("version.sdk", true) ||
            name.contains("hardware", true) ||
            name.contains("soc", true) ||
            name.startsWith("ro.boot.") -> RiskLevel.HIGH
        name.contains("model", true) || name.contains("brand", true) || name.contains("manufacturer", true) -> RiskLevel.MEDIUM
        else -> RiskLevel.MEDIUM
    }

    private fun inferConsumers(name: String): String = when {
        name.startsWith("ro.boot.") -> "Boot/init-time code and any framework or vendor components that inspect this propagated boot property."
        name.startsWith("ro.vendor.") || name.startsWith("ro.odm.") -> "Vendor services, HALs, init scripts or OEM framework components; exact consumer is device-specific."
        name.contains("build", true) || name.startsWith("ro.product.") -> "Android build/product identity APIs and any apps or system components that inspect this value."
        else -> "Unknown or device-specific consumer. Search the device's framework, init scripts, vendor binaries or source tree for the exact property name to identify it authoritatively."
    }

    private fun editBehaviour(name: String): String {
        val suffix = if (inferRisk(name) >= RiskLevel.HIGH) {
            " This property is compatibility/boot sensitive; changing the text does not recreate the underlying hardware, framework capability or security state it may describe."
        } else ""
        return "Because this is a ro.* property, Android normally freezes it after boot. ROProperties uses resetprop for a runtime override and a root-module system.prop for persistence.$suffix"
    }

    private fun looksBoolean(name: String): Boolean {
        val lower = name.lowercase()
        return listOf("enabled", "enable", "disabled", "secure", "debuggable", "locked", "support", "supported", "low_ram", "updatable").any(lower::contains)
    }

    private fun fingerprintMeaning(value: String): String {
        val regex = Regex("([^/]+)/([^/]+)/([^:]+):([^/]+)/([^/]+)/([^:]+):([^/]+)/(.+)")
        val match = regex.matchEntire(value)
        return if (match != null) {
            val (brand, product, device, release, id, incremental, type, tags) = match.destructured
            "Fingerprint reports brand $brand, product $product, device $device, Android $release, build $id/$incremental, type $type and tags $tags. Spoofing it does not transform the actual system image."
        } else {
            "Build fingerprint string: $value. It does not match the common Android fingerprint layout exactly."
        }
    }

    private fun unixTimeMeaning(value: String): String {
        val seconds = value.toLongOrNull() ?: return "Expected a Unix timestamp in seconds; “$value” is not numeric."
        return runCatching {
            val formatted = DateTimeFormatter.ISO_OFFSET_DATE_TIME
                .format(Instant.ofEpochSecond(seconds).atZone(ZoneId.systemDefault()))
            "Unix timestamp $seconds, corresponding to $formatted on this device's current time zone."
        }.getOrElse { "Unix timestamp: $seconds." }
    }

    private fun openGlMeaning(value: String): String {
        val encoded = value.toIntOrNull() ?: return "Expected Android's integer OpenGL ES version encoding; got “$value”."
        val major = encoded shr 16
        val minor = encoded and 0xffff
        return "Encoded OpenGL ES version $major.$minor ($encoded). Changing this cannot add GPU driver capabilities."
    }
}
