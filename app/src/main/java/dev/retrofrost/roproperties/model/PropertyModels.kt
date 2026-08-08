package dev.retrofrost.roproperties.model

data class AndroidProperty(
    val name: String,
    val value: String,
)

enum class KnowledgeConfidence(val label: String) {
    DOCUMENTED("Documented"),
    KNOWN("Known"),
    INFERRED("Inferred"),
    UNKNOWN("Unknown"),
}

enum class RiskLevel(val label: String) {
    LOW("Low"),
    MEDIUM("Medium"),
    HIGH("High"),
    CRITICAL("Critical"),
}

enum class EditMode(
    val label: String,
    val description: String,
) {
    RUNTIME(
        label = "Runtime",
        description = "Applies the value now with resetprop. It normally lasts only until the next reboot, and apps or services that already cached the old value may not notice the change.",
    ),
    PERSISTENT(
        label = "Persistent",
        description = "Saves the value in ROProperties' root module so it is applied again on future boots. The currently running value may stay unchanged until you reboot.",
    ),
    BOTH(
        label = "Runtime + persistent",
        description = "Applies the value immediately and also saves it for future boots. Use this when you want the change now and after reboot.",
    ),
}

data class PropertyExplanation(
    val propertyMeaning: String,
    val valueMeaning: String,
    val knownValues: Map<String, String> = emptyMap(),
    val origin: String,
    val confidence: KnowledgeConfidence,
    val risk: RiskLevel,
    val consumers: String,
    val editBehaviour: String,
    val rebootRequirement: String,
)

data class PropertyUiItem(
    val property: AndroidProperty,
    val explanation: PropertyExplanation,
)

data class RootCapabilities(
    val rootAvailable: Boolean = false,
    val resetPropAvailable: Boolean = false,
    val modulePersistenceAvailable: Boolean = false,
    val framework: String = "None detected",
)

data class EditResult(
    val success: Boolean,
    val message: String,
    val runtimeApplied: Boolean = false,
    val persistentApplied: Boolean = false,
)
