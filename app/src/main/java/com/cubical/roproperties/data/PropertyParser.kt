package com.cubical.roproperties.data

object PropertyParser {
    fun parseGetProp(output: String): Map<String, String> = buildMap {
        output.lineSequence().forEach { line ->
            if (!line.startsWith("[") || !line.endsWith("]")) return@forEach
            val separator = line.indexOf("]: [")
            if (separator <= 1) return@forEach
            val name = line.substring(1, separator)
            val valueStart = separator + 4
            val value = line.substring(valueStart, line.length - 1)
            put(name, value)
        }
    }

    fun parseSystemProp(output: String): LinkedHashMap<String, String> = linkedMapOf<String, String>().apply {
        output.lineSequence().forEach { raw ->
            val line = raw.trimEnd('\r')
            if (line.isBlank() || line.trimStart().startsWith("#")) return@forEach
            val separator = line.indexOf('=')
            if (separator <= 0) return@forEach
            put(line.substring(0, separator).trim(), line.substring(separator + 1))
        }
    }

    fun toSystemProp(values: Map<String, String>): String = values
        .toSortedMap()
        .entries
        .joinToString(separator = "\n", postfix = if (values.isEmpty()) "" else "\n") {
            "${it.key}=${it.value}"
        }
}
