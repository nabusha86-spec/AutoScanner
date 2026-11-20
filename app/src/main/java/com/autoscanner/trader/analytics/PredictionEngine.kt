package com.autoscanner.trader.analytics

import com.autoscanner.trader.vision.FrameAnalysis

class PredictionEngine(
    private val weights: PredictionWeights = PredictionWeights(),
    private val minimumConfidence: Double = 0.68,
) {
    private val confidenceEngine = SignalConfidenceEngine(minimumConfidence)

    fun predict(
        frames: List<FrameAnalysis>,
        instrument: String?,
        currentPrice: Double?,
        timeframe: String?,
        candleAnalysis: CandleAnalysisResult,
        trend: TrendResult,
        momentum: MomentumResult,
        volatility: VolatilityResult,
        levels: SupportResistanceResult,
        indicators: List<IndicatorResult>,
        marketCondition: MarketCondition,
    ): PredictionResult {
        val factors = mutableListOf<SignalFactor>()
        val trendInfluence = when (trend.direction) {
            TrendDirection.UP, TrendDirection.STRENGTHENING -> DirectionInfluence.UP
            TrendDirection.DOWN, TrendDirection.WEAKENING -> DirectionInfluence.DOWN
            else -> DirectionInfluence.NEUTRAL
        }
        factors += SignalFactor("Тренд", trend.direction.name, trendInfluence,
            weights.trend, trend.confidence, "Геометрия свечей")
        factors += SignalFactor("Импульс", momentum.direction.name, momentum.direction,
            weights.momentum, momentum.confidence, "Последовательность кадров и свечей")
        val bullishPatterns = candleAnalysis.patterns.count { it.direction == PatternDirection.BULLISH }
        val bearishPatterns = candleAnalysis.patterns.count { it.direction == PatternDirection.BEARISH }
        val patternInfluence = if (bullishPatterns > bearishPatterns) DirectionInfluence.UP
            else if (bearishPatterns > bullishPatterns) DirectionInfluence.DOWN
            else DirectionInfluence.NEUTRAL
        factors += SignalFactor("Свечные модели", "${bullishPatterns}/${bearishPatterns}",
            patternInfluence, weights.candles,
            if (candleAnalysis.patterns.isEmpty()) 0.2 else candleAnalysis.patterns.maxOf { it.confidence },
            "Распознавание геометрии свечей")

        val exactIndicators = indicators.filter { it.exactValue != null }
        val visualIndicators = indicators.filter { it.visualApproximation != null }
        val indicatorInfluence = if (exactIndicators.isNotEmpty()) {
            val directions = exactIndicators.map { it.direction }.distinct()
            if (directions.size == 1) directions.first() else DirectionInfluence.NEUTRAL
        } else DirectionInfluence.NEUTRAL
        factors += SignalFactor("Индикаторы",
            if (exactIndicators.isEmpty()) "Точных значений нет; визуально: ${visualIndicators.size}" else exactIndicators.joinToString { it.name },
            indicatorInfluence, weights.indicators,
            exactIndicators.firstOrNull()?.confidence ?: 0.15,
            "OCR и распознавание интерфейса")

        val nearestSupport = levels.support.minByOrNull { it.distanceFromCurrent ?: Double.MAX_VALUE }
        val nearestResistance = levels.resistance.minByOrNull { it.distanceFromCurrent ?: Double.MAX_VALUE }
        val levelInfluence = when {
            nearestSupport != null && (nearestSupport.distanceFromCurrent ?: Double.MAX_VALUE) <
                (nearestResistance?.distanceFromCurrent ?: Double.MAX_VALUE) -> DirectionInfluence.UP
            nearestResistance != null -> DirectionInfluence.DOWN
            else -> DirectionInfluence.NEUTRAL
        }
        factors += SignalFactor("Уровни", "П:${levels.support.size}; С:${levels.resistance.size}",
            levelInfluence, weights.levels,
            maxOf(nearestSupport?.confidence ?: 0.0, nearestResistance?.confidence ?: 0.0),
            "Локальные экстремумы")

        val marketInfluence = if (marketCondition == MarketCondition.STABLE_TREND) trendInfluence
            else if (marketCondition == MarketCondition.IMPULSE) momentum.direction
            else DirectionInfluence.NEUTRAL
        factors += SignalFactor("Рыночные условия", marketCondition.name, marketInfluence,
            weights.market,
            if (marketCondition == MarketCondition.UNDEFINED) 0.1 else trend.confidence,
            "Сводный анализ рынка")

        val confidence = confidenceEngine.weightedConfidence(factors)
        val conflict = confidenceEngine.conflicts(factors)
        val upScore = factors.filter { it.influence == DirectionInfluence.UP }
            .sumOf { it.weight * it.confidence }
        val downScore = factors.filter { it.influence == DirectionInfluence.DOWN }
            .sumOf { it.weight * it.confidence }
        val reasons = mutableListOf<String>()
        val risks = mutableListOf<RiskFactor>()
        val adequateFrames = frames.count { it.quality.isAdequate }
        val tradingScreenRecognized = frames.any { it.tradingScreenDetected }
        val pairKnown = instrument != null && instrument != "НЕ ОПРЕДЕЛЕНО"
        val enoughCandles = candleAnalysis.candles.size >= 5

        if (!tradingScreenRecognized) {
            risks += RiskFactor("Торговый интерфейс", "Торговый интерфейс не распознан")
        }
        if (!pairKnown) risks += RiskFactor("Валютная пара", "Валютная пара не распознана")
        if (!enoughCandles) risks += RiskFactor("История", "Недостаточно подтвержденных свечей")
        if (adequateFrames < frames.size / 2) risks += RiskFactor("Качество", "Большинство кадров недостаточного качества")
        if (conflict) risks += RiskFactor("Конфликт", "Аналитические факторы дают разные направления")
        if (volatility.level in setOf(VolatilityLevel.HIGH, VolatilityLevel.SHARP_INCREASE)) {
            risks += RiskFactor("Волатильность", "Повышенная или резко выросшая волатильность")
        }
        if (marketCondition == MarketCondition.UNDEFINED) {
            risks += RiskFactor("Рынок", "Рыночная ситуация не подтверждена")
        }

        val direction = if (!tradingScreenRecognized || !pairKnown || !enoughCandles || adequateFrames == 0 ||
            conflict || marketCondition == MarketCondition.UNDEFINED ||
            !confidenceEngine.meetsMinimum(confidence)
        ) {
            PredictionDirection.NO_TRADE
        } else if (upScore > downScore) {
            reasons += "Совокупная оценка факторов поддерживает восходящее движение"
            PredictionDirection.BUY
        } else if (downScore > upScore) {
            reasons += "Совокупная оценка факторов поддерживает нисходящее движение"
            PredictionDirection.SELL
        } else {
            PredictionDirection.NO_TRADE
        }
        if (direction == PredictionDirection.NO_TRADE) {
            reasons += "Достаточного согласованного подтверждения нет; сделка не рекомендуется"
        } else {
            reasons += trend.explanation
            if (momentum.measuredSamples > 0) reasons += "Импульс подтверждён по ${momentum.measuredSamples} измерениям"
        }

        val expiration = ExpirationSelector().select(
            direction = direction,
            timeframe = timeframe,
            trend = trend,
            momentum = momentum,
            volatility = volatility,
            levels = levels,
            candleCount = candleAnalysis.candles.size,
            conflict = conflict,
            confidence = confidence,
            frames = frames,
        )
        return PredictionResult(
            instrument = instrument,
            currentPrice = currentPrice,
            direction = direction,
            confidence = confidence,
            analysisTimeMillis = System.currentTimeMillis(),
            timeframe = timeframe,
            marketCondition = marketCondition,
            trendDirection = trend.direction,
            trendStrength = trend.strength,
            momentumStrength = momentum.strength,
            volatility = volatility.level,
            candlePatterns = candleAnalysis.patterns,
            indicators = indicators,
            supportResistance = levels,
            reasons = reasons,
            risks = risks,
            factors = factors,
            expiration = expiration,
        )
    }
}
