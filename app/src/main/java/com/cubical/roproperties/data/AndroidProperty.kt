package com.cubical.roproperties.data

enum class PropertyRisk {
    NORMAL,
    CAUTION,
    HIGH,
    CRITICAL,
}

data class AndroidProperty(
    val name: String,
    val value: String,
    val description: String,
    val category: String,
    val risk: PropertyRisk,
    val persistentValue: String? = null,
)

data class RootCapability(
    val rootGranted: Boolean = false,
    val resetPropAvailable: Boolean = false,
    val implementation: String? = null,
    val detail: String? = null,
)

enum class ApplyMode {
    RUNTIME,
    PERSISTENT,
}

data class EditResult(
    val success: Boolean,
    val message: String,
)
