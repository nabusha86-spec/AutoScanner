package com.autoscanner.trader.analytics

import com.autoscanner.trader.vision.FrameAnalysis
import kotlin.math.max
import kotlin.math.min

class ExpirationSelector {
    fun select(
        direction: PredictionDirection,
        timeframe: String?,
        trend: TrendResult,
        momentum: MomentumResult,
        volatility: VolatilityResult,
        levels: SupportResistanceResult,
        candleCount: Int,
        conflict: Boolean,
        confidence: Double,
        frames: List<FrameAnalysis>,
    ): ExpirationRecommendation {
        if (direction == PredictionDirection.NO_TRADE || conflict || confidence < 0.68 ||
            candleCount < 5
        ) {
            return ExpirationRecommendation(
                seconds = null,
                reason = "Прогноз не подтверждён или содержит конфликтующие признаки",
                usedIndicators = mapOf(
                    "Направление" to direction.name,
                    "Конфликт" to conflict.toString(),
                    "Уверенность" to confidence.toString(),
                ),
                confidence = confidence,
                limitations = listOf("ЭКСПИРАЦИЯ НЕ ОПРЕДЕЛЕНА", "Дальнейшее исполнение заблокировано"),
            )
        }
        val timeframeSeconds = parseTimeframe(timeframe)
        var targetSeconds = 60
        val reasons = mutableListOf<String>()
        if (momentum.strength >= 0.75) {
            targetSeconds = 30
            reasons += "Сильный краткосрочный импульс"
        } else if (trend.confidence >= 0.68 && trend.strength >= 0.55) {
            targetSeconds = if (timeframeSeconds != null) min(timeframeSeconds, 120) else 120
            reasons += "Устойчивое подтверждённое движение"
        }
        if (volatility.level in setOf(
                VolatilityLevel.HIGH,
                VolatilityLevel.SHARP_INCREASE,
            )
        ) {
            targetSeconds = min(targetSeconds, 30)
            reasons += "Повышенная волатильность требует более короткого срока"
        }
        val nearestDistance = (levels.support + levels.resistance)
            .mapNotNull { it.distanceFromCurrent }
            .minOrNull()
        if (nearestDistance != null && nearestDistance < 0.002) {
            targetSeconds = min(targetSeconds, 30)
            reasons += "Цена близко к уровню поддержки или сопротивления"
        }
        if (timeframeSeconds != null && timeframeSeconds < targetSeconds) {
            targetSeconds = max(15, timeframeSeconds)
            reasons += "Срок ограничен таймфреймом графика"
        }
        val recommended = SUPPORTED_EXPIRATIONS
            .lastOrNull { it <= targetSeconds.coerceIn(15, 180) }
            ?: SUPPORTED_EXPIRATIONS.first()
        val used = mapOf(
            "Таймфрейм" to (timeframe ?: "НЕ ОПРЕДЕЛЕНО"),
            "Импульс" to momentum.strength.toString(),
            "Волатильность" to volatility.level.name,
            "Сила тренда" to trend.strength.toString(),
            "Ближайший уровень" to (nearestDistance?.toString() ?: "НЕ ОПРЕДЕЛЕНО"),
        )
        return ExpirationRecommendation(
            seconds = recommended,
            reason = reasons.distinct().joinToString("; ").ifBlank { "Баланс движения и рыночного риска" },
            usedIndicators = used,
            confidence = confidence,
            limitations = buildList {
                add("Экспирация ограничена диапазоном от 15 до 180 секунд")
                add("Выбор не гарантирует прибыль")
                val timer = frames.lastOrNull()?.timer
                add(
                    if (timer.isNullOrBlank() || timer == "НЕ ОПРЕДЕЛЕНО") {
                        "Таймер свечи не распознан"
                    } else {
                        "Тип распознанного таймера не подтверждён; он не использовался как время закрытия свечи"
                    },
                )
            },
        )
    }

    private fun parseTimeframe(timeframe: String?): Int? {
        if (timeframe.isNullOrBlank()) return null
        val match = Regex("""(?i)(M|H|D|S)?\s*(\d+)""").find(timeframe) ?: return null
        val unit = match.groupValues[1].uppercase()
        val amount = match.groupValues[2].toIntOrNull() ?: return null
        return when (unit) {
            "H" -> amount * 3600
            "D" -> amount * 86400
            "S" -> amount
            else -> amount * 60
        }
    }

    private companion object {
        val SUPPORTED_EXPIRATIONS = listOf(15, 30, 60, 120, 180)
    }
}
