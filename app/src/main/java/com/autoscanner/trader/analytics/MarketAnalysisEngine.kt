package com.autoscanner.trader.analytics

import com.autoscanner.trader.vision.FrameAnalysis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MarketAnalysisEngine(
    private val weights: PredictionWeights = PredictionWeights(),
    minimumConfidence: Double = 0.68,
) {
    private val candleEngine = CandleAnalysisEngine()
    private val trendEngine = TrendAnalysisEngine()
    private val momentumEngine = MomentumAnalysisEngine()
    private val volatilityEngine = VolatilityAnalysisEngine()
    private val supportResistanceEngine = SupportResistanceEngine()
    private val indicatorEngine = IndicatorAnalysisEngine()
    private val marketConditionAnalyzer = MarketConditionAnalyzer()
    private val predictionEngine = PredictionEngine(weights, minimumConfidence)

    suspend fun analyze(
        frames: List<FrameAnalysis>,
        instrument: String?,
        price: String?,
        timeframe: String?,
    ): PredictionResult = withContext(Dispatchers.Default) {
        val volatilityWeight = minOf(VOLATILITY_WEIGHT, weights.market.coerceAtLeast(0.0))
        val candleRun = safely("Свечной анализ", CandleAnalysisResult(emptyList(), emptyList())) {
            candleEngine.analyze(frames)
        }
        val candles = candleRun.value.candles
        val trendRun = safely(
            "Тренд",
            TrendResult(TrendDirection.UNCONFIRMED, 0.0, 0.0, candles.size, "Расчёт тренда недоступен"),
        ) { trendEngine.analyze(candles) }
        val trend = trendRun.value
        val momentumRun = safely(
            "Импульс",
            MomentumResult(DirectionInfluence.NEUTRAL, 0.0, 0.0, null, null, false, 0),
        ) { momentumEngine.analyze(frames, candles) }
        val momentum = momentumRun.value
        val volatilityRun = safely(
            "Волатильность",
            VolatilityResult(VolatilityLevel.UNDEFINED, 0.0, null),
        ) { volatilityEngine.analyze(candles) }
        val volatility = volatilityRun.value
        val currentPrice = price?.replace(',', '.')?.toDoubleOrNull()
        val levelsRun = safely(
            "Поддержка и сопротивление",
            SupportResistanceResult(emptyList(), emptyList(), emptyList(), emptyList()),
        ) { supportResistanceEngine.analyze(candles, currentPrice) }
        val levels = levelsRun.value
        val indicatorsRun = safely("Индикаторы", emptyList()) {
            indicatorEngine.analyze(frames.flatMap { it.indicators }, candles)
        }
        val indicators = indicatorsRun.value
        val adequateCount = frames.count { it.quality.isAdequate }.toDouble()
        val adequateRatio = if (frames.isNotEmpty()) adequateCount / frames.size else 0.0
        val instrumentKnown = instrument != null && instrument != "НЕ ОПРЕДЕЛЕНО"
        val conditionRun = safely("Рыночные условия", MarketCondition.UNDEFINED) {
            marketConditionAnalyzer.analyze(
                trend,
                momentum,
                volatility,
                candles.size,
                instrumentKnown,
                adequateRatio,
                candleRun.value.patterns,
            )
        }

        val assessments = buildList {
            val candleDirection = candleDirection(candleRun.value)
            add(
                assessment(
                    "Свечные модели",
                    candleDirection,
                    candleConfidence(candleRun.value),
                    weights.candles / 2.0,
                    candleRun.failureReason,
                    candles.size >= MIN_CANDLES,
                    "Недостаточно распознанных свечей",
                ),
            )
            val priceActionDirection = priceActionDirection(candles)
            add(
                assessment(
                    "Price Action",
                    priceActionDirection,
                    priceActionConfidence(candles),
                    weights.candles / 2.0,
                    candleRun.failureReason,
                    candles.size >= MIN_CANDLES,
                    "Недостаточно геометрии графика",
                ),
            )
            add(
                assessment(
                    "Тренд",
                    trend.direction.toInfluence(),
                    trend.confidence,
                    weights.trend,
                    trendRun.failureReason,
                    candles.size >= MIN_CANDLES && trend.direction != TrendDirection.UNCONFIRMED,
                    "Недостаточно свечей для определения тренда",
                ),
            )
            add(
                assessment(
                    "Импульс",
                    momentum.direction,
                    momentum.confidence,
                    weights.momentum,
                    momentumRun.failureReason,
                    momentum.measuredSamples >= MIN_MOMENTUM_SAMPLES,
                    "Недостаточно измерений движения",
                ),
            )
            add(
                assessment(
                    "Волатильность",
                    DirectionInfluence.NEUTRAL,
                    volatility.confidence,
                    volatilityWeight,
                    volatilityRun.failureReason,
                    volatility.level != VolatilityLevel.UNDEFINED,
                    "Недостаточно диапазонов свечей",
                ),
            )
            val nearestLevelConfidence = (levels.support + levels.resistance)
                .maxOfOrNull { it.confidence } ?: 0.0
            add(
                assessment(
                    "Поддержка / сопротивление",
                    DirectionInfluence.NEUTRAL,
                    nearestLevelConfidence,
                    weights.levels,
                    levelsRun.failureReason,
                    candles.size >= MIN_CANDLES,
                    "Недостаточно данных для уровней",
                ),
            )
            val calculatedIndicators = indicators.filter {
                it.visualApproximation?.contains(GEOMETRY_INDICATOR_SOURCE) == true
            }
            val indicatorUp = calculatedIndicators.filter { it.direction == DirectionInfluence.UP }
            val indicatorDown = calculatedIndicators.filter { it.direction == DirectionInfluence.DOWN }
            val indicatorDirection = when {
                indicatorUp.sumOf { it.confidence } > indicatorDown.sumOf { it.confidence } ->
                    DirectionInfluence.UP
                indicatorDown.sumOf { it.confidence } > indicatorUp.sumOf { it.confidence } ->
                    DirectionInfluence.DOWN
                else -> DirectionInfluence.NEUTRAL
            }
            add(
                assessment(
                    "Технические индикаторы",
                    indicatorDirection,
                    calculatedIndicators.maxOfOrNull { it.confidence } ?: 0.0,
                    weights.indicators,
                    indicatorsRun.failureReason,
                    calculatedIndicators.isNotEmpty(),
                    "Для расчёта не хватает распознанных свечей",
                ),
            )
            val conditionDirection = when (conditionRun.value) {
                MarketCondition.STABLE_TREND, MarketCondition.WEAK_TREND -> trend.direction.toInfluence()
                MarketCondition.IMPULSE -> momentum.direction
                else -> DirectionInfluence.NEUTRAL
            }
            add(
                assessment(
                    "Рыночные условия",
                    conditionDirection,
                    trend.confidence.takeIf { conditionRun.value != MarketCondition.UNDEFINED } ?: 0.0,
                    (weights.market - volatilityWeight).coerceAtLeast(0.0),
                    conditionRun.failureReason,
                    conditionRun.value != MarketCondition.UNDEFINED,
                    "Условия рынка не подтверждены",
                ),
            )
        }

        predictionEngine.predict(
            frames = frames,
            instrument = instrument,
            currentPrice = currentPrice,
            timeframe = timeframe,
            candleAnalysis = candleRun.value,
            trend = trend,
            momentum = momentum,
            volatility = volatility,
            levels = levels,
            indicators = indicators,
            marketCondition = conditionRun.value,
            engineAssessments = assessments,
        )
    }

    private fun <T> safely(name: String, fallback: T, calculate: () -> T): EngineRun<T> =
        try {
            EngineRun(calculate(), null)
        } catch (error: RuntimeException) {
            EngineRun(fallback, "$name: ${error.message ?: "ошибка расчёта"}")
        } catch (error: LinkageError) {
            EngineRun(fallback, "$name: нативный модуль недоступен")
        }

    private fun assessment(
        name: String,
        direction: DirectionInfluence,
        confidence: Double,
        weight: Double,
        executionFailure: String?,
        hasEnoughData: Boolean,
        insufficientDataReason: String,
    ) = EngineAssessment(
        name = name,
        direction = direction,
        confidence = confidence.coerceIn(0.0, 1.0),
        weight = weight,
        successful = executionFailure == null && hasEnoughData,
        failureReason = executionFailure ?: insufficientDataReason.takeUnless { hasEnoughData },
    )

    private fun candleDirection(result: CandleAnalysisResult): DirectionInfluence {
        val bullish = result.patterns.filter { it.direction == PatternDirection.BULLISH }
            .sumOf { it.confidence } +
            result.candles.filter { it.direction == CandleDirection.UP }.sumOf { it.relativeStrength }
        val bearish = result.patterns.filter { it.direction == PatternDirection.BEARISH }
            .sumOf { it.confidence } +
            result.candles.filter { it.direction == CandleDirection.DOWN }.sumOf { it.relativeStrength }
        return when {
            bullish > bearish -> DirectionInfluence.UP
            bearish > bullish -> DirectionInfluence.DOWN
            else -> DirectionInfluence.NEUTRAL
        }
    }

    private fun candleConfidence(result: CandleAnalysisResult): Double {
        val bullish = result.patterns.filter { it.direction == PatternDirection.BULLISH }
            .sumOf { it.confidence } +
            result.candles.filter { it.direction == CandleDirection.UP }.sumOf { it.relativeStrength }
        val bearish = result.patterns.filter { it.direction == PatternDirection.BEARISH }
            .sumOf { it.confidence } +
            result.candles.filter { it.direction == CandleDirection.DOWN }.sumOf { it.relativeStrength }
        val total = bullish + bearish
        return if (total == 0.0) 0.0 else maxOf(bullish, bearish) / total
    }

    private fun priceActionDirection(candles: List<DetailedCandle>): DirectionInfluence {
        val closes = candles.mapNotNull { it.close }
        if (closes.size < 2) return DirectionInfluence.NEUTRAL
        return when {
            closes.last() > closes.first() -> DirectionInfluence.UP
            closes.last() < closes.first() -> DirectionInfluence.DOWN
            else -> DirectionInfluence.NEUTRAL
        }
    }

    private fun priceActionConfidence(candles: List<DetailedCandle>): Double {
        val closes = candles.mapNotNull { it.close }
        if (closes.size < MIN_CANDLES) return 0.0
        val spread = (closes.maxOrNull() ?: 0.0) - (closes.minOrNull() ?: 0.0)
        if (spread <= 0.0) return 0.0
        return (kotlin.math.abs(closes.last() - closes.first()) / spread)
            .coerceIn(0.0, 1.0)
    }

    private fun TrendDirection.toInfluence(): DirectionInfluence = when (this) {
        TrendDirection.UP, TrendDirection.STRENGTHENING -> DirectionInfluence.UP
        TrendDirection.DOWN, TrendDirection.WEAKENING -> DirectionInfluence.DOWN
        else -> DirectionInfluence.NEUTRAL
    }

    private data class EngineRun<T>(
        val value: T,
        val failureReason: String?,
    )

    private companion object {
        const val MIN_CANDLES = 3
        const val MIN_MOMENTUM_SAMPLES = 3
        const val VOLATILITY_WEIGHT = 0.08
        const val GEOMETRY_INDICATOR_SOURCE = "расчёт по координатам"
    }
}
