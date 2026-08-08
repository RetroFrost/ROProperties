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
        label = "Live override",
        description = "Changes Android's property-service value now with resetprop. Running framework/app processes can keep values they cached earlier, so this is not the same as changing the device identity everywhere.",
    ),
    PERSISTENT(
        label = "Persistent / next boot",
        description = "Saves the override in ROProperties' root module so it is applied during future boots. This is the preferred mode for cached build/device identity properties.",
    ),
    BOTH(
        label = "Live + persistent",
        description = "Writes the live property-service override and also saves it for next boot. Cached identity can still require a reboot before Android/framework consumers agree.",
    ),
}

enum class PropertyEditability(val label: String) {
    READ_ONLY("Read-only"),
    SPOOFABLE("Spoofable"),
    BOOT_TIME("Boot-time"),
    ADVANCED("Advanced"),
    DANGEROUS("Dangerous"),
}

enum class ApplyStrategy(val label: String) {
    LIVE("Live override"),
    RESTART_PROCESS("Restart-sensitive"),
    NEXT_BOOT("Next boot"),
    DO_NOT_MODIFY("Do not modify"),
}

data class PropertyPolicy(
    val editability: PropertyEditability,
    val strategy: ApplyStrategy,
    val canOverride: Boolean,
    val cachedSensitive: Boolean,
    val descriptiveOnly: Boolean,
    val warning: String,
)

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
    val policy: PropertyPolicy,
    val frameworkValue: String? = null,
    val persistentOverride: String? = null,
)

data class RootCapabilities(
    val rootAvailable: Boolean = false,
    val resetPropAvailable: Boolean = false,
    val modulePersistenceAvailable: Boolean = false,
    val framework: String = "None detected",
)

data class PropertyVerification(
    val requestedValue: String,
    val propertyServiceValue: String,
    val frameworkValue: String? = null,
) {
    val propertyServiceMatches: Boolean get() = propertyServiceValue == requestedValue
    val frameworkMapped: Boolean get() = frameworkValue != null
    val frameworkMatches: Boolean get() = frameworkValue == requestedValue
}

data class EditResult(
    val success: Boolean,
    val message: String,
    val runtimeApplied: Boolean = false,
    val persistentApplied: Boolean = false,
    val rebootRecommended: Boolean = false,
    val verification: PropertyVerification? = null,
)
