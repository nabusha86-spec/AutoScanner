package com.autoscanner.trader.trading

class TradingRiskManager(private val limits: TradingSafetyLimits = TradingSafetyLimits()) {
    var dailyLoss: Double = 0.0
        private set
    var consecutiveLosses: Int = 0
        private set
    val consecutiveLossLimitReached: Boolean
        get() = consecutiveLosses >= limits.maxConsecutiveLosses
    val dailyLossLimitReached: Boolean
        get() = dailyLoss >= limits.maxDailyLoss

    fun canContinue(completedTrades: Int): String? {
        if (completedTrades >= limits.maxTradesPerSession) return "Достигнут лимит сделок за сессию"
        if (dailyLossLimitReached) return "Достигнут лимит дневного убытка"
        if (consecutiveLossLimitReached) return "Достигнут лимит последовательных проигрышей"
        return null
    }

    fun recordConfirmedLoss(amount: Double): Boolean {
        if (!amount.isFinite() || amount <= 0.0) return false
        dailyLoss += amount
        consecutiveLosses += 1
        return true
    }

    fun recordConfirmedWin() {
        consecutiveLosses = 0
    }

    fun recordConfirmedDraw() {
        // Возврат не является проигрышем и не сбрасывает подтверждённую серию.
    }

    fun restore(dailyLoss: Double, consecutiveLosses: Int): Boolean {
        if (!dailyLoss.isFinite() || dailyLoss < 0.0 || consecutiveLosses < 0) return false
        this.dailyLoss = dailyLoss
        this.consecutiveLosses = consecutiveLosses
        return true
    }
}
