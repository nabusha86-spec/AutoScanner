package com.autoscanner.trader.analytics

import com.autoscanner.trader.vision.FrameAnalysis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AnalyticsCoordinator(
    private val weights: PredictionWeights = PredictionWeights(),
    private val minimumConfidence: Double = 0.68,
) {
    private val market = MarketAnalysisEngine(weights, minimumConfidence)

    suspend fun analyze(
        frames: List<FrameAnalysis>,
        instrument: String?,
        price: String?,
        timeframe: String?,
    ): PredictionResult = withContext(Dispatchers.Default) {
        market.analyze(frames, instrument, price, timeframe)
    }
}
