package com.cubical.roproperties.data

object PropertyDescriptionRegistry {
    private val exact = mapOf(
        "ro.build.fingerprint" to "Unique build identity used by Android, Play services, update checks, and compatibility systems.",
        "ro.build.id" to "Human-readable identifier assigned to this Android build.",
        "ro.build.display.id" to "Build identifier shown to users in system information screens.",
        "ro.build.version.release" to "Public Android release version reported by the build.",
        "ro.build.version.release_or_codename" to "Android release number, or its codename for a preview build.",
        "ro.build.version.sdk" to "Android framework API level exposed by the current build.",
        "ro.build.version.preview_sdk" to "Preview API level increment; zero on a final SDK release.",
        "ro.build.version.codename" to "Platform codename; REL indicates a final public release.",
        "ro.build.version.incremental" to "Build system's incremental version identifier.",
        "ro.build.version.security_patch" to "Android security patch level claimed by the operating system.",
        "ro.build.version.base_os" to "Base operating-system build from which this build was derived.",
        "ro.build.version.min_supported_target_sdk" to "Oldest app target SDK the system permits without compatibility overrides.",
        "ro.build.date" to "Human-readable timestamp at which this system build was produced.",
        "ro.build.date.utc" to "Build creation time expressed as Unix seconds in UTC.",
        "ro.build.type" to "Build type, commonly user, userdebug, or eng.",
        "ro.build.tags" to "Signing/build tags such as release-keys or test-keys.",
        "ro.build.flavor" to "Combined build product and variant name.",
        "ro.build.characteristics" to "Device form-factor characteristics such as phone, tablet, watch, or automotive.",
        "ro.product.brand" to "Consumer brand associated with the product.",
        "ro.product.manufacturer" to "Hardware manufacturer reported by the product configuration.",
        "ro.product.model" to "Market-facing device model name reported to Android apps.",
        "ro.product.name" to "Internal Android product name selected at build time.",
        "ro.product.device" to "Android device codename used by the build and hardware configuration.",
        "ro.product.board" to "Board or platform identifier declared by the product.",
        "ro.product.cpu.abi" to "Primary application binary interface used to choose native libraries.",
        "ro.product.cpu.abilist" to "Ordered list of all application binary interfaces supported by the device.",
        "ro.product.cpu.abilist32" to "Ordered list of supported 32-bit application binary interfaces.",
        "ro.product.cpu.abilist64" to "Ordered list of supported 64-bit application binary interfaces.",
        "ro.hardware" to "Primary hardware/platform name used when loading hardware abstraction modules.",
        "ro.hardware.egl" to "EGL graphics hardware implementation selected by the system.",
        "ro.hardware.vulkan" to "Vulkan hardware implementation selected by the system.",
        "ro.bootloader" to "Bootloader version reported to Android.",
        "ro.baseband" to "Baseband or modem version family reported by the device.",
        "ro.serialno" to "Device serial number exposed by the Android property service.",
        "ro.boot.serialno" to "Device serial number supplied by the bootloader.",
        "ro.boot.hardware" to "Hardware name supplied on the kernel command line by the bootloader.",
        "ro.boot.slot_suffix" to "Suffix of the currently booted A/B slot, normally _a or _b.",
        "ro.boot.slot" to "Currently booted A/B slot when exposed without a suffix.",
        "ro.boot.verifiedbootstate" to "Verified Boot result, commonly green, yellow, orange, or red.",
        "ro.boot.flash.locked" to "Whether the bootloader reports flash partitions as locked.",
        "ro.boot.vbmeta.device_state" to "Bootloader lock state recorded in Android Verified Boot metadata.",
        "ro.boot.veritymode" to "dm-verity enforcement mode selected during boot.",
        "ro.boot.warranty_bit" to "OEM warranty or tamper state passed from the bootloader.",
        "ro.debuggable" to "Whether Android permits debugging features intended for debug builds.",
        "ro.secure" to "Core Android security mode; production builds normally use 1.",
        "ro.adb.secure" to "Whether ADB requires host-key authentication.",
        "ro.kernel.qemu" to "Indicates whether Android believes it is running in an emulator.",
        "ro.treble.enabled" to "Whether the build declares Project Treble support.",
        "ro.vndk.version" to "Vendor Native Development Kit compatibility version used by the system.",
        "ro.vendor.build.security_patch" to "Security patch level declared by the vendor partition.",
        "ro.vendor.api_level" to "API level targeted by the vendor implementation.",
        "ro.board.first_api_level" to "API level with which this hardware platform originally launched.",
        "ro.product.first_api_level" to "API level with which this product originally launched.",
        "ro.config.low_ram" to "Enables Android behaviour optimized for devices with limited memory.",
        "ro.config.ringtone" to "Default incoming-call ringtone configured by the product.",
        "ro.config.notification_sound" to "Default notification sound configured by the product.",
        "ro.config.alarm_alert" to "Default alarm sound configured by the product.",
        "ro.sf.lcd_density" to "Default logical display density used for UI scaling.",
        "ro.opengles.version" to "Highest OpenGL ES version declared by the device, encoded as an integer.",
        "ro.crypto.state" to "Current high-level data encryption state reported by vold.",
        "ro.crypto.type" to "Encryption scheme in use, such as file or block encryption.",
        "ro.crypto.volume.filenames_mode" to "Filename encryption mode used for encrypted volumes.",
        "ro.setupwizard.mode" to "Controls whether and how the initial device setup wizard runs.",
        "ro.zygote" to "Zygote process configuration, including 32-bit and 64-bit ordering.",
        "ro.control_privapp_permissions" to "Enforcement mode for privileged-app permission allowlists.",
        "ro.apex.updatable" to "Whether updateable APEX system modules are enabled.",
        "ro.gsid.image_running" to "Whether the currently running system is a Dynamic System Update image.",
    )

