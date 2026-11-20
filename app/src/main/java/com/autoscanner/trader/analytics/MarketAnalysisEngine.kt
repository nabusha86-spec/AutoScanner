package com.autoscanner.trader.analytics

import com.autoscanner.trader.vision.FrameAnalysis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MarketAnalysisEngine(
    weights: PredictionWeights = PredictionWeights(),
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
        val candleAnalysis = candleEngine.analyze(frames)
        val trend = trendEngine.analyze(candleAnalysis.candles)
        val momentum = momentumEngine.analyze(frames, candleAnalysis.candles)
        val volatility = volatilityEngine.analyze(candleAnalysis.candles)
        val currentPrice = price?.replace(',', '.')?.toDoubleOrNull()
        val levels = supportResistanceEngine.analyze(candleAnalysis.candles, currentPrice)
        val indicators = indicatorEngine.analyze(frames.flatMap { it.indicators })
        val adequateCount = frames.count { it.quality.isAdequate }.toDouble()
        val adequateRatio = if (frames.isNotEmpty()) adequateCount / frames.size else 0.0
        val instrumentKnown = instrument != null && instrument != "НЕ ОПРЕДЕЛЕНО"
        val marketCondition = marketConditionAnalyzer.analyze(
            trend,
            momentum,
            volatility,
            candleAnalysis.candles.size,
            instrumentKnown,
            adequateRatio,
            candleAnalysis.patterns,
        )
        predictionEngine.predict(
            frames = frames,
            instrument = instrument,
            currentPrice = currentPrice,
            timeframe = timeframe,
            candleAnalysis = candleAnalysis,
            trend = trend,
            momentum = momentum,
            volatility = volatility,
            levels = levels,
            indicators = indicators,
            marketCondition = marketCondition,
        )
    }
}
