package com.autoscanner.trader.analytics

import kotlin.math.abs

class SignalConfidenceEngine(
    private val minimumConfidence: Double = 0.68,
) {
    fun weightedConfidence(factors: List<SignalFactor>): Double {
        if (factors.isEmpty()) return 0.0
        var directionalWeight = 0.0
        var weightedAgreement = 0.0
        val directions = factors.filter { it.influence != DirectionInfluence.NEUTRAL }
            .map { it.influence }.distinct()
        if (directions.size > 1) {
            factors.forEach { factor ->
                weightedAgreement += factor.weight * factor.confidence *
                    if (factor.influence == DirectionInfluence.NEUTRAL) 0.5 else 0.35
                directionalWeight += factor.weight
            }
        } else {
            factors.forEach { factor ->
                weightedAgreement += factor.weight * factor.confidence *
                    if (factor.influence == DirectionInfluence.NEUTRAL) 0.25 else 1.0
                directionalWeight += factor.weight
            }
        }
        return if (directionalWeight > 0.0)
            (weightedAgreement / directionalWeight).coerceIn(0.0, 1.0) else 0.0
    }

    fun conflicts(factors: List<SignalFactor>): Boolean =
        factors.filter { it.influence != DirectionInfluence.NEUTRAL }
            .map { it.influence }.distinct().size > 1

    fun meetsMinimum(confidence: Double): Boolean = confidence >= minimumConfidence
}
