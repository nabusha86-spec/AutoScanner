package com.autoscanner.trader.trading

class MartingaleStrategyManager(
    initialAmount: Double = 1.0,
    val maxAmount: Double = 100.0,
    val maxDoublings: Int = 2,
) {
    init {
        require(initialAmount.isFinite() && initialAmount > 0.0 && initialAmount <= maxAmount)
        require(maxAmount.isFinite() && maxAmount > 0.0)
        require(maxDoublings >= 0)
    }

    var initialAmount: Double = initialAmount
        private set
    var currentAmount: Double = initialAmount
        private set
    var consecutiveLosses: Int = 0
        private set
    var consecutiveWins: Int = 0
        private set
    var doublingsUsed: Int = 0
        private set
    var enabled: Boolean = false
        private set
    var allowedByUser: Boolean = false
        private set

    fun configureAllowedByUser(enabled: Boolean) {
        allowedByUser = enabled
        this.enabled = enabled
    }

    fun setInitialAmount(amount: Double): Boolean {
        if (!amount.isFinite() || amount <= 0.0 || amount > maxAmount) return false
        if (consecutiveLosses > 0 || consecutiveWins > 0) return false
        initialAmount = amount
        currentAmount = amount
        return true
    }

    fun restore(initialAmount: Double, currentAmount: Double, losses: Int, wins: Int,
                doublings: Int, enabled: Boolean, allowedByUser: Boolean): Boolean {
        if (!initialAmount.isFinite() || !currentAmount.isFinite() ||
            initialAmount <= 0.0 || currentAmount <= 0.0 ||
            initialAmount > maxAmount || currentAmount > maxAmount ||
            losses < 0 || wins < 0 || doublings < 0 || doublings > maxDoublings
        ) return false
        this.initialAmount = initialAmount
        this.currentAmount = currentAmount
        consecutiveLosses = losses
        consecutiveWins = wins
        doublingsUsed = doublings
        this.allowedByUser = allowedByUser
        this.enabled = enabled && allowedByUser
        return true
    }

    fun onConfirmedLoss(): MartingaleLossUpdate {
        consecutiveLosses += 1
        consecutiveWins = 0
        if (!enabled || doublingsUsed >= maxDoublings) {
            return MartingaleLossUpdate(
                currentAmount,
                false,
                consecutiveLosses,
                doublingsUsed,
            )
        }
        val doubled = (currentAmount * 2.0).coerceAtMost(maxAmount)
        if (doubled <= currentAmount) {
            return MartingaleLossUpdate(
                currentAmount,
                false,
                consecutiveLosses,
                doublingsUsed,
            )
        }
        currentAmount = doubled
        doublingsUsed += 1
        return MartingaleLossUpdate(
            currentAmount,
            true,
            consecutiveLosses,
            doublingsUsed,
        )
    }

    fun onConfirmedWin() {
        consecutiveWins += 1
        consecutiveLosses = 0
        doublingsUsed = 0
        currentAmount = initialAmount
    }

    fun onConfirmedDraw() {
        // Draw neither resets a loss streak nor advances the Martingale.
    }

    fun resetAfterUserConfirmation(resetAmount: Boolean) {
        consecutiveLosses = 0
        consecutiveWins = 0
        doublingsUsed = 0
        if (resetAmount) currentAmount = initialAmount
    }

    fun canPlaceAmount(amount: Double): Boolean =
        amount.isFinite() && amount > 0.0 && amount <= maxAmount
}

data class MartingaleLossUpdate(
    val nextAmount: Double,
    val doubled: Boolean,
    val consecutiveLosses: Int,
    val doublingsUsed: Int,
)
