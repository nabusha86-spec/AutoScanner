package com.autoscanner.trader.analytics

import kotlin.math.abs

class SupportResistanceEngine {
    fun analyze(candles: List<DetailedCandle>, currentPrice: Double?): SupportResistanceResult {
        if (candles.size < 3) return SupportResistanceResult(emptyList(), emptyList(), emptyList(), emptyList())
        val lows = candles.mapIndexedNotNull { i, candle ->
            val low = candle.low ?: return@mapIndexedNotNull null
            val left = candles.getOrNull(i - 1)?.low
            val right = candles.getOrNull(i + 1)?.low
            if (left != null && right != null && low <= left && low <= right)
                i to low else null
        }
        val highs = candles.mapIndexedNotNull { i, candle ->
            val high = candle.high ?: return@mapIndexedNotNull null
            val left = candles.getOrNull(i - 1)?.high
            val right = candles.getOrNull(i + 1)?.high
            if (left != null && right != null && high >= left && high >= right)
                i to high else null
        }
        val supports = cluster(lows, "ПОДДЕРЖКА", candles, currentPrice)
        val resistances = cluster(highs, "СОПРОТИВЛЕНИЕ", candles, currentPrice)
        val latest = candles.lastOrNull()
        val breaks = resistances.filter { zone ->
            val y = zone.approximateImageY
            latest?.close != null && y != null && latest.close > 0
        }.map { "Возможен пробой ранее обнаруженного сопротивления" }
        val falseBreaks = resistances.filter { zone ->
            val y = zone.approximateImageY
            latest?.close != null && y != null && abs(latest.close - y.toDouble()) < 3.0
        }.map { "Недалеко от уровня: возможен ложный тест" }
        return SupportResistanceResult(supports, resistances, breaks.distinct(), falseBreaks.distinct())
    }

    private fun cluster(
        points: List<Pair<Int, Double>>,
        kind: String,
        candles: List<DetailedCandle>,
        currentPrice: Double?,
    ): List<SupportResistanceZone> {
        if (points.isEmpty()) return emptyList()
        val tolerance = points.map { it.second }.let { values ->
            val spread = (values.maxOrNull() ?: 0.0) - (values.minOrNull() ?: 0.0)
            (spread / 10.0).coerceAtLeast(1.0)
        }
        val groups = mutableListOf<MutableList<Pair<Int, Double>>>()
        points.sortedBy { it.second }.forEach { point ->
            val group = groups.firstOrNull { existing ->
                abs(existing.map { it.second }.average() - point.second) <= tolerance
            }
            if (group == null) groups += mutableListOf(point) else group += point
        }
        return groups.mapNotNull { group ->
            val confirmations = group.size
            val average = group.map { it.second }.average()
            val imageY = candles[group.maxByOrNull { it.first }?.first ?: 0]
                .let { if (kind == "ПОДДЕРЖКА") it.low else it.high }
                ?.toInt()
            val strength = (confirmations.toDouble() / 3.0).coerceIn(0.0, 1.0)
            val confidence = (0.25 + confirmations * 0.18).coerceIn(0.0, 0.85)
            val detectedIndex = group.maxByOrNull { it.first }?.first
            val timestamp = detectedIndex?.let { candles.getOrNull(it)?.timestampMillis }
            SupportResistanceZone(
                kind, exactPrice = null, approximateImageY = imageY,
                confirmations, timestamp, strength,
                distanceFromCurrent = currentPrice?.let { abs(it - average) },
                confidence,
            )
        }
    }
}
