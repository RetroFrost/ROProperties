package dev.retrofrost.roproperties.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PropertyPolicyClassifierTest {
    @Test
    fun identityPropertiesPreferNextBoot() {
        val policy = PropertyPolicyClassifier.policyFor("ro.product.model")
        assertEquals(PropertyEditability.SPOOFABLE, policy.editability)
        assertEquals(ApplyStrategy.NEXT_BOOT, policy.strategy)
        assertTrue(policy.canOverride)
        assertTrue(policy.cachedSensitive)
        assertTrue(policy.descriptiveOnly)
    }

    @Test
    fun verifiedBootStateIsInformationOnly() {
        val policy = PropertyPolicyClassifier.policyFor("ro.boot.verifiedbootstate")
        assertEquals(PropertyEditability.READ_ONLY, policy.editability)
        assertEquals(ApplyStrategy.DO_NOT_MODIFY, policy.strategy)
        assertFalse(policy.canOverride)
    }

    @Test
    fun coreRuntimeArchitectureIsBlocked() {
        val policy = PropertyPolicyClassifier.policyFor("ro.zygote")
        assertEquals(PropertyEditability.DANGEROUS, policy.editability)
        assertFalse(policy.canOverride)
    }

    @Test
    fun apiCompatibilityMetadataIsNotTreatedAsHarmlessSpoofing() {
        assertFalse(PropertyPolicyClassifier.policyFor("ro.product.first_api_level").canOverride)
        assertFalse(PropertyPolicyClassifier.policyFor("ro.build.version.sdk").canOverride)
    }

    @Test
    fun vendorPropertiesDefaultToBootTime() {
        val policy = PropertyPolicyClassifier.policyFor("ro.vendor.some.feature")
        assertEquals(PropertyEditability.BOOT_TIME, policy.editability)
        assertEquals(ApplyStrategy.NEXT_BOOT, policy.strategy)
    }
}
