package dev.retrofrost.roproperties.io

import dev.retrofrost.roproperties.model.AndroidProperty

data class ImportedPropertyValue(
    val name: String,
    val value: String,
)

object PropertyTextFormat {
    private val assignmentRegex = Regex("^\\s*(ro\\.[A-Za-z0-9_.-]+)\\s*=\\s*(.*)$")
    private val getPropRegex = Regex("^\\s*\\[(ro\\.[^]]+)]\\s*:\\s*\\[(.*)]\\s*$")

    fun export(properties: List<AndroidProperty>): String {
        val sorted = properties.sortedBy { it.name }
        return buildString {
            appendLine("╭────────────────────────────────────────╮")
            appendLine("│ ROProperties • property collection     │")
            appendLine("│ ${sorted.size.toString().padEnd(3)} selected properties              │")
            appendLine("╰────────────────────────────────────────╯")
            appendLine()
            appendLine("# You can edit the values below and import this file back into ROProperties.")
            appendLine("# The plain 'ro.name = value' line in every block is the round-trip format.")
            appendLine()

            sorted.forEachIndexed { index, property ->
                appendLine("${(index + 1).toString().padStart(2, '0')}  ${property.name}")
                appendLine("    ↳ ${property.value.ifEmpty { "(empty)" }}")
                appendLine("    ${property.name} = ${property.value}")
                if (index != sorted.lastIndex) appendLine()
            }
        }
    }

    fun parse(text: String): List<ImportedPropertyValue> {
        val values = linkedMapOf<String, String>()

        text.lineSequence().forEach { line ->
            val assignment = assignmentRegex.matchEntire(line)
            if (assignment != null) {
                values[assignment.groupValues[1]] = assignment.groupValues[2]
                return@forEach
            }

            val getProp = getPropRegex.matchEntire(line)
            if (getProp != null) {
                values[getProp.groupValues[1]] = getProp.groupValues[2]
            }
        }

        return values.map { (name, value) -> ImportedPropertyValue(name, value) }
    }
}
