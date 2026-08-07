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
    fun excludesUnrelatedReadOnlyProperties() {
        assertFalse(PropertyCategoryClassifier.isSpoofingProperty("ro.hardware"))
        assertFalse(PropertyCategoryClassifier.isSpoofingProperty("ro.debuggable"))
        assertFalse(PropertyCategoryClassifier.isSpoofingProperty("ro.boot.verifiedbootstate"))
    }
}
