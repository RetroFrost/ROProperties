package dev.retrofrost.roproperties.io

import dev.retrofrost.roproperties.model.ApplyStrategy
import dev.retrofrost.roproperties.model.PropertyPolicyClassifier

data class ImportProfileAnalysis(
    val warnings: List<String>,
    val nextBootCount: Int,
    val blockedCount: Int,
    val mixedIdentity: Boolean,
)

object ImportProfileAnalyzer {
    fun analyze(values: List<ImportedPropertyValue>): ImportProfileAnalysis {
        val models = values
            .filter { it.name.lowercase().matches(Regex("ro\\.product(?:\\.[^.]+)?\\.model")) }
            .map { it.value.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        val devices = values
            .filter { it.name.lowercase().matches(Regex("ro\\.product(?:\\.[^.]+)?\\.device")) }
            .map { it.value.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        val productNames = values
            .filter { it.name.lowercase().matches(Regex("ro\\.product(?:\\.[^.]+)?\\.name")) }
            .map { it.value.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

        val mixed = models.size > 1 || devices.size > 1 || productNames.size > 1
        val policies = values.map { PropertyPolicyClassifier.policyFor(it.name) }
        val blocked = policies.count { !it.canOverride }
        val nextBoot = policies.count { it.strategy == ApplyStrategy.NEXT_BOOT }
        val warnings = buildList {
            if (mixed) {
                add("Mixed identity detected: this profile contains multiple model/device/product identities across partitions. That can be normal for a port, but it is not a clean single-device spoof profile.")
            }
            if (nextBoot > 0) {
                add("$nextBoot properties are boot/cached-sensitive. Persistent + reboot is recommended; live getprop changes alone may not affect framework consumers.")
            }
            if (blocked > 0) {
                add("$blocked properties are marked read-only or dangerous and will be refused by ROProperties instead of being blindly overridden.")
            }
        }
        return ImportProfileAnalysis(warnings, nextBoot, blocked, mixed)
    }
}
