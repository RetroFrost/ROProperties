package dev.retrofrost.roproperties.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PropertyCategoryClassifierTest {
    @Test
    fun recognisesCoreIdentityProperties() {
        assertTrue(PropertyCategoryClassifier.isSpoofingProperty("ro.build.fingerprint"))
        assertTrue(PropertyCategoryClassifier.isSpoofingProperty("ro.product.model"))
        assertTrue(PropertyCategoryClassifier.isSpoofingProperty("ro.product.manufacturer"))
        assertTrue(PropertyCategoryClassifier.isSpoofingProperty("ro.system.build.fingerprint"))
        assertTrue(PropertyCategoryClassifier.isSpoofingProperty("ro.vendor.build.version.security_patch"))
    }

    @Test
    fun recognisesPartitionQualifiedProductIdentity() {
        assertTrue(PropertyCategoryClassifier.isSpoofingProperty("ro.product.system.model"))
        assertTrue(PropertyCategoryClassifier.isSpoofingProperty("ro.product.vendor.brand"))
        assertTrue(PropertyCategoryClassifier.isSpoofingProperty("ro.product.product.device"))
        assertTrue(PropertyCategoryClassifier.isSpoofingProperty("ro.boot.hardware.sku"))
    }

    @Test
    fun excludesUnrelatedReadOnlyProperties() {
        assertFalse(PropertyCategoryClassifier.isSpoofingProperty("ro.hardware"))
        assertFalse(PropertyCategoryClassifier.isSpoofingProperty("ro.debuggable"))
        assertFalse(PropertyCategoryClassifier.isSpoofingProperty("ro.boot.verifiedbootstate"))
        assertFalse(PropertyCategoryClassifier.isSpoofingProperty("ro.product.cpu.abilist"))
    }
}
