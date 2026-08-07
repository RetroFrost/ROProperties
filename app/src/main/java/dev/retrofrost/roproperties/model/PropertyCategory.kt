package dev.retrofrost.roproperties.model

enum class PropertyCategory(
    val label: String,
    val description: String,
) {
    ALL(
        label = "All properties",
        description = "Every ro.* property exposed by Android on this device.",
    ),
    SPOOFING(
        label = "Spoofing properties",
        description = "Build and device identity values commonly read when software identifies the device.",
    ),
    BUILD_IDENTITY(
        label = "Build & identity",
        description = "Android build metadata, product identity and version information.",
    ),
    HARDWARE(
        label = "Hardware",
        description = "Board, SoC, CPU, ABI and hardware-facing properties.",
    ),
    OEM_VENDOR(
        label = "OEM & vendor",
        description = "Vendor, ODM and manufacturer-specific properties.",
    ),
    RUNTIME_DEBUG(
        label = "Runtime & debug",
        description = "Debug, ADB, boot-state and runtime configuration properties.",
    ),
}

object PropertyCategoryClassifier {
    private val spoofingExact = setOf(
        "ro.build.fingerprint",
        "ro.build.id",
        "ro.build.description",
        "ro.build.display.id",
        "ro.build.tags",
        "ro.build.type",
        "ro.build.user",
        "ro.build.host",
        "ro.build.version.release",
        "ro.build.version.incremental",
        "ro.build.version.security_patch",
        "ro.build.version.sdk",
        "ro.product.brand",
        "ro.product.device",
        "ro.product.manufacturer",
        "ro.product.model",
        "ro.product.name",
        "ro.product.marketname",
        "ro.product.first_api_level",
    )

    private val spoofingSuffixes = setOf(
        ".build.fingerprint",
        ".build.id",
        ".build.description",
        ".build.tags",
        ".build.type",
        ".build.version.release",
        ".build.version.incremental",
        ".build.version.security_patch",
        ".product.brand",
        ".product.device",
        ".product.manufacturer",
        ".product.model",
        ".product.name",
    )

    fun matches(category: PropertyCategory, item: PropertyUiItem): Boolean = when (category) {
        PropertyCategory.ALL -> true
        PropertyCategory.SPOOFING -> isSpoofingProperty(item.property.name)
        PropertyCategory.BUILD_IDENTITY -> isBuildIdentity(item.property.name)
        PropertyCategory.HARDWARE -> isHardware(item.property.name)
        PropertyCategory.OEM_VENDOR -> isOemVendor(item)
        PropertyCategory.RUNTIME_DEBUG -> isRuntimeDebug(item.property.name)
    }

    fun isSpoofingProperty(name: String): Boolean {
        val normalized = name.lowercase()
        return normalized in spoofingExact || spoofingSuffixes.any(normalized::endsWith)
    }

    private fun isBuildIdentity(name: String): Boolean {
        val normalized = name.lowercase()
        return normalized.startsWith("ro.build.") ||
            normalized.startsWith("ro.product.") ||
            normalized.contains(".build.") ||
            normalized.contains(".product.")
    }

    private fun isHardware(name: String): Boolean {
        val normalized = name.lowercase()
        return listOf(
            "hardware",
            "board",
            "platform",
            "soc",
            "chip",
            "cpu",
            "abi",
            "gpu",
            "egl",
        ).any(normalized::contains)
    }

    private fun isOemVendor(item: PropertyUiItem): Boolean {
        val name = item.property.name.lowercase()
        val origin = item.explanation.origin.lowercase()
        return name.startsWith("ro.vendor.") ||
            name.startsWith("ro.odm.") ||
            name.startsWith("ro.boot.") ||
            listOf("samsung", "qualcomm", "mediatek", "xiaomi", "oplus", "google").any(origin::contains)
    }

    private fun isRuntimeDebug(name: String): Boolean {
        val normalized = name.lowercase()
        return listOf(
            "debug",
            "adb",
            "secure",
            "debuggable",
            "trace",
            "logging",
            "verifiedboot",
            "bootmode",
        ).any(normalized::contains)
    }
}
