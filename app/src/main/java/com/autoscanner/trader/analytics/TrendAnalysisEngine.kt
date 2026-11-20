package com.autoscanner.trader.analytics

class TrendAnalysisEngine {
    fun analyze(candles: List<DetailedCandle>): TrendResult {
        if (candles.size < 5) {
            return TrendResult(TrendDirection.UNCONFIRMED, 0.0, 0.0, candles.size,
                "Недостаточно свечей для подтверждённого тренда")
        }
        val closes = candles.mapNotNull { it.close }
        if (closes.size < 5) {
            return TrendResult(TrendDirection.UNCONFIRMED, 0.0, 0.0, candles.size,
                "Нет достаточно геометрических данных свечей")
        }
        val highs = candles.mapNotNull { it.high }
        val lows = candles.mapNotNull { it.low }
        val risingHighs = if (highs.size >= 5) highs.zipWithNext().count { it.second > it.first } else 0
        val risingLows = if (lows.size >= 5) lows.zipWithNext().count { it.second > it.first } else 0
        val fallingHighs = if (highs.size >= 5) highs.zipWithNext().count { it.second < it.first } else 0
        val fallingLows = if (lows.size >= 5) lows.zipWithNext().count { it.second < it.first } else 0
        val first = closes.first()
        val last = closes.last()
        val spread = closes.maxOrNull()?.minus(closes.minOrNull() ?: 0.0) ?: 0.0
        val moveRatio = if (spread > 0.0) kotlin.math.abs(last - first) / spread else 0.0
        val upRatio = (risingHighs + risingLows).toDouble() / ((highs.size - 1 + lows.size - 1).coerceAtLeast(1))
        val downRatio = (fallingHighs + fallingLows).toDouble() / ((highs.size - 1 + lows.size - 1).coerceAtLeast(1))
        val recent = closes.takeLast(3)
        val reversal = if (recent.size == 3) {
            (recent[0] > recent[1] && recent[1] < recent[2]) ||
                (recent[0] < recent[1] && recent[1] > recent[2])
        } else false
        val direction = when {
            moveRatio < 0.35 && kotlin.math.abs(upRatio - downRatio) <= 0.22 ->
                TrendDirection.SIDEWAYS
            reversal && moveRatio < 0.35 -> TrendDirection.POSSIBLE_REVERSAL
            upRatio >= 0.58 && last >= first -> TrendDirection.UP
            downRatio >= 0.58 && last <= first -> TrendDirection.DOWN
            kotlin.math.abs(upRatio - downRatio) <= 0.18 -> TrendDirection.SIDEWAYS
            else -> TrendDirection.UNCONFIRMED
        }
        val strength = when (direction) {
            TrendDirection.UP, TrendDirection.DOWN -> (moveRatio * 0.7 + maxOf(upRatio, downRatio) * 0.3).coerceIn(0.0, 1.0)
            else -> moveRatio * 0.25
        }
        val confidence = when (direction) {
            TrendDirection.UP, TrendDirection.DOWN ->
                ((candles.size.coerceAtMost(20) / 20.0) * 0.45 + strength * 0.55).coerceIn(0.0, 1.0)
            TrendDirection.SIDEWAYS -> 0.55
            TrendDirection.POSSIBLE_REVERSAL -> 0.35
            else -> 0.15
        }
        val explanation = when (direction) {
            TrendDirection.UP -> "Последовательность минимумов и максимумов подтверждает восходящее движение"
            TrendDirection.DOWN -> "Последовательность минимумов и максимумов подтверждает нисходящее движение"
            TrendDirection.SIDEWAYS -> "Максимумы и минимумы не имеют устойчивого направленного движения"
            TrendDirection.POSSIBLE_REVERSAL -> "Последние движения меняют структуру и могут указывать на разворот"
            else -> "Недостаточно согласованных признаков тренда"
        }
        return TrendResult(direction, strength, confidence, candles.size, explanation)
    }
}
