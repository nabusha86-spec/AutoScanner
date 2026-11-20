package com.autoscanner.trader.analytics

class MarketConditionAnalyzer {
    fun analyze(
        trend: TrendResult,
        momentum: MomentumResult,
        volatility: VolatilityResult,
        candleCount: Int,
        instrumentKnown: Boolean,
        imageAdequateRatio: Double,
        candlePatterns: List<CandlePattern>,
    ): MarketCondition {
        if (!instrumentKnown || candleCount < 5 || imageAdequateRatio < 0.5) return MarketCondition.UNDEFINED
        if (momentum.strength >= 0.75 && volatility.level in setOf(
                VolatilityLevel.HIGH, VolatilityLevel.SHARP_INCREASE
            )
        ) return MarketCondition.HIGH_VOLATILITY
        if (momentum.strength >= 0.65) return MarketCondition.IMPULSE
        if (candleCount >= 5 && trend.direction == TrendDirection.SIDEWAYS) {
            val ranges = candlePatterns.size
            return if (ranges > 0) MarketCondition.CONSOLIDATION else MarketCondition.SIDEWAYS
        }
        if (trend.direction == TrendDirection.POSSIBLE_REVERSAL) return MarketCondition.POSSIBLE_REVERSAL
        if (trend.direction == TrendDirection.UP || trend.direction == TrendDirection.DOWN) {
            return if (trend.strength >= 0.55) MarketCondition.STABLE_TREND else MarketCondition.WEAK_TREND
        }
        return MarketCondition.UNDEFINED
    }
}
