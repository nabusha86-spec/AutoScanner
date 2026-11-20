package com.autoscanner.trader.trading

import com.autoscanner.trader.analytics.PredictionDirection

enum class TradingMode {
    MANUAL,
    AUTO,
}

enum class AutoTradingStatus {
    WAITING,
    CHECKING,
    CHECKING_SCREEN,
    CAPTURING,
    ANALYZING,
    DETERMINING_DIRECTION,
    SELECTING_EXPIRATION,
    SAFETY_CHECK,
    EXECUTING,
    TRADE_SKIPPED,
    EXECUTION_UNAVAILABLE,
    WAITING_NEXT,
    STOPPED,
    EMERGENCY_STOPPED,
    ERROR,
}

enum class EntryDecision {
    ENTER_NOW,
    WAIT_FOR_CANDLE_CLOSE,
    ENTER_AT_NEXT_CANDLE,
    WAIT_FOR_CONFIRMATION,
    NO_TRADE,
}

enum class TradeOutcome {
    WIN,
    LOSS,
    DRAW,
    UNCONFIRMED,
}

enum class TradeCycleState {
    NONE,
    OPEN,
    OBSERVING,
    COMPLETED,
    BLOCKED_UNCONFIRMED,
}

enum class ExecutionStatus {
    NOT_ATTEMPTED,
    BLOCKED_NO_APPROVED_MECHANISM,
    BLOCKED_SAFETY,
    RESULT_NOT_CONFIRMED,
    EXECUTION_IN_PROGRESS,
    ACTION_PERFORMED_ACCEPTANCE_UNCONFIRMED,
    PLATFORM_ACCEPTANCE_CONFIRMED,
    EXECUTION_IMPOSSIBLE,
}

data class ExecutionAttemptResult(
    val status: ExecutionStatus,
    val detail: String,
)

data class TradingSafetyLimits(
    val maxTradesPerSession: Int = 10,
    val maxTradeAmount: Double = 100.0,
    val maxDailyLoss: Double = 50.0,
    val maxConsecutiveLosses: Int = 3,
    val delayBetweenTradesSeconds: Int = 30,
    val initialTradeAmount: Double = 1.0,
    val maxMartingaleDoublings: Int = 2,
)

data class TradingSessionState(
    val running: Boolean = false,
    val mode: TradingMode = TradingMode.MANUAL,
    val status: AutoTradingStatus = AutoTradingStatus.WAITING,
    val instrument: String = "НЕ ОПРЕДЕЛЕНО",
    val currentPrice: String = "НЕ ОПРЕДЕЛЕНО",
    val lastAnalysisTimeMillis: Long? = null,
    val forecast: PredictionDirection = PredictionDirection.NO_TRADE,
    val confidence: Double = 0.0,
    val expirationSeconds: Int? = null,
    val captureState: String = "НЕ АКТИВЕН",
    val buyButtonState: String = "НЕ ОПРЕДЕЛЕНО",
    val sellButtonState: String = "НЕ ОПРЕДЕЛЕНО",
    val executionStatus: ExecutionStatus = ExecutionStatus.NOT_ATTEMPTED,
    val completedCycles: Int = 0,
    val skippedTrades: Int = 0,
    val confirmedTrades: Int = 0,
    val lastSkipReason: String = "ПРОПУСК ЕЩЁ НЕ ВЫПОЛНЕН",
    val lastConfirmedResult: String = "РЕЗУЛЬТАТ НЕ ПОДТВЕРЖДЁН",
    val emergencyReason: String? = null,
    val emergencyLocked: Boolean = false,
    val initialAmount: Double = 1.0,
    val currentAmount: Double = 1.0,
    val maxAmount: Double = 100.0,
    val dailyLoss: Double = 0.0,
    val maxDailyLoss: Double = 50.0,
    val consecutiveLosses: Int = 0,
    val consecutiveWins: Int = 0,
    val confirmedWins: Int = 0,
    val confirmedLosses: Int = 0,
    val confirmedDraws: Int = 0,
    val martingaleEnabled: Boolean = false,
    val martingaleAllowedByUser: Boolean = false,
    val martingaleDoublings: Int = 0,
    val maxMartingaleDoublings: Int = 2,
    val entryDecision: EntryDecision = EntryDecision.NO_TRADE,
    val tradeCycleState: TradeCycleState = TradeCycleState.NONE,
)

fun TradingSessionState.automaticStartBlockReason(): String? = when {
    emergencyLocked -> "Автоматический запуск заблокирован до подтверждения пользователя"
    tradeCycleState == TradeCycleState.BLOCKED_UNCONFIRMED ||
        tradeCycleState == TradeCycleState.OBSERVING ->
        "Автоматическая торговля заблокирована до подтверждения результата сделки"
    mode != TradingMode.AUTO -> "Для запуска активируйте АВТОМАТИЧЕСКИЙ режим"
    else -> null
}

data class TradingExecutionCommand(
    val id: String,
    val direction: PredictionDirection,
    val instrument: String,
    val price: Double,
    val amount: Double,
    val expirationSeconds: Int,
)
