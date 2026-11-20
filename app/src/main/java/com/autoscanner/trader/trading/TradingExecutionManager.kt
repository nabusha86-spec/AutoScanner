package com.autoscanner.trader.trading

class TradingExecutionManager {
    private val lock = Any()
    private val attemptedCommandIds = mutableSetOf<String>()
    private var activeCommandId: String? = null

    fun prepare(command: TradingExecutionCommand): ExecutionStatus =
        ExecutionStatus.BLOCKED_NO_APPROVED_MECHANISM

    fun executeIfApproved(command: TradingExecutionCommand): ExecutionStatus =
        prepare(command)

    fun executeIfApproved(
        command: TradingExecutionCommand,
        request: (
            TradingExecutionCommand,
            (ExecutionAttemptResult) -> Unit,
        ) -> Boolean,
        callback: (ExecutionAttemptResult) -> Unit,
    ): Boolean {
        val claimed = synchronized(lock) {
            if (activeCommandId != null || !attemptedCommandIds.add(command.id)) {
                false
            } else {
                activeCommandId = command.id
                true
            }
        }
        if (!claimed) {
            callback(
                ExecutionAttemptResult(
                    ExecutionStatus.EXECUTION_IMPOSSIBLE,
                    "Повторная или параллельная команда заблокирована",
                ),
            )
            return false
        }

        var completed = false
        fun complete(result: ExecutionAttemptResult) {
            val shouldDeliver = synchronized(lock) {
                if (completed) {
                    false
                } else {
                    completed = true
                    if (activeCommandId == command.id) activeCommandId = null
                    true
                }
            }
            if (shouldDeliver) callback(result)
        }

        val queued = try {
            request(command, ::complete)
        } catch (_: SecurityException) {
            complete(ExecutionAttemptResult(
                ExecutionStatus.EXECUTION_IMPOSSIBLE,
                "Система запретила запрос Accessibility",
            ))
            true
        } catch (_: IllegalStateException) {
            complete(ExecutionAttemptResult(
                ExecutionStatus.EXECUTION_IMPOSSIBLE,
                "Служба Accessibility недоступна",
            ))
            true
        }
        if (!queued) {
            complete(ExecutionAttemptResult(
                ExecutionStatus.EXECUTION_IMPOSSIBLE,
                "Служба Accessibility не включена или не подключена",
            ))
        }
        return queued
    }
}
