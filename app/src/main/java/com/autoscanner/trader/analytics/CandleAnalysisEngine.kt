package com.autoscanner.trader.analytics

import com.autoscanner.trader.vision.Candle
import com.autoscanner.trader.vision.FrameAnalysis
import kotlin.math.abs

class CandleAnalysisEngine {
    fun analyze(frames: List<FrameAnalysis>): CandleAnalysisResult {
        val latest = frames.lastOrNull { it.chart.detected && it.chart.candles.isNotEmpty() }
            ?: return CandleAnalysisResult(emptyList(), emptyList())
        val timestamp = latest.timestampMillis
        val previousCandles = frames.filter { it.chart.detected }
            .flatMap { it.chart.candles }
            .distinctBy { it.index }
        val geometry = mutableListOf<DetailedCandle>()
        latest.chart.candles.forEachIndexed { position, candle ->
            val bodyTop = minOf(candle.top, candle.bottom)
            val bodyBottom = maxOf(candle.top, candle.bottom)
            val body = (bodyBottom - bodyTop).coerceAtLeast(1).toDouble()
            val range = (candle.wickBottom - candle.wickTop).coerceAtLeast(body.toInt()).toDouble()
            val upper = abs(candle.wickTop - bodyTop).toDouble()
            val lower = abs(candle.wickBottom - bodyBottom).toDouble()
            val direction = when {
                candle.color.contains("ЗЕЛ", ignoreCase = true) -> CandleDirection.UP
                candle.color.contains("КРАС", ignoreCase = true) -> CandleDirection.DOWN
                else -> CandleDirection.DOJI
            }
            val close = if (direction == CandleDirection.UP) -bodyTop.toDouble()
            else if (direction == CandleDirection.DOWN) -bodyBottom.toDouble()
            else -(bodyTop + bodyBottom).toDouble() / 2.0
            val open = if (direction == CandleDirection.UP) -bodyBottom.toDouble()
            else if (direction == CandleDirection.DOWN) -bodyTop.toDouble()
            else close
            val high = -candle.wickTop.toDouble()
            val low = -candle.wickBottom.toDouble()
            val previous = geometry.lastOrNull()
            val change = previous?.close?.let { close - it }
            val color = when (direction) {
                CandleDirection.UP -> CandleColor.GREEN
                CandleDirection.DOWN -> CandleColor.RED
                CandleDirection.DOJI -> CandleColor.UNKNOWN
            }
            geometry += DetailedCandle(
                index = candle.index,
                timestampMillis = if (position == latest.chart.candles.lastIndex) timestamp else null,
                open = open,
                close = close,
                high = high,
                low = low,
                bodySize = body,
                upperShadow = upper,
                lowerShadow = lower,
                color = color,
                direction = direction,
                relativeStrength = (body / range).coerceIn(0.0, 1.0),
                changeFromPrevious = change,
            )
        }
        val patterns = PatternRecognitionEngine().recognize(geometry, timestamp)
        return CandleAnalysisResult(geometry, patterns)
    }
}