    private val prefixDescriptions = listOf(
        "ro.boot." to "Read-only value supplied during boot by the bootloader or kernel command line.",
        "ro.build.version." to "Android platform version metadata baked into this build.",
        "ro.build." to "Build identity or build-system metadata baked into the system image.",
        "ro.product." to "Product identity or hardware capability declared by an Android partition.",
        "ro.system_ext." to "Read-only metadata belonging to the system_ext partition.",
        "ro.system." to "Read-only metadata belonging to the system partition.",
        "ro.vendor." to "Read-only metadata or feature configuration supplied by the vendor partition.",
        "ro.odm." to "Read-only metadata or configuration supplied by the ODM partition.",
        "ro.product_services." to "Metadata from the legacy product-services partition.",
        "ro.soc." to "System-on-chip identity or capability reported by the device.",
        "ro.telephony." to "Read-only telephony or radio configuration selected by the product.",
        "ro.bluetooth." to "Read-only Bluetooth stack or hardware configuration.",
        "ro.camera." to "Read-only camera framework or vendor feature configuration.",
        "ro.audio." to "Read-only audio framework or hardware configuration.",
        "ro.surface_flinger." to "SurfaceFlinger display-compositor configuration fixed at boot.",
        "ro.hwui." to "Hardware-accelerated Android UI renderer configuration.",
        "ro.graphics." to "Read-only graphics stack or driver configuration.",
        "ro.crypto." to "Read-only storage encryption state or policy.",
        "ro.config." to "Product-level default or feature switch loaded from the system image.",
        "ro.com.google." to "Google Mobile Services product configuration.",
        "ro.setupwizard." to "Initial device setup wizard configuration.",
        "ro.security." to "Read-only platform or OEM security configuration.",
        "ro.debug." to "Debugging behaviour selected for this build.",
        "ro." to "OEM- or component-specific read-only Android system property.",
    )

    fun description(name: String): String = exact[name]
        ?: prefixDescriptions.firstOrNull { name.startsWith(it.first) }?.second
        ?: "Read-only Android system property discovered on this device."

    fun category(name: String): String = when {
        name.startsWith("ro.boot.") || name == "ro.bootloader" -> "Boot"
        name.startsWith("ro.build.") -> "Build"
        name.startsWith("ro.product.") || name.startsWith("ro.soc.") -> "Device"
        name.startsWith("ro.vendor.") || name.startsWith("ro.odm.") -> "Vendor"
        name.startsWith("ro.system.") || name.startsWith("ro.system_ext.") -> "Partitions"
        name.startsWith("ro.telephony.") || name == "ro.baseband" -> "Radio"
        name.startsWith("ro.crypto.") || name.startsWith("ro.security.") -> "Security"
        name.startsWith("ro.surface_flinger.") || name.startsWith("ro.hwui.") || name.startsWith("ro.graphics.") -> "Graphics"
        name.startsWith("ro.config.") || name.startsWith("ro.setupwizard.") -> "Configuration"
        else -> "Other"
    }

    fun risk(name: String): PropertyRisk = when {
        name in setOf(
            "ro.product.cpu.abi",
            "ro.product.cpu.abilist",
            "ro.product.cpu.abilist32",
            "ro.product.cpu.abilist64",
            "ro.zygote",
            "ro.hardware",
            "ro.board.first_api_level",
            "ro.product.first_api_level",
        ) -> PropertyRisk.CRITICAL
        name.contains("verifiedboot") ||
            name.contains("verity") ||
            name.contains("flash.locked") ||
            name.contains("device_state") ||
            name == "ro.secure" ||
            name == "ro.debuggable" ||
            name == "ro.build.version.sdk" -> PropertyRisk.HIGH
        name.contains("fingerprint") ||
            name.contains("security_patch") ||
            name.contains("serialno") ||
            name.startsWith("ro.product.") ||
            name == "ro.build.type" ||
            name == "ro.build.tags" -> PropertyRisk.CAUTION
        else -> PropertyRisk.NORMAL
    }
}
