package dev.retrofrost.roproperties.io

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportProfileAnalyzerTest {
    @Test
    fun cleanSingleDeviceProfileIsNotMixed() {
        val analysis = ImportProfileAnalyzer.analyze(
            listOf(
                ImportedPropertyValue("ro.product.model", "SM-S901B"),
                ImportedPropertyValue("ro.product.vendor.model", "SM-S901B"),
                ImportedPropertyValue("ro.product.device", "r0s"),
                ImportedPropertyValue("ro.product.vendor.device", "r0s"),
            )
        )
        assertFalse(analysis.mixedIdentity)
        assertTrue(analysis.nextBootCount > 0)
    }

    @Test
    fun mixedPortIdentityIsFlagged() {
        val analysis = ImportProfileAnalyzer.analyze(
            listOf(
                ImportedPropertyValue("ro.product.model", "SM-S731B"),
                ImportedPropertyValue("ro.product.vendor.model", "SM-G975F"),
                ImportedPropertyValue("ro.product.device", "r13s"),
                ImportedPropertyValue("ro.product.vendor.device", "beyond2"),
            )
        )
        assertTrue(analysis.mixedIdentity)
        assertTrue(analysis.warnings.any { it.contains("Mixed identity") })
    }

    @Test
    fun dangerousPropertiesAreCountedAsBlocked() {
        val analysis = ImportProfileAnalyzer.analyze(
            listOf(ImportedPropertyValue("ro.zygote", "zygote64"))
        )
        assertTrue(analysis.blockedCount == 1)
    }
}
