package com.autoscanner.trader.trading

import com.autoscanner.trader.analytics.PredictionDirection
import com.autoscanner.trader.analytics.TrendDirection
import com.autoscanner.trader.analytics.TrendResult
import com.autoscanner.trader.analytics.DirectionInfluence
import com.autoscanner.trader.analytics.MomentumResult
import com.autoscanner.trader.analytics.VolatilityLevel
import com.autoscanner.trader.analytics.VolatilityResult
import com.autoscanner.trader.analytics.SupportResistanceResult
import com.autoscanner.trader.analytics.ExpirationSelector
import com.autoscanner.trader.vision.AnalysisReport
import com.autoscanner.trader.vision.Candle
import com.autoscanner.trader.vision.ChartAnalysis
import com.autoscanner.trader.vision.FrameAnalysis
import com.autoscanner.trader.vision.ImageQuality
import com.autoscanner.trader.vision.TradingButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoTradingSafetyTest {
    @Test
    fun continuousAnalysisStartsNextCycleOnlyAfterCurrentCycleFinishes() {
        val cycles = ContinuousAnalysisCycle()
        assertTrue(cycles.start())
        assertTrue(cycles.beginCycle())
        assertFalse(cycles.beginCycle())
        assertTrue(cycles.finishCycle())
        assertTrue(cycles.beginCycle())
    }

    @Test
    fun stoppingContinuousAnalysisPreventsAnotherCycle() {
        val cycles = ContinuousAnalysisCycle()
        assertTrue(cycles.start())
        assertTrue(cycles.beginCycle())
        cycles.stop()
        assertFalse(cycles.finishCycle())
        assertFalse(cycles.beginCycle())
        assertTrue(cycles.start())
        assertTrue(cycles.beginCycle())
    }

    private fun report(
        instrument: String = "EUR/USD",
        price: String = "1.1000",
        amount: String = "10",
        adequate: Boolean = true,
        tradingScreen: Boolean = true,
        buy: TradingButton = button("ПОКУПКА", true),
        sell: TradingButton = button("ПРОДАЖА", true),
    ): AnalysisReport {
        val frame = FrameAnalysis(
            sequence = 1,
            timestampMillis = 1000L,
            width = 100,
            height = 100,
            quality = ImageQuality(100, 100, 1.0, 1.0, 1.0, adequate, null),
            tradingScreenDetected = tradingScreen,
            instrument = instrument,
            price = price,
            amount = amount,
            timeframe = "M1",
            timer = "00:45",
            expiration = "00:45",
            chart = ChartAnalysis(true, 0, 0, 100, 100, listOf(
                Candle(1, 0, 20, 10, 40, 15, 45, "ЗЕЛЁНАЯ", 20),
            ), "НЕ ОПРЕДЕЛЕНО", null),
            buttons = listOf(buy, sell),
            indicators = emptyList(),
            otherElements = emptyList(),
            textBoxes = emptyList(),
            errors = emptyList(),
        )
        return AnalysisReport(
            processedFrames = 1,
            tradingScreenDetected = tradingScreen,
            instrument = instrument,
            price = price,
            amount = amount,
            timeframe = "M1",
            timer = "00:45",
            expiration = "00:45",
            candles = frame.chart.candles,
            marketMovement = "НЕ ОПРЕДЕЛЕНО",
            indicators = emptyList(),
            buyButton = buy,
            sellButton = sell,
            imageQuality = "ДОСТАТОЧНО",
            warnings = emptyList(),
            errors = emptyList(),
            frames = listOf(frame),
            reportPath = "",
            prediction = null,
        )
    }

    private fun button(text: String, detected: Boolean, ambiguous: Boolean = false, confidence: Float = 0.8f) =
        TradingButton(
            kind = if (text.contains("ПОКУП")) "ПОКУПКА" else "ПРОДАЖА",
            detected = detected,
            text = text,
            left = 70,
            top = if (text.contains("ПОКУП")) 40 else 70,
            right = 95,
            bottom = if (text.contains("ПОКУП")) 65 else 95,
            confidence = confidence,
            state = "ВИДИМА",
            ambiguous = ambiguous,
            message = if (text.contains("ПОКУП")) "ЗЕЛЁНАЯ КНОПКА: ПОКУПКА ОБНАРУЖЕНА"
                else "КРАСНАЯ КНОПКА: ПРОДАЖА ОБНАРУЖЕНА",
        )

    @Test
    fun mediaProjectionUnavailableBlocksPreflight() {
        val reason = TradingSafetyManager().preflight(report(), false, false, false, 0)
        assertNotNull(reason)
        assertTrue(reason!!.contains("MediaProjection"))
    }

    @Test
    fun missingTradingScreenBlocksPreflight() {
        val reason = TradingSafetyManager().preflight(report(tradingScreen = false), true, false, false, 0)
        assertNotNull(reason)
    }

    @Test
    fun poorQualityBlocksPreflight() {
        val reason = TradingSafetyManager().preflight(report(adequate = false), true, false, false, 0)
        assertNotNull(reason)
    }

    @Test
    fun unknownPairBlocksPreflight() {
        val reason = TradingSafetyManager().preflight(report(instrument = "НЕ ОПРЕДЕЛЕНО"), true, false, false, 0)
        assertNotNull(reason)
    }

    @Test
    fun unknownAmountBlocksPreflight() {
        val reason = TradingSafetyManager().preflight(report(amount = "НЕ ОПРЕДЕЛЕНО"), true, false, false, 0)
        assertNotNull(reason)
    }

    @Test
    fun buyRequiresConfirmedGreenButtonSemantics() {
        val manager = TradingSafetyManager()
        assertNull(manager.validateButtons(report(buy = button("ПОКУПКА", true)), PredictionDirection.BUY))
        assertNotNull(manager.validateButtons(report(buy = button("ПРОДАЖА", true)), PredictionDirection.BUY))
    }

    @Test
    fun sellRequiresConfirmedRedButtonSemantics() {
        val manager = TradingSafetyManager()
        assertNull(manager.validateButtons(report(sell = button("ПРОДАЖА", true)), PredictionDirection.SELL))
        assertNotNull(manager.validateButtons(report(sell = button("ПОКУПКА", true)), PredictionDirection.SELL))
    }

    @Test
    fun ambiguousButtonIsBlocked() {
        val reason = TradingSafetyManager().validateButtons(
            report(buy = button("ПОКУПКА", true, ambiguous = true)),
            PredictionDirection.BUY,
        )
        assertNotNull(reason)
    }

    @Test
    fun duplicateCommandIsBlocked() {
        val manager = TradingSafetyManager()
        assertFalse(manager.isDuplicate("unique-command"))
        assertTrue(manager.isDuplicate("unique-command"))
    }

    @Test
    fun riskLimitsBlockConfiguredSession() {
        val risk = TradingRiskManager(TradingSafetyLimits(maxTradesPerSession = 2))
        assertNotNull(risk.canContinue(2))
    }

    @Test
    fun dailyLossLimitBlocksNextTrade() {
        val risk = TradingRiskManager(TradingSafetyLimits(maxDailyLoss = 10.0))
        risk.recordConfirmedLoss(10.0)
        assertNotNull(risk.canContinue(0))
    }

    @Test
    fun consecutiveLossesBlockNextTrade() {
        val risk = TradingRiskManager(TradingSafetyLimits(maxConsecutiveLosses = 2))
        risk.recordConfirmedLoss(1.0)
        risk.recordConfirmedLoss(1.0)
        assertNotNull(risk.canContinue(0))
    }

    @Test
    fun noTradeIsNotExecutable() {
        val reason = TradingSafetyManager().validateButtons(report(), PredictionDirection.NO_TRADE)
        assertNotNull(reason)
    }

    @Test
    fun expirationStaysWithinRange() {
        val result = ExpirationSelector().select(
            PredictionDirection.BUY,
            "H1",
            TrendResult(TrendDirection.UP, 0.9, 0.9, 10, "Рост"),
            MomentumResult(DirectionInfluence.UP, 0.9, 0.9, 1.0, 0.1, false, 10),
            VolatilityResult(VolatilityLevel.MODERATE, 0.9, 1.0),
            SupportResistanceResult(emptyList(), emptyList(), emptyList(), emptyList()),
            10,
            false,
            0.9,
            emptyList(),
        )
        assertTrue(result.seconds == null || result.seconds!! in 15..180)
    }

    @Test
    fun executionManagerNeverReportsApprovedTrade() {
        val command = TradingExecutionCommand("test", PredictionDirection.BUY, "EUR/USD", 1.1, 10.0, 60)
        val status = TradingExecutionManager().executeIfApproved(command)
        assertEquals(ExecutionStatus.BLOCKED_NO_APPROVED_MECHANISM, status)
    }

    @Test
    fun resultVerifierDoesNotFabricateOutcome() {
        assertEquals("РЕЗУЛЬТАТ НЕ ПОДТВЕРЖДЁН", TradingResultVerifier().notConfirmed())
    }

    @Test
    fun emergencyStopAndNormalStopAreSeparate() {
        val stopped = TradingSessionState(status = AutoTradingStatus.STOPPED)
        val emergency = TradingSessionState(
            status = AutoTradingStatus.EMERGENCY_STOPPED,
            emergencyLocked = true,
        )
        assertNull(stopped.copy(mode = TradingMode.AUTO).automaticStartBlockReason())
        assertNotNull(stopped.automaticStartBlockReason())
        assertNotNull(emergency.automaticStartBlockReason())
        assertTrue(emergency.status != stopped.status)
    }

    @Test
    fun martingaleDoublesAfterFirstLoss() {
        val manager = MartingaleStrategyManager(initialAmount = 1.0, maxAmount = 100.0, maxDoublings = 2)
        manager.configureAllowedByUser(true)
        val update = manager.onConfirmedLoss()
        assertEquals(2.0, update.nextAmount, 0.0001)
    }

    @Test
    fun martingaleDoublesAfterSecondLossAndStopsAtThirdLossInManager() {
        val manager = MartingaleStrategyManager(initialAmount = 1.0, maxAmount = 100.0, maxDoublings = 2)
        manager.configureAllowedByUser(true)
        assertEquals(2.0, manager.onConfirmedLoss().nextAmount, 0.0001)
        assertEquals(4.0, manager.onConfirmedLoss().nextAmount, 0.0001)
        val third = manager.onConfirmedLoss()
        assertEquals(3, third.consecutiveLosses)
        assertEquals(4.0, third.nextAmount, 0.0001)
    }

    @Test
    fun winResetsLossStreakAndCurrentAmount() {
        val manager = MartingaleStrategyManager(initialAmount = 1.0, maxAmount = 100.0, maxDoublings = 2)
        manager.configureAllowedByUser(true)
        manager.onConfirmedLoss()
        manager.onConfirmedLoss()
        manager.onConfirmedWin()
        assertEquals(0, manager.consecutiveLosses)
        assertEquals(1.0, manager.currentAmount, 0.0001)
    }

    @Test
    fun unconfirmedResultDoesNotModifyMartingale() {
        val manager = MartingaleStrategyManager(initialAmount = 1.0, maxAmount = 100.0, maxDoublings = 2)
        manager.configureAllowedByUser(true)
        val before = manager.currentAmount
        val outcome = TradingResultVerifier().verifyObservedPlatformOutcome(
            platformReportedResult = false,
            normalizedOutcome = null,
            instrumentMatched = true,
            directionMatched = true,
            amountMatched = true,
            expirationCompleted = true,
            entryPriceMatched = true,
        )
        assertEquals(TradeOutcome.UNCONFIRMED, outcome)
        assertEquals(before, manager.currentAmount, 0.0001)
    }

    @Test
    fun drawDoesNotResetConfirmedLossSeries() {
        val manager = MartingaleStrategyManager(initialAmount = 1.0, maxAmount = 100.0, maxDoublings = 2)
        manager.configureAllowedByUser(true)
        manager.onConfirmedLoss()
        manager.onConfirmedDraw()
        assertEquals(1, manager.consecutiveLosses)
    }

    @Test
    fun thirdConsecutiveLossReachesEmergencyRiskThreshold() {
        val risk = TradingRiskManager(TradingSafetyLimits(maxConsecutiveLosses = 3))
        repeat(2) {
            assertTrue(risk.recordConfirmedLoss(1.0))
            assertFalse(risk.consecutiveLossLimitReached)
        }
        assertTrue(risk.recordConfirmedLoss(4.0))
        assertTrue(risk.consecutiveLossLimitReached)
        assertNotNull(risk.canContinue(0))
    }

    @Test
    fun winResetsOnlyConsecutiveLossLimit() {
        val risk = TradingRiskManager(TradingSafetyLimits(maxConsecutiveLosses = 3))
        repeat(2) { assertTrue(risk.recordConfirmedLoss(1.0)) }
        risk.recordConfirmedWin()
        assertEquals(0, risk.consecutiveLosses)
        assertFalse(risk.consecutiveLossLimitReached)
        assertEquals(2.0, risk.dailyLoss, 0.0001)
    }

    @Test
    fun invalidLossAmountCannotChangeRiskCounters() {
        val risk = TradingRiskManager()
        assertFalse(risk.recordConfirmedLoss(Double.NaN))
        assertFalse(risk.recordConfirmedLoss(0.0))
        assertEquals(0.0, risk.dailyLoss, 0.0001)
        assertEquals(0, risk.consecutiveLosses)
    }

    @Test
    fun martingaleCapsAmountAndNumberOfDoublings() {
        val manager = MartingaleStrategyManager(initialAmount = 1.0, maxAmount = 3.0, maxDoublings = 2)
        manager.configureAllowedByUser(true)
        assertEquals(2.0, manager.onConfirmedLoss().nextAmount, 0.0001)
        assertEquals(3.0, manager.onConfirmedLoss().nextAmount, 0.0001)
        assertEquals(3.0, manager.onConfirmedLoss().nextAmount, 0.0001)
        assertEquals(2, manager.doublingsUsed)
    }

    @Test
    fun buyRequiresGreenButtonMessageAndNonOverlappingGeometry() {
        val manager = TradingSafetyManager()
        val redBuy = button("ПОКУПКА", true).copy(message = "КРАСНАЯ КНОПКА: ПОКУПКА")
        assertNotNull(manager.validateButtons(report(buy = redBuy), PredictionDirection.BUY))
        val overlappingSell = button("ПРОДАЖА", true).copy(top = 40, bottom = 65)
        assertNotNull(manager.validateButtons(
            report(sell = overlappingSell),
            PredictionDirection.BUY,
        ))
    }

    @Test
    fun bothButtonsMustBeConfirmed() {
        val reason = TradingSafetyManager().validateButtons(
            report(sell = button("ПРОДАЖА", detected = false)),
            PredictionDirection.BUY,
        )
        assertNotNull(reason)
    }

    @Test
    fun calculatedMartingaleAmountIsPassedToExecutionCommand() {
        val command = TradingSafetyManager().createCommand(
            report(),
            PredictionDirection.BUY,
            expirationSeconds = 60,
            amountOverride = 4.0,
        )
        assertNotNull(command)
        assertEquals(4.0, command!!.amount, 0.0001)
    }

    @Test
    fun duplicateAnalysisProducesStableCommandId() {
        val manager = TradingSafetyManager()
        val first = manager.createCommand(report(), PredictionDirection.BUY, 60, amountOverride = 2.0)
        val second = manager.createCommand(report(), PredictionDirection.BUY, 60, amountOverride = 2.0)
        assertNotNull(first)
        assertEquals(first!!.id, second!!.id)
        assertFalse(manager.isDuplicate(first.id))
        assertTrue(manager.isDuplicate(second.id))
    }

    @Test
    fun amountAboveConfiguredMaximumIsRejected() {
        val manager = TradingSafetyManager(TradingSafetyLimits(maxTradeAmount = 3.0))
        assertNull(manager.createCommand(report(), PredictionDirection.BUY, 60, amountOverride = 4.0))
        assertNotNull(manager.preflight(report(), true, false, false, 0, currentAmount = 4.0))
    }

    @Test
    fun resultRequiresAllPlatformEvidenceToBeConfirmed() {
        val outcome = TradingResultVerifier().verifyObservedPlatformOutcome(
            platformReportedResult = true,
            normalizedOutcome = "ВЫИГРЫШ",
            instrumentMatched = false,
            directionMatched = true,
            amountMatched = true,
            expirationCompleted = true,
            entryPriceMatched = true,
        )
        assertEquals(TradeOutcome.UNCONFIRMED, outcome)
    }

    @Test
    fun manualModeCannotStartAutomaticCycle() {
        val state = TradingSessionState(mode = TradingMode.MANUAL)
        assertNotNull(state.automaticStartBlockReason())
        assertNull(state.copy(mode = TradingMode.AUTO).automaticStartBlockReason())
    }

    @Test
    fun unresolvedResultRemainsBlockedEvenInAutoMode() {
        val state = TradingSessionState(
            mode = TradingMode.AUTO,
            tradeCycleState = TradeCycleState.BLOCKED_UNCONFIRMED,
        )
        assertNotNull(state.automaticStartBlockReason())
    }
}
