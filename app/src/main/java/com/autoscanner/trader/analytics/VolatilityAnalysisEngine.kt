package com.autoscanner.trader.analytics

import kotlin.math.abs

class VolatilityAnalysisEngine {
    fun analyze(candles: List<DetailedCandle>): VolatilityResult {
        if (candles.size < 3) return VolatilityResult(VolatilityLevel.UNDEFINED, 0.0, null)
        val ranges = candles.mapNotNull { candle ->
            val high = candle.high
            val low = candle.low
            if (high != null && low != null) abs(high - low) else null
        }
        if (ranges.size < 3) return VolatilityResult(VolatilityLevel.UNDEFINED, 0.0, null)
        val average = ranges.average()
        val latest = ranges.takeLast(3).average()
        val ratio = if (average > 0.0) latest / average else null
        val closes = candles.mapNotNull { it.close }
        val relativeRange = if (closes.isNotEmpty()) {
            average / kotlin.math.abs(closes.average()).coerceAtLeast(0.0000001)
        } else null
        val level = when {
            ratio != null && ratio >= 1.8 -> VolatilityLevel.SHARP_INCREASE
            ratio != null && ratio <= 0.55 -> VolatilityLevel.SHARP_DECREASE
            relativeRange != null && relativeRange <= 0.05 -> VolatilityLevel.LOW
            ratio != null && ratio >= 1.35 -> VolatilityLevel.HIGH
            ratio != null && ratio <= 0.75 -> VolatilityLevel.LOW
            else -> VolatilityLevel.MODERATE
        }
        val confidence = (ranges.size.coerceAtMost(20) / 20.0 * 0.55 +
            if (ratio != null) 0.45 else 0.0).coerceIn(0.0, 1.0)
        return VolatilityResult(level, confidence, ratio)
    }
}
