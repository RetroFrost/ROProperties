package dev.retrofrost.roproperties.io

import dev.retrofrost.roproperties.model.AndroidProperty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PropertyTextFormatTest {
    @Test
    fun exportedTextRoundTrips() {
        val source = listOf(
            AndroidProperty("ro.product.model", "SM-G975F"),
            AndroidProperty("ro.build.type", "user"),
        )

        val text = PropertyTextFormat.export(source)
        val parsed = PropertyTextFormat.parse(text)

        assertTrue(text.contains("ROProperties • property collection"))
        assertEquals(2, parsed.size)
        assertEquals("user", parsed.first { it.name == "ro.build.type" }.value)
        assertEquals("SM-G975F", parsed.first { it.name == "ro.product.model" }.value)
    }

    @Test
    fun parsesPlainAssignmentsAndGetpropDumps() {
        val text = """
            # comment
            ro.product.brand = samsung
            ro.product.model=SM-G975F
            [ro.build.type]: [user]
            ignored.property=value
        """.trimIndent()

        val parsed = PropertyTextFormat.parse(text)

        assertEquals(3, parsed.size)
        assertEquals("samsung", parsed.first { it.name == "ro.product.brand" }.value)
        assertEquals("SM-G975F", parsed.first { it.name == "ro.product.model" }.value)
        assertEquals("user", parsed.first { it.name == "ro.build.type" }.value)
    }

    @Test
    fun lastDuplicateWins() {
        val parsed = PropertyTextFormat.parse(
            "ro.product.model=old\nro.product.model=new"
        )

        assertEquals(1, parsed.size)
        assertEquals("new", parsed.single().value)
    }
}
