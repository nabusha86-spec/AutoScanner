package com.autoscanner.trader.analytics

import kotlin.math.sqrt

class IndicatorAnalysisEngine {
    fun analyze(
        names: List<String>,
        candles: List<DetailedCandle> = emptyList(),
    ): List<IndicatorResult> {
        val recognized = names.distinct().map { rawName ->
            val name = rawName.uppercase()
            val recognized = name.contains("RSI") || name.contains("MACD") ||
                name.contains("EMA") || name.contains("SMA") ||
                name.contains("СКОЛЬЗЯЩ") || name.contains("БОЛЛИНДЖЕР") ||
                name.contains("СТОХАСТ")
            val kind = when {
                name.contains("RSI") -> "RSI"
                name.contains("MACD") -> "MACD"
                name.contains("БОЛЛИНДЖЕР") -> "Bollinger Bands"
                name.contains("СТОХАСТ") -> "Stochastic"
                name.contains("EMA") -> "EMA"
                name.contains("SMA") -> "SMA"
                name.contains("СКОЛЬЗЯЩ") || name.contains("MA") -> "Moving Average"
                else -> rawName
            }
            IndicatorResult(
                name = kind,
                recognized = recognized,
                exactValue = null,
                visualApproximation = if (recognized) "Название индикатора распознано; числовое значение недоступно" else null,
                direction = DirectionInfluence.NEUTRAL,
                state = "НЕ ОПРЕДЕЛЕНО",
                confidence = if (recognized) 0.55 else 0.0,
            )
        }
        return recognized + calculateFromChartGeometry(candles)
    }

    private fun calculateFromChartGeometry(candles: List<DetailedCandle>): List<IndicatorResult> {
        val closes = candles.mapNotNull { it.close }
        if (closes.size < MIN_PERIOD) return emptyList()

        val results = mutableListOf<IndicatorResult>()
        val latest = closes.last()
        val sma = closes.takeLast(SHORT_PERIOD).average()
        results += calculated("SMA(5)", latest.compareTo(sma).toDirection(), 0.45)

        val ema = exponentialMovingAverage(closes, SHORT_PERIOD)
        if (ema != null) {
            results += calculated("EMA(5)", latest.compareTo(ema).toDirection(), 0.45)
        }

        if (closes.size >= RSI_PERIOD + 1) {
            val changes = closes.takeLast(RSI_PERIOD + 1).zipWithNext { a, b -> b - a }
            val gains = changes.filter { it > 0.0 }.sum() / RSI_PERIOD
            val losses = -changes.filter { it < 0.0 }.sum() / RSI_PERIOD
            val rsi = when {
                gains == 0.0 && losses == 0.0 -> 50.0
                losses == 0.0 -> 100.0
                else -> 100.0 - 100.0 / (1.0 + gains / losses)
            }
            val direction = when {
                rsi >= RSI_BULLISH_THRESHOLD -> DirectionInfluence.UP
                rsi <= RSI_BEARISH_THRESHOLD -> DirectionInfluence.DOWN
                else -> DirectionInfluence.NEUTRAL
            }
            results += calculated("RSI(14)", direction, 0.5)
        }

        if (closes.size >= MACD_SLOW_PERIOD) {
            val macdValues = (MACD_SLOW_PERIOD..closes.size).mapNotNull { end ->
                val prefix = closes.take(end)
                val fast = exponentialMovingAverage(prefix, MACD_FAST_PERIOD)
                val slow = exponentialMovingAverage(prefix, MACD_SLOW_PERIOD)
                if (fast != null && slow != null) fast - slow else null
            }
            val macd = macdValues.lastOrNull()
            val signal = exponentialMovingAverage(macdValues, MACD_SIGNAL_PERIOD)
            if (macd != null && signal != null) {
                results += calculated("MACD", macd.compareTo(signal).toDirection(), 0.5)
            }
        }

        if (closes.size >= BOLLINGER_PERIOD) {
            val window = closes.takeLast(BOLLINGER_PERIOD)
            val mean = window.average()
            val deviation = sqrt(window.sumOf { (it - mean) * (it - mean) } / window.size)
            val direction = when {
                latest > mean + BOLLINGER_DEVIATIONS * deviation -> DirectionInfluence.UP
                latest < mean - BOLLINGER_DEVIATIONS * deviation -> DirectionInfluence.DOWN
                latest > mean -> DirectionInfluence.UP
                latest < mean -> DirectionInfluence.DOWN
                else -> DirectionInfluence.NEUTRAL
            }
            results += calculated("Bollinger Bands(20)", direction, 0.4)
        }

        if (candles.size >= ATR_PERIOD) {
            results += calculated("ATR(14)", DirectionInfluence.NEUTRAL, 0.35)
        }
        return results
    }

    private fun exponentialMovingAverage(values: List<Double>, period: Int): Double? {
        if (values.size < period) return null
        val multiplier = 2.0 / (period + 1)
        var result = values.take(period).average()
        values.drop(period).forEach { value -> result += multiplier * (value - result) }
        return result
    }

    private fun calculated(
        name: String,
        direction: DirectionInfluence,
        confidence: Double,
    ) = IndicatorResult(
        name = "$name • геометрия графика",
        recognized = true,
        exactValue = null,
        visualApproximation = "расчёт по координатам распознанных свечей",
        direction = direction,
        state = direction.name,
        confidence = confidence,
    )

    private fun Int.toDirection(): DirectionInfluence = when {
        this > 0 -> DirectionInfluence.UP
        this < 0 -> DirectionInfluence.DOWN
        else -> DirectionInfluence.NEUTRAL
    }

    private companion object {
        const val SHORT_PERIOD = 5
        const val RSI_PERIOD = 14
        const val RSI_BULLISH_THRESHOLD = 55.0
        const val RSI_BEARISH_THRESHOLD = 45.0
        const val MACD_FAST_PERIOD = 12
        const val MACD_SLOW_PERIOD = 26
        const val MACD_SIGNAL_PERIOD = 9
        const val BOLLINGER_PERIOD = 20
        const val BOLLINGER_DEVIATIONS = 2.0
        const val ATR_PERIOD = 14
        const val MIN_PERIOD = SHORT_PERIOD
    }
}
