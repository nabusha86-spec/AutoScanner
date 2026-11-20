package com.autoscanner.trader.analytics

import com.autoscanner.trader.vision.Candle
import com.autoscanner.trader.vision.ChartAnalysis
import com.autoscanner.trader.vision.FrameAnalysis
import com.autoscanner.trader.vision.ImageQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyticsEngineTest {
    private fun candle(index: Int, close: Double, direction: CandleDirection = CandleDirection.UP): DetailedCandle {
        val body = 10.0
        val high = close + 5.0
        val low = close - 5.0
        return DetailedCandle(index, 1_000L * index, close - body, close, high, low,
            body, 5.0, 5.0,
            if (direction == CandleDirection.UP) CandleColor.GREEN else CandleColor.RED,
            direction, 0.5, if (index == 1) null else 1.0)
    }

    private fun frame(
        candles: List<Candle>,
        instrument: String = "EUR/USD",
        price: String = "1.1000",
        adequate: Boolean = true,
    ) = FrameAnalysis(
        sequence = 1,
        timestampMillis = 1000L,
        width = 100,
        height = 100,
        quality = ImageQuality(100, 100, 1.0, 1.0, 1.0, adequate, null),
        tradingScreenDetected = true,
        instrument = instrument,
        price = price,
        amount = "10",
        timeframe = "M1",
        timer = "00:45",
        expiration = "00:45",
        chart = ChartAnalysis(true, 0, 0, 100, 100, candles, "НЕ ОПРЕДЕЛЕНО", null),
        buttons = emptyList(),
        indicators = emptyList(),
        otherElements = emptyList(),
        textBoxes = emptyList(),
        errors = emptyList(),
    )

    @Test
    fun risingSequenceProducesUpTrend() {
        val candles = (1..8).map { candle(it, it.toDouble(), CandleDirection.UP) }
        val result = TrendAnalysisEngine().analyze(candles)
        assertEquals(TrendDirection.UP, result.direction)
    }

    @Test
    fun fallingSequenceProducesDownTrend() {
        val candles = (8 downTo 1).map { candle(it, it.toDouble(), CandleDirection.DOWN) }
        val result = TrendAnalysisEngine().analyze(candles)
        assertEquals(TrendDirection.DOWN, result.direction)
    }

    @Test
    fun sidewaySequenceIsNotDirectional() {
        val values = listOf(10.0, 10.2, 9.8, 10.1, 9.9, 10.0)
        val candles = values.mapIndexed { i, value ->
            candle(i + 1, value, if (i % 2 == 0) CandleDirection.UP else CandleDirection.DOWN)
        }
        val result = TrendAnalysisEngine().analyze(candles)
        assertTrue(result.direction == TrendDirection.SIDEWAYS || result.direction == TrendDirection.UNCONFIRMED)
    }

    @Test
    fun weakAndStrongMomentumDiffer() {
        val weak = (1..5).map { candle(it, it.toDouble(), CandleDirection.UP).copy(changeFromPrevious = 0.1) }
        val strong = (1..5).map { candle(it, it * 10.0, CandleDirection.UP).copy(changeFromPrevious = 10.0) }
        val frame = frame(emptyList())
        assertTrue(MomentumAnalysisEngine().analyze(listOf(frame), strong).strength >
            MomentumAnalysisEngine().analyze(listOf(frame), weak).strength)
    }

    @Test
    fun highAndLowVolatilityDiffer() {
        val low = (1..6).map { candle(it, it.toDouble()).copy(high = it + 0.05, low = it - 0.05) }
        val high = (1..6).map { index ->
            val size = if (index <= 3) 1.0 else 20.0
            candle(index, index.toDouble()).copy(high = index + size, low = index - size)
        }
        assertEquals(VolatilityLevel.LOW, VolatilityAnalysisEngine().analyze(low).level)
        val highLevel = VolatilityAnalysisEngine().analyze(high).level
        assertTrue(highLevel == VolatilityLevel.HIGH || highLevel == VolatilityLevel.SHARP_INCREASE)
    }

    @Test
    fun recognizesHammerPattern() {
        val hammer = candle(1, 10.0, CandleDirection.UP).copy(
            bodySize = 3.0,
            upperShadow = 1.0,
            lowerShadow = 12.0,
        )
        val result = PatternRecognitionEngine().recognize(listOf(hammer), 1000L)
        assertTrue(result.any { it.name == "Молот" })
    }

    @Test
    fun indicatorConflictIsNeutral() {
        val indicators = IndicatorAnalysisEngine().analyze(listOf("RSI", "MACD"))
        assertTrue(indicators.all { it.direction == DirectionInfluence.NEUTRAL })
    }

    @Test
    fun approachingSupportAndResistancePreservesZones() {
        val candles = listOf(
            candle(1, 100.0).copy(low = 90.0, high = 110.0),
            candle(2, 95.0).copy(low = 85.0, high = 105.0),
            candle(3, 100.0).copy(low = 87.0, high = 112.0),
        )
        val result = SupportResistanceEngine().analyze(candles, 100.0)
        assertTrue(result.support.isNotEmpty() || result.resistance.isNotEmpty())
    }

    @Test
    fun insufficientCandlesProduceNoTrade() {
        val result = MarketAnalysisEngine().runBlockingAnalyze(emptyList(), "EUR/USD", "1.1000", "M1")
        assertEquals(PredictionDirection.NO_TRADE, result.direction)
        assertNotNull(result.expiration.limitations)
        assertNull(result.expiration.seconds)
    }

    @Test
    fun missingPairProducesNoTrade() {
        val frames = (1..6).map { frame(emptyList(), instrument = "НЕ ОПРЕДЕЛЕНО", price = "НЕ ОПРЕДЕЛЕНО") }
        val result = MarketAnalysisEngine().runBlockingAnalyze(frames, "НЕ ОПРЕДЕЛЕНО", "НЕ ОПРЕДЕЛЕНО", "M1")
        assertEquals(PredictionDirection.NO_TRADE, result.direction)
    }

    @Test
    fun poorImageQualityProducesNoTrade() {
        val frames = (1..6).map { frame(emptyList(), adequate = false) }
        val result = MarketAnalysisEngine().runBlockingAnalyze(frames, "EUR/USD", "1.1000", "M1")
        assertEquals(PredictionDirection.NO_TRADE, result.direction)
    }

    @Test
    fun unrecognizedTradingInterfaceCannotProduceSignal() {
        val frames = (1..6).map {
            frame(emptyList()).copy(tradingScreenDetected = false)
        }
        val result = MarketAnalysisEngine().runBlockingAnalyze(frames, "EUR/USD", "1.1000", "M1")
        assertEquals(PredictionDirection.NO_TRADE, result.direction)
        assertTrue(result.risks.any { it.name == "Торговый интерфейс" })
    }

    @Test
    fun chartDataProducesUpDirectionWithoutRecognizedTradingInterface() {
        val candles = (0 until 6).map { index ->
            val top = 40 - index * 2
            Candle(index + 1, index * 5, top, index * 5 + 3, top + 8,
                top, top + 8, "ЗЕЛЁНАЯ", top)
        }
        val frames = (1..6).map {
            frame(candles, instrument = "НЕ ОПРЕДЕЛЕНО", price = "НЕ ОПРЕДЕЛЕНО")
                .copy(tradingScreenDetected = false)
        }

        val result = MarketAnalysisEngine().runBlockingAnalyze(
            frames,
            "НЕ ОПРЕДЕЛЕНО",
            "НЕ ОПРЕДЕЛЕНО",
            "M1",
        )

        assertEquals(PredictionDirection.BUY, result.direction)
        assertTrue(result.confidence > 0.0)
        assertTrue(result.confidence <= 1.0)
        assertNotNull(result.expiration.seconds)
    }

    @Test
    fun chartDataProducesDownDirectionWithoutRecognizedTradingInterface() {
        val candles = (0 until 6).map { index ->
            val top = 30 + index * 2
            Candle(index + 1, index * 5, top, index * 5 + 3, top + 8,
                top, top + 8, "КРАСНАЯ", top + 8)
        }
        val frames = (1..6).map {
            frame(candles, instrument = "НЕ ОПРЕДЕЛЕНО", price = "НЕ ОПРЕДЕЛЕНО")
                .copy(tradingScreenDetected = false)
        }

        val result = MarketAnalysisEngine().runBlockingAnalyze(
            frames,
            "НЕ ОПРЕДЕЛЕНО",
            "НЕ ОПРЕДЕЛЕНО",
            "M1",
        )

        assertEquals(PredictionDirection.SELL, result.direction)
        assertTrue(result.confidence in 0.0..1.0)
    }

    @Test
    fun expirationIsCappedAtThreeMinutes() {
        val result = ExpirationSelector().select(
            PredictionDirection.BUY, "H1",
            TrendResult(TrendDirection.UP, 0.9, 0.9, 10, "рост"),
            MomentumResult(DirectionInfluence.UP, 0.9, 0.9, 1.0, 0.1, false, 10),
            VolatilityResult(VolatilityLevel.MODERATE, 0.8, 1.0),
            SupportResistanceResult(emptyList(), emptyList(), emptyList(), emptyList()),
            10, false, 0.9, emptyList(),
        )
        assertTrue((result.seconds ?: 0) <= 180)
        assertTrue(result.seconds?.let { it in listOf(15, 30, 60, 120, 180) } == true)
    }

    @Test
    fun shortAndLongExpirationCanDiffer() {
        val base = TrendResult(TrendDirection.UP, 0.9, 0.9, 10, "рост")
        val longResult = ExpirationSelector().select(
            PredictionDirection.BUY, "M1", base,
            MomentumResult(DirectionInfluence.UP, 0.3, 0.7, null, null, false, 5),
            VolatilityResult(VolatilityLevel.MODERATE, 0.8, 1.0),
            SupportResistanceResult(emptyList(), emptyList(), emptyList(), emptyList()),
            10, false, 0.8, emptyList(),
        )
        val shortResult = ExpirationSelector().select(
            PredictionDirection.BUY, "M1", base,
            MomentumResult(DirectionInfluence.UP, 0.9, 0.9, 5.0, 1.0, false, 10),
            VolatilityResult(VolatilityLevel.HIGH, 0.9, 1.5),
            SupportResistanceResult(emptyList(), emptyList(), emptyList(), emptyList()),
            10, false, 0.9, emptyList(),
        )
        assertTrue((shortResult.seconds ?: 0) < (longResult.seconds ?: 0))
    }

    @Test
    fun conflictingAnalysisKeepsMinimumExpirationDespiteStrongTrend() {
        val result = ExpirationSelector().select(
            PredictionDirection.BUY,
            "M1",
            TrendResult(TrendDirection.UP, 0.9, 0.9, 10, "рост"),
            MomentumResult(DirectionInfluence.UP, 0.3, 0.7, null, null, false, 5),
            VolatilityResult(VolatilityLevel.MODERATE, 0.8, 1.0),
            SupportResistanceResult(emptyList(), emptyList(), emptyList(), emptyList()),
            10,
            true,
            0.4,
            emptyList(),
        )

        assertEquals(15, result.seconds)
    }

    @Test
    fun lowConfidenceBlocksPredictionAndExpiration() {
        val factors = listOf(
            SignalFactor("Тренд", "UP", DirectionInfluence.UP, 0.28, 0.30, "тест"),
            SignalFactor("Импульс", "DOWN", DirectionInfluence.DOWN, 0.20, 0.30, "тест"),
        )
        val confidence = SignalConfidenceEngine(0.68).weightedConfidence(factors)
        assertFalse(SignalConfidenceEngine(0.68).meetsMinimum(confidence))
    }

    private fun MarketAnalysisEngine.runBlockingAnalyze(
        frames: List<FrameAnalysis>,
        instrument: String?,
        price: String?,
        timeframe: String?,
    ): PredictionResult = kotlinx.coroutines.runBlocking {
        analyze(frames, instrument, price, timeframe)
    }
}
