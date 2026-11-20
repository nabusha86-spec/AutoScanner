package com.autoscanner.trader.trading

class TradingExecutionManager {
    fun prepare(command: TradingExecutionCommand): ExecutionStatus =
        ExecutionStatus.BLOCKED_NO_APPROVED_MECHANISM

    fun executeIfApproved(command: TradingExecutionCommand): ExecutionStatus =
        ExecutionStatus.BLOCKED_NO_APPROVED_MECHANISM
}
