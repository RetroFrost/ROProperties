package dev.retrofrost.roproperties.model

object PropertyPolicyClassifier {
    private val readOnlyExact = setOf(
        "ro.boot.verifiedbootstate",
        "ro.boot.vbmeta.device_state",
        "ro.boot.flash.locked",
        "ro.boot.slot_suffix",
    )

    private val dangerousExact = setOf(
        "ro.zygote",
        "ro.crypto.state",
        "ro.crypto.type",
        "ro.treble.enabled",
        "ro.virtual_ab.enabled",
        "ro.virtual_ab.compression.enabled",
        "ro.apex.updatable",
        "ro.control_privapp_permissions",
        "ro.vndk.version",
        "ro.vendor.api_level",
        "ro.board.first_api_level",
        "ro.debuggable",
        "ro.secure",
        "ro.adb.secure",
    )

    fun policyFor(name: String): PropertyPolicy {
        val normalized = name.lowercase()

        if (normalized in readOnlyExact || normalized.startsWith("ro.boot.avb")) {
            return PropertyPolicy(
                editability = PropertyEditability.READ_ONLY,
                strategy = ApplyStrategy.DO_NOT_MODIFY,
                canOverride = false,
                cachedSensitive = true,
                descriptiveOnly = true,
                warning = "This value is derived from bootloader/verified-boot state. Changing the property string cannot change the underlying trust state, so ROProperties keeps it information-only.",
            )
        }

        if (normalized in dangerousExact || normalized.contains("dynamic_partitions")) {
            return PropertyPolicy(
                editability = PropertyEditability.DANGEROUS,
                strategy = ApplyStrategy.DO_NOT_MODIFY,
                canOverride = false,
                cachedSensitive = true,
                descriptiveOnly = false,
                warning = "This property describes or controls core security, runtime architecture, encryption, partitioning or compatibility behaviour. ROProperties intentionally does not override it.",
            )
        }

        if (PropertyCategoryClassifier.isSpoofingProperty(name)) {
            return PropertyPolicy(
                editability = PropertyEditability.SPOOFABLE,
                strategy = ApplyStrategy.NEXT_BOOT,
                canOverride = true,
                cachedSensitive = true,
                descriptiveOnly = true,
                warning = "Identity-facing string. A live override can change getprop while already-running Android/framework processes still report the old cached identity. Persistent + reboot is the reliable strategy.",
            )
        }

        if (normalized.startsWith("ro.boot.") ||
            normalized.startsWith("ro.vendor.") ||
            normalized.startsWith("ro.vendor_dlkm.") ||
            normalized.startsWith("ro.odm.") ||
            normalized.contains("first_api_level")
        ) {
            return PropertyPolicy(
                editability = PropertyEditability.BOOT_TIME,
                strategy = ApplyStrategy.NEXT_BOOT,
                canOverride = true,
                cachedSensitive = true,
                descriptiveOnly = false,
                warning = "This property is normally established during boot or belongs to a vendor/ODM namespace. A live write may be visible to getprop without reconfiguring the subsystem that consumed it.",
            )
        }

        if (normalized.contains("debug") || normalized.contains("trace") || normalized.contains("logging")) {
            return PropertyPolicy(
                editability = PropertyEditability.ADVANCED,
                strategy = ApplyStrategy.RESTART_PROCESS,
                canOverride = true,
                cachedSensitive = true,
                descriptiveOnly = false,
                warning = "Consumers may read this only at process/service startup. Changing the property service does not guarantee an already-running consumer will re-read it.",
            )
        }

        return PropertyPolicy(
            editability = PropertyEditability.ADVANCED,
            strategy = ApplyStrategy.LIVE,
            canOverride = true,
            cachedSensitive = false,
            descriptiveOnly = false,
            warning = "Undocumented or general property. ROProperties can override the property-service value, but the real subsystem behaviour is not guaranteed to follow the string.",
        )
    }
}
