package com.autoscanner.trader.trading

import android.content.Context
import android.content.Intent
import android.util.AtomicFile
import com.autoscanner.trader.analytics.PredictionDirection
import com.autoscanner.trader.vision.AnalysisReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

class AutoTradingController(
    context: Context,
    private val limits: TradingSafetyLimits = TradingSafetyLimits(),
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val safety = TradingSafetyManager(limits)
    private val risk = TradingRiskManager(limits)
    private val execution = TradingExecutionManager()
    private val verifier = TradingResultVerifier()
    private val martingale = MartingaleStrategyManager(
        limits.initialTradeAmount,
        limits.maxTradeAmount,
        limits.maxMartingaleDoublings,
    )
    private val stateFile = File(appContext.filesDir, "auto_trading_state.json")
    private val atomicStateFile = AtomicFile(stateFile)
    private var state = loadState()
    private val cycleCoordinator = ContinuousAnalysisCycle()
    private var persistenceRevision = 0L
    @Volatile private var persistedRevision = 0L
    private var nextCycleJob: Job? = null
    private var analysisJob: Job? = null
    private var captureTimeoutJob: Job? = null
    private var captureInProgress = false
    private var projectionAvailable = false
    private var projectionSessionStarted = false
    private var mediaProjectionResultCode: Int? = null
    private var mediaProjectionResultData: Intent? = null
    private var captureAction: ((Intent) -> Unit)? = null
    private var stopCaptureAction: (() -> Unit)? = null
    private var stateListener: ((TradingSessionState) -> Unit)? = null

    init {
        val restored = martingale.restore(
            state.initialAmount,
            state.currentAmount,
            state.consecutiveLosses,
            state.consecutiveWins,
            state.martingaleDoublings,
            state.martingaleEnabled,
            state.martingaleAllowedByUser,
        )
        val countersValid = state.confirmedTrades >= 0 &&
            state.skippedTrades >= 0 &&
            state.completedCycles >= 0 &&
            state.confirmedWins >= 0 &&
            state.confirmedLosses >= 0 &&
            state.confirmedDraws >= 0 &&
            state.dailyLoss.isFinite() &&
            state.dailyLoss >= 0.0
        if (restored && countersValid && risk.restore(state.dailyLoss, state.consecutiveLosses)) {
            state = state.copy(
                maxAmount = limits.maxTradeAmount,
                maxDailyLoss = limits.maxDailyLoss,
                maxMartingaleDoublings = limits.maxMartingaleDoublings,
            )
        } else {
            state = corruptedState("Сохранённые параметры риска недопустимы")
        }
    }

    fun setStateListener(listener: (TradingSessionState) -> Unit) {
        stateListener = listener
        listener(state)
    }

    fun setCaptureAction(action: (Intent) -> Unit) {
        captureAction = action
    }

    fun setStopCaptureAction(action: () -> Unit) {
        stopCaptureAction = action
    }

    fun setMode(mode: TradingMode) {
        if (state.running) {
            updateState(state.copy(status = AutoTradingStatus.ERROR,
                lastSkipReason = "Режим нельзя менять во время активного цикла"))
            return
        }
        if (mode == TradingMode.AUTO &&
            (state.emergencyLocked || state.tradeCycleState == TradeCycleState.BLOCKED_UNCONFIRMED)
        ) {
            updateState(state.copy(
                mode = TradingMode.MANUAL,
                lastSkipReason = "Автоматический режим заблокирован аварийным или неподтверждённым состоянием",
            ))
            return
        }
        updateState(state.copy(mode = mode))
    }

    fun configureMartingale(enabled: Boolean, confirmedByUser: Boolean) {
        if (!confirmedByUser) {
            martingale.configureAllowedByUser(false)
            updateState(state.copy(
                martingaleEnabled = false,
                martingaleAllowedByUser = false,
                lastSkipReason = "Мартингейл отключён: подтверждение пользователя не получено",
            ))
            return
        }
        martingale.configureAllowedByUser(enabled)
        updateState(state.copy(
            martingaleEnabled = enabled,
            martingaleAllowedByUser = enabled,
        ))
    }

    fun setInitialAmount(amount: Double): Boolean {
        val accepted = martingale.setInitialAmount(amount)
        if (accepted) {
            updateState(state.copy(
                initialAmount = martingale.initialAmount,
                currentAmount = martingale.currentAmount,
            ))
        }
        return accepted
    }

    fun grantProjection(resultCode: Int, resultData: Intent) {
        mediaProjectionResultCode = resultCode
        mediaProjectionResultData = resultData
        projectionAvailable = true
        projectionSessionStarted = false
        if (state.running) startCaptureCycle()
    }

    fun attachActiveProjectionSession() {
        projectionAvailable = true
        projectionSessionStarted = true
        mediaProjectionResultCode = null
        mediaProjectionResultData = null
    }

    fun loseProjection() {
        projectionAvailable = false
        stopEmergency("Разрешение MediaProjection потеряно")
    }

    fun start() {
        if (state.running) return
        val startBlockReason = state.automaticStartBlockReason()
        if (startBlockReason != null) {
            updateState(state.copy(
                running = false,
                status = when {
                    state.emergencyLocked -> AutoTradingStatus.EMERGENCY_STOPPED
                    state.tradeCycleState == TradeCycleState.BLOCKED_UNCONFIRMED -> AutoTradingStatus.ERROR
                    else -> AutoTradingStatus.STOPPED
                },
                executionStatus = when {
                    state.emergencyLocked -> ExecutionStatus.BLOCKED_SAFETY
                    state.tradeCycleState == TradeCycleState.BLOCKED_UNCONFIRMED ->
                        ExecutionStatus.RESULT_NOT_CONFIRMED
                    else -> state.executionStatus
                },
                lastSkipReason = startBlockReason,
            ))
            return
        }
        val reason = risk.canContinue(state.confirmedTrades)
        if (reason != null) {
            updateState(state.copy(status = AutoTradingStatus.ERROR, lastSkipReason = reason))
            return
        }
        updateState(state.copy(
            running = true,
            status = AutoTradingStatus.CHECKING,
            emergencyReason = null,
            captureState = "ГОТОВ К ЗАХВАТУ",
        ))
        cycleCoordinator.start()
        if (projectionAvailable && mediaProjectionResultCode != null && mediaProjectionResultData != null) {
            startCaptureCycle()
        } else {
            startCaptureCycle() // Permission request is owned by MainActivity before this call.
        }
    }

    fun stop() {
        nextCycleJob?.cancel()
        nextCycleJob = null
        captureTimeoutJob?.cancel()
        captureTimeoutJob = null
        analysisJob?.cancel()
        analysisJob = null
        cycleCoordinator.stop()
        if (projectionSessionStarted) stopCaptureAction?.invoke()
        captureInProgress = false
        projectionSessionStarted = false
        projectionAvailable = false
        mediaProjectionResultCode = null
        mediaProjectionResultData = null
        updateState(state.copy(
            running = false,
            status = if (state.emergencyLocked) AutoTradingStatus.EMERGENCY_STOPPED
                else AutoTradingStatus.STOPPED,
            captureState = "НЕ АКТИВЕН",
            emergencyReason = if (state.emergencyLocked) state.emergencyReason else null,
            executionStatus = if (state.emergencyLocked) ExecutionStatus.BLOCKED_SAFETY
                else state.executionStatus,
        ))
    }

    fun close() {
        stop()
        persistState(durable = true)
        scope.cancel()
    }

    fun stopEmergency(reason: String) {
        nextCycleJob?.cancel()
        nextCycleJob = null
        captureTimeoutJob?.cancel()
        captureTimeoutJob = null
        analysisJob?.cancel()
        analysisJob = null
        cycleCoordinator.stop()
        if (projectionSessionStarted) stopCaptureAction?.invoke()
        captureInProgress = false
        projectionSessionStarted = false
        projectionAvailable = false
        mediaProjectionResultCode = null
        mediaProjectionResultData = null
        updateState(state.copy(
            running = false,
            status = AutoTradingStatus.EMERGENCY_STOPPED,
            emergencyLocked = true,
            mode = TradingMode.MANUAL,
            captureState = "НЕ АКТИВЕН",
            emergencyReason = reason,
            lastSkipReason = reason,
            executionStatus = ExecutionStatus.BLOCKED_SAFETY,
        ))
    }

    fun confirmEmergencyResume(resetAmount: Boolean, confirmedByUser: Boolean): Boolean {
        if (!confirmedByUser) return false
        if (!state.emergencyLocked) return false
        if (state.tradeCycleState == TradeCycleState.BLOCKED_UNCONFIRMED) {
            updateState(state.copy(
                lastSkipReason = "Возобновление запрещено: результат сделки или сохранённое состояние не подтверждены",
            ))
            return false
        }
        martingale.resetAfterUserConfirmation(resetAmount)
        risk.restore(risk.dailyLoss, 0)
        val balanceReason = risk.canContinue(state.confirmedTrades)
        if (balanceReason != null) {
            updateState(state.copy(
                running = false,
                status = AutoTradingStatus.EMERGENCY_STOPPED,
                emergencyLocked = true,
                mode = TradingMode.MANUAL,
                lastSkipReason = "Возобновление запрещено: $balanceReason",
                executionStatus = ExecutionStatus.BLOCKED_SAFETY,
            ))
            return false
        }
        updateState(state.copy(
            running = false,
            status = AutoTradingStatus.STOPPED,
            emergencyLocked = false,
            emergencyReason = null,
            mode = TradingMode.MANUAL,
            initialAmount = martingale.initialAmount,
            currentAmount = martingale.currentAmount,
            consecutiveLosses = martingale.consecutiveLosses,
            martingaleDoublings = martingale.doublingsUsed,
            executionStatus = ExecutionStatus.NOT_ATTEMPTED,
            tradeCycleState = TradeCycleState.NONE,
            lastSkipReason = "Аварийная блокировка снята подтверждением пользователя",
        ))
        return true
    }

    fun recordConfirmedResult(outcome: TradeOutcome, amount: Double): Boolean {
        if (state.tradeCycleState != TradeCycleState.OBSERVING) {
            blockUnconfirmedResult("Нет активной сделки с подтверждённым официальным исполнением")
            return false
        }
        if (outcome == TradeOutcome.UNCONFIRMED ||
            (outcome == TradeOutcome.LOSS &&
                (!amount.isFinite() || amount <= 0.0 || amount > limits.maxTradeAmount))
        ) {
            blockUnconfirmedResult(
                "Результат или сумма сделки не подтверждены; дальнейшее автоматическое исполнение заблокировано",
            )
            return false
        }
        if (outcome == TradeOutcome.DRAW) {
            martingale.onConfirmedDraw()
            risk.recordConfirmedDraw()
            updateState(state.copy(
                confirmedTrades = state.confirmedTrades + 1,
                confirmedDraws = state.confirmedDraws + 1,
                lastConfirmedResult = "ВОЗВРАТ/НИЧЬЯ",
                tradeCycleState = TradeCycleState.COMPLETED,
                currentAmount = martingale.currentAmount,
            ))
            return true
        }
        if (outcome == TradeOutcome.WIN) {
            martingale.onConfirmedWin()
            risk.recordConfirmedWin()
            updateState(state.copy(
                confirmedTrades = state.confirmedTrades + 1,
                confirmedWins = state.confirmedWins + 1,
                consecutiveWins = martingale.consecutiveWins,
                consecutiveLosses = 0,
                dailyLoss = risk.dailyLoss,
                currentAmount = martingale.currentAmount,
                martingaleDoublings = martingale.doublingsUsed,
                lastConfirmedResult = "ВЫИГРЫШ",
                tradeCycleState = TradeCycleState.COMPLETED,
            ))
            return true
        }

        val update = martingale.onConfirmedLoss()
        check(risk.recordConfirmedLoss(amount))
        updateState(state.copy(
            confirmedTrades = state.confirmedTrades + 1,
            confirmedLosses = state.confirmedLosses + 1,
            consecutiveLosses = update.consecutiveLosses,
            currentAmount = update.nextAmount,
            dailyLoss = risk.dailyLoss,
            martingaleDoublings = update.doublingsUsed,
            lastConfirmedResult = "ПРОИГРЫШ",
            tradeCycleState = TradeCycleState.COMPLETED,
        ))
        if (risk.consecutiveLossLimitReached) {
            stopEmergency(
                "АВАРИЙНАЯ ОСТАНОВКА!\nОбнаружено 3 последовательных проигрыша.\n" +
                    "Автоматическая торговля остановлена для защиты баланса.\n" +
                    "Для продолжения необходимо ваше подтверждение.",
            )
            return true
        }
        if (risk.dailyLossLimitReached) {
            stopEmergency("Достигнут лимит дневного убытка")
            return true
        }
        return true
    }

    private fun blockUnconfirmedResult(reason: String) {
        nextCycleJob?.cancel()
        nextCycleJob = null
        captureTimeoutJob?.cancel()
        captureTimeoutJob = null
        analysisJob?.cancel()
        analysisJob = null
        cycleCoordinator.stop()
        if (projectionSessionStarted) stopCaptureAction?.invoke()
        captureInProgress = false
        projectionSessionStarted = false
        projectionAvailable = false
        mediaProjectionResultCode = null
        mediaProjectionResultData = null
        updateState(state.copy(
            running = false,
            mode = TradingMode.MANUAL,
            status = AutoTradingStatus.ERROR,
            executionStatus = ExecutionStatus.RESULT_NOT_CONFIRMED,
            lastConfirmedResult = verifier.notConfirmed(),
            tradeCycleState = TradeCycleState.BLOCKED_UNCONFIRMED,
            lastSkipReason = reason,
        ))
    }

    fun startCaptureCycle() {
        if (!state.running || analysisJob?.isActive == true) return
        val reason = risk.canContinue(state.confirmedTrades)
        if (reason != null) {
            onCaptureFailed(reason)
            return
        }
        if (!projectionAvailable || (!projectionSessionStarted &&
                (mediaProjectionResultCode == null || mediaProjectionResultData == null))
        ) {
            onCaptureFailed("Разрешение MediaProjection не получено или сессия захвата потеряна")
            return
        }
        if (!cycleCoordinator.beginCycle()) return
        captureInProgress = true
        captureTimeoutJob?.cancel()
        captureTimeoutJob = scope.launch {
            delay(CAPTURE_TIMEOUT_MS)
            if (captureInProgress) {
                onCaptureFailed("Служба захвата не завершила цикл вовремя")
            }
        }
        updateState(state.copy(status = AutoTradingStatus.CHECKING_SCREEN, captureState = "ПОДГОТОВКА"))
        val action = captureAction ?: run {
            captureInProgress = false
            cycleCoordinator.stop()
            updateState(state.copy(running = false, status = AutoTradingStatus.ERROR,
                lastSkipReason = "Механизм захвата экрана не настроен"))
            return
        }
        scope.launch(Dispatchers.Main) {
            updateState(state.copy(status = AutoTradingStatus.CAPTURING, captureState = "ОДИНОЧНЫЙ КАДР"))
            if (projectionSessionStarted) {
                action(Intent().putExtra(EXTRA_NEXT_CYCLE, true))
            } else {
                val resultCode = mediaProjectionResultCode ?: run {
                    onCaptureFailed("Код разрешения MediaProjection отсутствует")
                    return@launch
                }
                val resultData = mediaProjectionResultData ?: run {
                    onCaptureFailed("Данные разрешения MediaProjection отсутствуют")
                    return@launch
                }
                projectionSessionStarted = true
                mediaProjectionResultCode = null
                mediaProjectionResultData = null
                action(Intent().putExtra(EXTRA_RESULT_CODE, resultCode)
                    .putExtra(EXTRA_RESULT_DATA, resultData)
                    .putExtra(EXTRA_CONTINUOUS_SESSION, true))
            }
        }
    }

    fun onCaptureCompleted(report: AnalysisReport?) {
        captureInProgress = false
        captureTimeoutJob?.cancel()
        captureTimeoutJob = null
        if (!state.running) return
        if (analysisJob?.isActive == true) return
        if (report == null) {
            recordSkip("Захват или обработка кадров не завершилась")
            scheduleNextCycle()
            return
        }
        analysisJob = scope.launch {
            updateState(state.copy(
                status = AutoTradingStatus.ANALYZING,
                instrument = report.instrument,
                currentPrice = report.price,
                lastAnalysisTimeMillis = report.prediction?.analysisTimeMillis ?: System.currentTimeMillis(),
                forecast = report.prediction?.direction ?: state.forecast,
                confidence = report.prediction?.confidence ?: state.confidence,
                expirationSeconds = report.prediction?.expiration?.seconds ?: state.expirationSeconds,
                buyButtonState = report.buyButton.message,
                sellButtonState = report.sellButton.message,
                captureState = "ЗАХВАТ ЗАВЕРШЁН",
            ))
            val preflight = safety.preflight(
                report = report,
                projectionAvailable = projectionAvailable,
                emergencyStopped = state.status == AutoTradingStatus.EMERGENCY_STOPPED,
                runningTrades = false,
                completedTrades = state.confirmedTrades,
                currentAmount = state.currentAmount,
            )
            if (preflight != null) {
                recordSkip(preflight)
                scheduleNextCycle()
                return@launch
            }
            val prediction = report.prediction
            if (prediction == null) {
                recordSkip("Аналитический прогноз не сформирован")
                scheduleNextCycle()
                return@launch
            }
            updateState(state.copy(status = AutoTradingStatus.DETERMINING_DIRECTION))
            if (prediction.direction == PredictionDirection.NO_TRADE) {
                recordSkip(prediction.reasons.joinToString("; ").ifBlank { "Недостаточно подтвержденных признаков" })
                scheduleNextCycle()
                return@launch
            }
            val entryDecision = determineEntryDecision()
            updateState(state.copy(entryDecision = entryDecision))
            if (entryDecision != EntryDecision.ENTER_NOW) {
                recordSkip(
                    when (entryDecision) {
                        EntryDecision.WAIT_FOR_CANDLE_CLOSE -> "Ожидание закрытия распознанной свечи"
                        EntryDecision.ENTER_AT_NEXT_CANDLE -> "Ожидание начала следующей свечи"
                        EntryDecision.WAIT_FOR_CONFIRMATION ->
                            "Тип распознанного таймера не подтверждён как таймер свечи"
                        else -> "Момент входа не подтверждён"
                    },
                )
                scheduleNextCycle()
                return@launch
            }
            updateState(state.copy(status = AutoTradingStatus.SELECTING_EXPIRATION))
            val expiration = prediction.expiration.seconds
            if (expiration == null || expiration !in 15..180) {
                recordSkip("Экспирация не определена надёжно")
                scheduleNextCycle()
                return@launch
            }
            updateState(state.copy(status = AutoTradingStatus.SAFETY_CHECK))
            val buttonBlockReason = safety.validateButtons(report, prediction.direction)
            if (buttonBlockReason != null) {
                recordSkip(buttonBlockReason)
                scheduleNextCycle()
                return@launch
            }
            val command = safety.createCommand(
                report,
                prediction.direction,
                expiration,
                state.currentAmount,
            )
            if (command == null) {
                recordSkip("Ценная цена, валютная пара или сумма не подтверждены")
                scheduleNextCycle()
                return@launch
            }
            if (!safety.isDuplicate(command.id)) {
                val status = execution.prepare(command)
                updateState(state.copy(
                    status = AutoTradingStatus.EXECUTION_UNAVAILABLE,
                    executionStatus = status,
                    lastSkipReason = "Автоматическое исполнение недоступно",
                ))
            } else {
                recordSkip("Ошибка защиты от дублирующей команды")
                scheduleNextCycle()
                return@launch
            }
            updateState(state.copy(
                completedCycles = state.completedCycles + 1,
                status = AutoTradingStatus.EXECUTION_UNAVAILABLE,
                executionStatus = ExecutionStatus.BLOCKED_NO_APPROVED_MECHANISM,
                lastSkipReason = "АВТОМАТИЧЕСКОЕ ИСПОЛНЕНИЕ НЕДОСТУПНО",
                lastConfirmedResult = verifier.notConfirmed(),
            ))
            scheduleNextCycle()
        }
    }

    fun onCaptureFailed(resourceMessage: String) {
        if (!state.running) return
        recordSkip(resourceMessage)
        nextCycleJob?.cancel()
        nextCycleJob = null
        captureTimeoutJob?.cancel()
        captureTimeoutJob = null
        analysisJob?.cancel()
        analysisJob = null
        cycleCoordinator.stop()
        if (projectionSessionStarted) stopCaptureAction?.invoke()
        captureInProgress = false
        projectionSessionStarted = false
        projectionAvailable = false
        mediaProjectionResultCode = null
        mediaProjectionResultData = null
        updateState(state.copy(
            running = false,
            status = AutoTradingStatus.ERROR,
            captureState = "ОШИБКА ЗАХВАТА",
            lastSkipReason = resourceMessage,
        ))
    }

    private fun recordSkip(reason: String) {
        updateState(state.copy(
            completedCycles = state.completedCycles + 1,
            skippedTrades = state.skippedTrades + 1,
            status = AutoTradingStatus.TRADE_SKIPPED,
            captureState = "ЗАХВАТ ЗАВЕРШЁН",
            lastSkipReason = reason,
            executionStatus = ExecutionStatus.NOT_ATTEMPTED,
        ))
    }

    private fun determineEntryDecision(): EntryDecision = EntryDecision.WAIT_FOR_CONFIRMATION

    private fun scheduleNextCycle() {
        if (!state.running) return
        if (!projectionSessionStarted || !projectionAvailable) {
            onCaptureFailed("Сессия захвата завершилась; для продолжения требуется новое разрешение")
            return
        }
        if (!cycleCoordinator.finishCycle()) return
        nextCycleJob?.cancel()
        nextCycleJob = scope.launch {
            updateState(state.copy(status = AutoTradingStatus.WAITING_NEXT,
                captureState = "ОЖИДАНИЕ СЛЕДУЮЩЕГО ЦИКЛА"))
            delay(CYCLE_DELAY_MS)
            if (state.running) startCaptureCycle()
        }
    }

    private fun updateState(newState: TradingSessionState) {
        val previous = state
        state = newState
        val criticalChange =
            (!previous.emergencyLocked && newState.emergencyLocked) ||
                (previous.emergencyLocked && !newState.emergencyLocked) ||
                newState.tradeCycleState == TradeCycleState.BLOCKED_UNCONFIRMED ||
                newState.confirmedTrades > previous.confirmedTrades
        persistState(durable = criticalChange)
        stateListener?.invoke(state)
    }

    private fun persistState(durable: Boolean = false) {
        val snapshot = state
        val revision = ++persistenceRevision
        val serialized = serializeState(snapshot)
        if (durable) {
            try {
                writeState(revision, serialized)
            } catch (error: IOException) {
                stateListener?.invoke(snapshot.copy(
                    lastSkipReason = "Не удалось надёжно сохранить состояние: ${error.message ?: "ошибка записи"}",
                ))
            }
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                writeState(revision, serialized)
            } catch (error: IOException) {
                scope.launch {
                    stateListener?.invoke(state.copy(
                        lastSkipReason = "Не удалось сохранить состояние: ${error.message ?: "ошибка записи"}",
                    ))
                }
            }
        }
    }

    private fun serializeState(snapshot: TradingSessionState): String =
        JSONObject()
            .put("running", snapshot.running)
            .put("mode", snapshot.mode.name)
            .put("status", snapshot.status.name)
            .put("instrument", snapshot.instrument)
            .put("currentPrice", snapshot.currentPrice)
            .put("lastAnalysisTimeMillis", snapshot.lastAnalysisTimeMillis ?: JSONObject.NULL)
            .put("forecast", snapshot.forecast.name)
            .put("confidence", snapshot.confidence)
            .put("expirationSeconds", snapshot.expirationSeconds ?: JSONObject.NULL)
            .put("captureState", snapshot.captureState)
            .put("buyButtonState", snapshot.buyButtonState)
            .put("sellButtonState", snapshot.sellButtonState)
            .put("executionStatus", snapshot.executionStatus.name)
            .put("completedCycles", snapshot.completedCycles)
            .put("skippedTrades", snapshot.skippedTrades)
            .put("confirmedTrades", snapshot.confirmedTrades)
            .put("lastSkipReason", snapshot.lastSkipReason)
            .put("lastConfirmedResult", snapshot.lastConfirmedResult)
            .put("emergencyReason", snapshot.emergencyReason ?: JSONObject.NULL)
            .put("emergencyLocked", snapshot.emergencyLocked)
            .put("initialAmount", snapshot.initialAmount)
            .put("currentAmount", snapshot.currentAmount)
            .put("maxAmount", snapshot.maxAmount)
            .put("dailyLoss", snapshot.dailyLoss)
            .put("maxDailyLoss", snapshot.maxDailyLoss)
            .put("consecutiveLosses", snapshot.consecutiveLosses)
            .put("consecutiveWins", snapshot.consecutiveWins)
            .put("confirmedWins", snapshot.confirmedWins)
            .put("confirmedLosses", snapshot.confirmedLosses)
            .put("confirmedDraws", snapshot.confirmedDraws)
            .put("martingaleEnabled", snapshot.martingaleEnabled)
            .put("martingaleAllowedByUser", snapshot.martingaleAllowedByUser)
            .put("martingaleDoublings", snapshot.martingaleDoublings)
            .put("maxMartingaleDoublings", snapshot.maxMartingaleDoublings)
            .put("entryDecision", snapshot.entryDecision.name)
            .put("tradeCycleState", snapshot.tradeCycleState.name)
            .toString(2)

    private fun writeState(revision: Long, serialized: String) {
        synchronized(stateFile) {
            if (revision < persistedRevision) return
            val output = atomicStateFile.startWrite()
            try {
                output.write(serialized.toByteArray(Charsets.UTF_8))
                output.fd.sync()
                atomicStateFile.finishWrite(output)
                persistedRevision = revision
            } catch (error: IOException) {
                atomicStateFile.failWrite(output)
                throw error
            }
        }
    }

    private fun loadState(): TradingSessionState {
        if (!stateFile.exists() && !File("${stateFile.path}.bak").exists()) {
            return TradingSessionState(
                initialAmount = limits.initialTradeAmount,
                currentAmount = limits.initialTradeAmount,
                maxAmount = limits.maxTradeAmount,
                maxDailyLoss = limits.maxDailyLoss,
                maxMartingaleDoublings = limits.maxMartingaleDoublings,
            )
        }
        return try {
            val root = JSONObject(
                atomicStateFile.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() },
            )
            val emergencyLocked = root.optBoolean("emergencyLocked", false)
            val storedMode = when (root.optString("mode", "MANUAL")) {
                "MANUAL", "DEMO" -> TradingMode.MANUAL
                "AUTO", "REAL" -> TradingMode.AUTO
                else -> throw IllegalArgumentException("Unknown stored trading mode")
            }
            TradingSessionState(
                running = false,
                mode = if (emergencyLocked) TradingMode.MANUAL else storedMode,
                status = if (emergencyLocked) AutoTradingStatus.EMERGENCY_STOPPED
                    else AutoTradingStatus.STOPPED,
                instrument = root.optString("instrument", "НЕ ОПРЕДЕЛЕНО"),
                currentPrice = root.optString("currentPrice", "НЕ ОПРЕДЕЛЕНО"),
                lastAnalysisTimeMillis = root.opt("lastAnalysisTimeMillis")
                    .takeIf { it is Number }
                    ?.let { (it as Number).toLong() },
                forecast = PredictionDirection.valueOf(root.optString("forecast", "NO_TRADE")),
                confidence = root.optDouble("confidence", 0.0),
                expirationSeconds = root.opt("expirationSeconds").takeIf { it is Number }?.let { (it as Number).toInt() },
                captureState = "НЕ АКТИВЕН",
                buyButtonState = root.optString("buyButtonState", "НЕ ОПРЕДЕЛЕНО"),
                sellButtonState = root.optString("sellButtonState", "НЕ ОПРЕДЕЛЕНО"),
                executionStatus = ExecutionStatus.valueOf(root.optString("executionStatus", "NOT_ATTEMPTED")),
                completedCycles = root.optInt("completedCycles", 0),
                skippedTrades = root.optInt("skippedTrades", 0),
                confirmedTrades = root.optInt("confirmedTrades", 0),
                lastSkipReason = root.optString("lastSkipReason", "ПРОПУСК ЕЩЁ НЕ ВЫПОЛНЕН"),
                lastConfirmedResult = root.optString("lastConfirmedResult", "РЕЗУЛЬТАТ НЕ ПОДТВЕРЖДЁН"),
                emergencyReason = if (emergencyLocked)
                    root.optString("emergencyReason", "Аварийная остановка сохранена")
                else null,
                emergencyLocked = emergencyLocked,
                initialAmount = root.optDouble("initialAmount", limits.initialTradeAmount),
                currentAmount = root.optDouble("currentAmount", limits.initialTradeAmount),
                maxAmount = root.optDouble("maxAmount", limits.maxTradeAmount),
                dailyLoss = root.optDouble("dailyLoss", 0.0),
                maxDailyLoss = root.optDouble("maxDailyLoss", limits.maxDailyLoss),
                consecutiveLosses = root.optInt("consecutiveLosses", 0),
                consecutiveWins = root.optInt("consecutiveWins", 0),
                confirmedWins = root.optInt("confirmedWins", 0),
                confirmedLosses = root.optInt("confirmedLosses", 0),
                confirmedDraws = root.optInt("confirmedDraws", 0),
                martingaleEnabled = root.optBoolean("martingaleEnabled", false),
                martingaleAllowedByUser = root.optBoolean("martingaleAllowedByUser", false),
                martingaleDoublings = root.optInt("martingaleDoublings", 0),
                maxMartingaleDoublings = root.optInt("maxMartingaleDoublings", limits.maxMartingaleDoublings),
                entryDecision = EntryDecision.valueOf(root.optString("entryDecision", "NO_TRADE")),
                tradeCycleState = TradeCycleState.valueOf(root.optString("tradeCycleState", "NONE")),
            )
        } catch (error: IOException) {
            corruptedState("Не удалось прочитать сохранённое состояние: ${error.message ?: "ошибка ввода-вывода"}")
        } catch (error: JSONException) {
            corruptedState("Файл состояния повреждён: ${error.message ?: "неверный JSON"}")
        } catch (error: IllegalArgumentException) {
            corruptedState("Файл состояния содержит недопустимые значения: ${error.message ?: "проверка не пройдена"}")
        } catch (error: SecurityException) {
            corruptedState("Нет доступа к файлу состояния: ${error.message ?: "доступ запрещён"}")
        }
    }

    private fun corruptedState(reason: String) = TradingSessionState(
        running = false,
        mode = TradingMode.MANUAL,
        status = AutoTradingStatus.EMERGENCY_STOPPED,
        executionStatus = ExecutionStatus.BLOCKED_SAFETY,
        emergencyReason = reason,
        emergencyLocked = true,
        lastSkipReason = reason,
        tradeCycleState = TradeCycleState.BLOCKED_UNCONFIRMED,
        initialAmount = limits.initialTradeAmount,
        currentAmount = limits.initialTradeAmount,
        maxAmount = limits.maxTradeAmount,
        maxDailyLoss = limits.maxDailyLoss,
        maxMartingaleDoublings = limits.maxMartingaleDoublings,
    )

    companion object {
        private const val EXTRA_NEXT_CYCLE = "next_cycle"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val EXTRA_CONTINUOUS_SESSION = "continuous_session"
        private const val CYCLE_DELAY_MS = 5_000L
        private const val CAPTURE_TIMEOUT_MS = 30_000L
    }
}
