package com.autoscanner.trader.trading

class TradingResultVerifier {
    fun notConfirmed(): String = "РЕЗУЛЬТАТ НЕ ПОДТВЕРЖДЁН"

    fun verifyObservedPlatformOutcome(
        platformReportedResult: Boolean,
        normalizedOutcome: String?,
        instrumentMatched: Boolean,
        directionMatched: Boolean,
        amountMatched: Boolean,
        expirationCompleted: Boolean,
        entryPriceMatched: Boolean,
    ): TradeOutcome {
        if (!platformReportedResult ||
            !instrumentMatched ||
            !directionMatched ||
            !amountMatched ||
            !expirationCompleted ||
            !entryPriceMatched
        ) return TradeOutcome.UNCONFIRMED
        return when (normalizedOutcome?.uppercase()) {
            "ВЫИГРЫШ", "WIN" -> TradeOutcome.WIN
            "ПРОИГРЫШ", "LOSS" -> TradeOutcome.LOSS
            "ВОЗВРАТ", "НИЧЬЯ", "DRAW" -> TradeOutcome.DRAW
            else -> TradeOutcome.UNCONFIRMED
        }
    }
}
