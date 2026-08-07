package dev.retrofrost.roproperties.knowledge

import dev.retrofrost.roproperties.model.AndroidProperty
import dev.retrofrost.roproperties.model.KnowledgeConfidence
import dev.retrofrost.roproperties.model.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PropertyKnowledgeEngineTest {
    @Test
    fun sdkPropertyExplainsTheCurrentValue() {
        val explanation = PropertyKnowledgeEngine.explain(AndroidProperty("ro.build.version.sdk", "36"))

        assertEquals(KnowledgeConfidence.DOCUMENTED, explanation.confidence)
        assertEquals(RiskLevel.CRITICAL, explanation.risk)
        assertTrue(explanation.valueMeaning.contains("API level 36"))
    }

    @Test
    fun unknownVendorPropertyGetsHonestInference() {
        val explanation = PropertyKnowledgeEngine.explain(AndroidProperty("ro.vendor.camera.feature_mode", "2"))

        assertEquals(KnowledgeConfidence.INFERRED, explanation.confidence)
        assertTrue(explanation.origin.contains("Vendor"))
        assertTrue(explanation.propertyMeaning.contains("inferred", ignoreCase = true))
        assertTrue(explanation.valueMeaning.contains("Numeric value 2"))
    }

    @Test
    fun booleanLookingUnknownPropertyGetsValueMapping() {
        val explanation = PropertyKnowledgeEngine.explain(AndroidProperty("ro.vendor.example.feature_enabled", "1"))

        assertEquals(KnowledgeConfidence.INFERRED, explanation.confidence)
        assertTrue(explanation.valueMeaning.contains("Enabled"))
    }
}
