package com.autoscanner.trader.analytics

import kotlin.math.abs

class PatternRecognitionEngine {
    fun recognize(candles: List<DetailedCandle>, timestampMillis: Long?): List<CandlePattern> {
        if (candles.isEmpty()) return emptyList()
        val found = mutableListOf<CandlePattern>()
        candles.forEachIndexed { i, c ->
            val range = (c.high ?: 0.0).let { high ->
                val low = c.low ?: 0.0
                abs(high - low).coerceAtLeast(1.0)
            }
            val body = c.bodySize.coerceAtLeast(1.0)
            val upperRatio = c.upperShadow / range
            val lowerRatio = c.lowerShadow / range
            val doji = body / range <= 0.12
            if (doji) found += pattern("Доджи", PatternDirection.NEUTRAL, c, timestampMillis,
                0.62, listOf("Тело составляет не более 12% диапазона"))

            if (body / range <= 0.35 && lowerRatio >= 0.55 && upperRatio <= 0.20) {
                found += pattern("Молот", PatternDirection.BULLISH, c, timestampMillis,
                    0.65, listOf("Малое тело", "длинная нижняя тень", "короткая верхняя тень"))
            }
            if (body / range <= 0.35 && upperRatio >= 0.55 && lowerRatio <= 0.20) {
                found += pattern("Перевёрнутый молот", PatternDirection.BULLISH, c, timestampMillis,
                    0.60, listOf("Малое тело", "длинная верхняя тень"))
            }
            if (body / range <= 0.35 && lowerRatio >= 0.45 && c.direction == CandleDirection.UP) {
                found += pattern("Повешенный", PatternDirection.BEARISH, c, timestampMillis,
                    0.58, listOf("Малое бычье тело", "длинная нижняя тень"))
            }
            if (body / range <= 0.35 && upperRatio >= 0.55 && c.direction == CandleDirection.DOWN) {
                found += pattern("Падающая звезда", PatternDirection.BEARISH, c, timestampMillis,
                    0.58, listOf("Малое медвежье тело", "длинная верхняя тень"))
            }
            if (i >= 1) {
                val prev = candles[i - 1]
                if (c.direction == CandleDirection.UP && prev.direction == CandleDirection.DOWN &&
                    (c.open ?: 0.0) <= (prev.close ?: 0.0) && (c.close ?: 0.0) >= (prev.open ?: 0.0)
                ) found += pattern("Бычье поглощение", PatternDirection.BULLISH, c, timestampMillis,
                    0.72, listOf("Предыдущая медвежья свеча", "последующая бычья свеча поглотила тело"))
                if (c.direction == CandleDirection.DOWN && prev.direction == CandleDirection.UP &&
                    (c.open ?: 0.0) >= (prev.close ?: 0.0) && (c.close ?: 0.0) <= (prev.open ?: 0.0)
                ) found += pattern("Медвежье поглощение", PatternDirection.BEARISH, c, timestampMillis,
                    0.72, listOf("Предыдущая бычья свеча", "последующая медвежья свеча поглотила тело"))

                val prevBody = prev.bodySize
                if (prevBody > 0.0 && c.bodySize >= prevBody * 1.6 &&
                    c.low == prev.low && c.high == prev.high
                ) found += pattern("Внешний бар", PatternDirection.NEUTRAL, c, timestampMillis,
                    0.64, listOf("Диапазон текущей свечи шире предыдущего"))
                if (c.bodySize < prevBody * 0.7 &&
                    (c.high ?: 0.0) <= (prev.high ?: 0.0) && (c.low ?: 0.0) >= (prev.low ?: 0.0)
                ) found += pattern("Внутренний бар", PatternDirection.NEUTRAL, c, timestampMillis,
                    0.64, listOf("Диапазон находится внутри диапазона предыдущей свечи"))
                if (c.lowerShadow / range >= 0.35 && c.upperShadow / range >= 0.35 &&
                    body / range <= 0.30
                ) found += pattern("Пин-бар", PatternDirection.NEUTRAL, c, timestampMillis,
                    0.66, listOf("Длинный отскок по одной из сторон", "малое тело"))
            }
            if (i >= 2) {
                val first = candles[i - 2]
                val second = candles[i - 1]
                if (first.direction == CandleDirection.DOWN &&
                    second.direction == CandleDirection.UP &&
                    c.direction == CandleDirection.DOWN &&
                    (c.close ?: 0.0) > (second.close ?: 0.0)
                ) found += pattern("Утренняя звезда", PatternDirection.BULLISH, c, timestampMillis,
                    0.74, listOf("Медвежья свеча", "небольшая средняя свеча", "замыкающая бычья свеча"))
                if (first.direction == CandleDirection.UP &&
                    second.direction == CandleDirection.DOWN &&
                    c.direction == CandleDirection.UP &&
                    (c.close ?: 0.0) < (second.close ?: 0.0)
                ) found += pattern("Вечерняя звезда", PatternDirection.BEARISH, c, timestampMillis,
                    0.74, listOf("Бычья свеча", "небольшая средняя свеча", "замыкающая медвежья свеча"))
            }
            if (i >= 2) {
                val three = candles.takeLast(3)
                if (three.all { it.direction == CandleDirection.UP } &&
                    three.zipWithNext().all { (a, b) -> (b.close ?: 0.0) > (a.close ?: 0.0) }
                ) found += pattern("Три белых солдата", PatternDirection.BULLISH, c, timestampMillis,
                    0.76, listOf("Три последовательные бычьи свечи", "каждое закрытие выше предыдущего"))
                if (three.all { it.direction == CandleDirection.DOWN } &&
                    three.zipWithNext().all { (a, b) -> (b.close ?: 0.0) < (a.close ?: 0.0) }
                ) found += pattern("Три чёрные вороны", PatternDirection.BEARISH, c, timestampMillis,
                    0.76, listOf("Три последовательные медвежьи свечи", "каждое закрытие ниже предыдущего"))
            }
        }
        return found.distinctBy { it.name + it.timestampMillis }
    }

    private fun pattern(
        name: String,
        direction: PatternDirection,
        candle: DetailedCandle,
        timestampMillis: Long?,
        confidence: Double,
        features: List<String>,
    ) = CandlePattern(name, direction, timestampMillis, direction, confidence, features)
}
