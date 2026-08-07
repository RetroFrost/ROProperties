package com.cubical.roproperties.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PropertyParserTest {
    @Test
    fun parsesGetPropAndPreservesSpaces() {
        val result = PropertyParser.parseGetProp(
            "[ro.product.model]: [Galaxy S10+]\n[ro.build.type]: [user]\ninvalid",
        )

        assertEquals("Galaxy S10+", result["ro.product.model"])
        assertEquals("user", result["ro.build.type"])
        assertEquals(2, result.size)
    }

    @Test
    fun parsesValuesContainingEquals() {
        val result = PropertyParser.parseSystemProp("ro.example=a=b=c\n# ignored\n")
        assertEquals("a=b=c", result["ro.example"])
    }

    @Test
    fun fallbackDescriptionIsNeverBlank() {
        assertFalse(PropertyDescriptionRegistry.description("ro.vendor.unknown.flag").isBlank())
    }
}
