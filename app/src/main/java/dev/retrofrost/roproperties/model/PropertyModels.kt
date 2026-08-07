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

enum class EditMode(val label: String) {
    RUNTIME("Runtime"),
    PERSISTENT("Persistent"),
    BOTH("Runtime + persistent"),
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
