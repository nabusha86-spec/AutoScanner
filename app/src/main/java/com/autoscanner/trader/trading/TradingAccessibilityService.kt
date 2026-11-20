package com.autoscanner.trader.trading

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.autoscanner.trader.analytics.PredictionDirection
import java.util.Locale
import kotlin.math.abs

class TradingAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val attemptedCommands = mutableSetOf<String>()
    private val cancelledCommands = mutableSetOf<String>()
    private var attemptInProgress = false
    private var activeCommandId: String? = null
    private var clickIssued = false
    private var foregroundPackage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        connectedService = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        foregroundPackage = event?.packageName
            ?.toString()
            ?.takeIf(PocketOptionAccessibilityScope::allowsPackage)
    }

    override fun onInterrupt() {
        foregroundPackage = null
    }

    override fun onDestroy() {
        if (connectedService === this) connectedService = null
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun enqueueExecution(
        command: TradingExecutionCommand,
        callback: (ExecutionAttemptResult) -> Unit,
    ): Boolean = mainHandler.post {
        executeOnce(command, callback)
    }

    private fun executeOnce(
        command: TradingExecutionCommand,
        callback: (ExecutionAttemptResult) -> Unit,
    ) {
        if (cancelledCommands.remove(command.id)) {
            callback(impossible("Попытка отменена до нажатия"))
            return
        }
        if (attemptInProgress || !attemptedCommands.add(command.id)) {
            callback(impossible("Повторная или параллельная попытка заблокирована"))
            return
        }
        attemptInProgress = true
        activeCommandId = command.id
        clickIssued = false
        val root = activePocketOptionRoot()
        if (root == null) {
            finishAttempt(callback, impossible("Откройте Pocket Option в Chrome"))
            return
        }

        val nodes = safeCollectNodes(root)
        if (nodes == null) {
            finishAttempt(callback, impossible("Дерево активного окна изменилось во время проверки"))
            return
        }
        val texts = nodes.mapNotNull { it.text?.toString()?.takeIf(String::isNotBlank) }
        if (!texts.any { normalize(it).contains(POCKET_OPTION_BRAND) }) {
            finishAttempt(callback, impossible("На переднем плане не подтверждён экран Pocket Option"))
            return
        }
        if (!containsInstrument(texts, command.instrument)) {
            finishAttempt(callback, impossible("В активном интерфейсе не подтверждён выбранный инструмент"))
            return
        }
        if (!containsPrice(texts, command.price)) {
            finishAttempt(callback, impossible("Цена на активном экране изменилась или не распознана"))
            return
        }
        if (!containsAmount(nodes, command.amount)) {
            finishAttempt(callback, impossible("Поле суммы не подтверждает размер позиции"))
            return
        }
        if (!hasSelectedExpiration(nodes, command.expirationSeconds)) {
            finishAttempt(callback, impossible("Выбранная экспирация не подтверждена элементом интерфейса"))
            return
        }

        val buyButtons = findClickableButtons(nodes, BUY_LABELS)
        val sellButtons = findClickableButtons(nodes, SELL_LABELS)
        if (buyButtons.size != 1 || sellButtons.size != 1 ||
            overlaps(buyButtons.singleOrNull()?.bounds, sellButtons.singleOrNull()?.bounds)
        ) {
            finishAttempt(callback, impossible("Кнопки BUY и SELL не найдены однозначно"))
            return
        }

        val target = when (command.direction) {
            PredictionDirection.BUY -> buyButtons.single()
            PredictionDirection.SELL -> sellButtons.single()
            PredictionDirection.NO_TRADE -> {
                finishAttempt(callback, impossible("Направление сделки не определено"))
                return
            }
        }
        val clickRoot = activePocketOptionRoot()
        val clickNodes = clickRoot?.let(::safeCollectNodes)
        if (clickRoot == null || clickNodes == null || clickRoot.windowId != root.windowId) {
            finishAttempt(callback, impossible("Окно Pocket Option изменилось перед нажатием"))
            return
        }
        val clickTexts = clickNodes.mapNotNull { it.text?.toString()?.takeIf(String::isNotBlank) }
        if (!clickTexts.any { normalize(it).contains(POCKET_OPTION_BRAND) } ||
            !containsInstrument(clickTexts, command.instrument) ||
            !containsPrice(clickTexts, command.price) ||
            !containsAmount(clickNodes, command.amount) ||
            !hasSelectedExpiration(clickNodes, command.expirationSeconds)
        ) {
            finishAttempt(callback, impossible("Проверяемые данные сделки изменились перед нажатием"))
            return
        }
        val clickBuyButtons = findClickableButtons(clickNodes, BUY_LABELS)
        val clickSellButtons = findClickableButtons(clickNodes, SELL_LABELS)
        if (clickBuyButtons.size != 1 || clickSellButtons.size != 1 ||
            overlaps(clickBuyButtons.singleOrNull()?.bounds, clickSellButtons.singleOrNull()?.bounds)
        ) {
            finishAttempt(callback, impossible("Кнопки BUY и SELL изменились перед нажатием"))
            return
        }
        val clickTarget = when (command.direction) {
            PredictionDirection.BUY -> clickBuyButtons.single()
            PredictionDirection.SELL -> clickSellButtons.single()
            PredictionDirection.NO_TRADE -> {
                finishAttempt(callback, impossible("Направление сделки не определено"))
                return
            }
        }
        if (clickTarget.bounds != target.bounds) {
            finishAttempt(callback, impossible("Кнопка сделки изменилась перед нажатием"))
            return
        }

        val beforeTexts = clickTexts.map(::normalize)
        val clicked = try {
            clickTarget.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } catch (_: IllegalStateException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
        if (!clicked) {
            finishAttempt(callback, impossible("Android отклонил действие на доступной кнопке"))
            return
        }
        clickIssued = true

        mainHandler.postDelayed({
            val afterRoot = activePocketOptionRoot()
            val afterNodes = afterRoot?.let(::safeCollectNodes)
            if (afterNodes == null) {
                finishAttempt(
                    callback,
                    ExecutionAttemptResult(
                        ExecutionStatus.ACTION_PERFORMED_ACCEPTANCE_UNCONFIRMED,
                        "Нажатие выполнено, но дерево интерфейса после действия недоступно",
                    ),
                )
                return@postDelayed
            }
            val afterRawTexts = afterNodes.mapNotNull {
                it.text?.toString()?.takeIf(String::isNotBlank)
            }
            val afterTexts = afterRawTexts.map(::normalize)
            val afterAllText = afterTexts.joinToString("")
            val newAcceptanceLabel = afterTexts.any { text ->
                CONFIRMATION_LABELS.any(text::contains)
            } && beforeTexts.none { text ->
                CONFIRMATION_LABELS.any(text::contains)
            }
            val accepted = newAcceptanceLabel &&
                afterAllText.contains(normalize(command.instrument)) &&
                directionLabels(command.direction).any(afterAllText::contains) &&
                afterRawTexts.any { containsAmountText(it, command.amount) }
            finishAttempt(
                callback,
                if (accepted) {
                    ExecutionAttemptResult(
                        ExecutionStatus.PLATFORM_ACCEPTANCE_CONFIRMED,
                        "Интерфейс показал новое подтверждение действия; результат сделки ещё не проверен",
                    )
                } else {
                    ExecutionAttemptResult(
                        ExecutionStatus.ACTION_PERFORMED_ACCEPTANCE_UNCONFIRMED,
                        "Android выполнил нажатие, но принятие действия платформой не подтверждено",
                    )
                },
            )
        }, ACCEPTANCE_CHECK_DELAY_MS)
    }

    private fun finishAttempt(
        callback: (ExecutionAttemptResult) -> Unit,
        result: ExecutionAttemptResult,
    ) {
        attemptInProgress = false
        activeCommandId = null
        clickIssued = false
        callback(result)
    }

    private fun cancelExecution(commandId: String) {
        if (activeCommandId == commandId && clickIssued) return
        cancelledCommands += commandId
    }

    private fun impossible(reason: String) =
        ExecutionAttemptResult(ExecutionStatus.EXECUTION_IMPOSSIBLE, reason)

    private fun activeWindowRoot(): AccessibilityNodeInfo? = try {
        rootInActiveWindow
    } catch (_: IllegalStateException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun activePocketOptionRoot(): AccessibilityNodeInfo? {
        val expectedPackage = foregroundPackage
            ?.takeIf(PocketOptionAccessibilityScope::allowsPackage)
            ?: return null
        val root = activeWindowRoot() ?: return null
        if (root.packageName?.toString() != expectedPackage || !hasPocketOptionAddress(root)) {
            return null
        }
        return root
    }

    private fun hasPocketOptionAddress(root: AccessibilityNodeInfo): Boolean = try {
        root.findAccessibilityNodeInfosByViewId(
            PocketOptionAccessibilityScope.CHROME_ADDRESS_BAR_VIEW_ID,
        ).orEmpty().any { addressNode ->
            addressNode.packageName?.toString() == PocketOptionAccessibilityScope.CHROME_PACKAGE &&
                sequenceOf(addressNode.text, addressNode.contentDescription)
                    .filterNotNull()
                    .any(PocketOptionAccessibilityScope::allowsAddress)
        }
    } catch (_: IllegalStateException) {
        false
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun safeCollectNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo>? = try {
        collectNodes(root)
    } catch (_: IllegalStateException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun collectNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        val pending = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        pending.add(root to 0)
        while (pending.isNotEmpty() && nodes.size < MAX_ACCESSIBLE_NODES) {
            val (node, depth) = pending.removeFirst()
            nodes += node
            if (depth >= MAX_NODE_DEPTH) continue
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { pending.add(it to depth + 1) }
            }
        }
        return nodes
    }

    private fun findClickableButtons(
        nodes: List<AccessibilityNodeInfo>,
        labels: Set<String>,
    ): List<ButtonCandidate> {
        val found = linkedMapOf<String, ButtonCandidate>()
        nodes.forEach { node ->
            val text = sequenceOf(node.text, node.contentDescription)
                .filterNotNull()
                .map { normalize(it.toString()) }
                .firstOrNull(labels::contains)
                ?: return@forEach
            if (!node.isVisibleToUser || text !in labels) return@forEach
            var clickable: AccessibilityNodeInfo? = node
            var parentDepth = 0
            while (clickable != null && !clickable.isClickable && parentDepth < MAX_CLICKABLE_ANCESTORS) {
                clickable = clickable.parent
                parentDepth += 1
            }
            val target = clickable?.takeIf { it.isClickable && it.isEnabled && it.isVisibleToUser }
                ?: return@forEach
            val bounds = Rect().also(target::getBoundsInScreen)
            if (bounds.isEmpty) return@forEach
            val key = "${bounds.left}:${bounds.top}:${bounds.right}:${bounds.bottom}:$text"
            found.putIfAbsent(key, ButtonCandidate(target, bounds))
        }
        return found.values.toList()
    }

    private fun containsInstrument(texts: List<String>, instrument: String): Boolean {
        val expected = normalize(instrument)
        return expected.isNotEmpty() && texts.any { normalize(it).contains(expected) }
    }

    private fun containsPrice(texts: List<String>, expected: Double): Boolean =
        texts.count { text ->
            PRICE_PATTERN.findAll(text.replace(',', '.')).any { match ->
                match.value.toDoubleOrNull()?.let { abs(it - expected) <= PRICE_TOLERANCE } == true
            }
        } == 1

    private fun containsAmount(nodes: List<AccessibilityNodeInfo>, expected: Double): Boolean =
        nodes.filter { it.isEditable && it.isVisibleToUser }
            .mapNotNull { it.text?.toString()?.let(::parseAmount) }
            .count { abs(it - expected) <= AMOUNT_TOLERANCE } == 1

    private fun containsAmountText(text: String, expected: Double): Boolean =
        AMOUNT_PATTERN.findAll(text.replace(',', '.'))
            .mapNotNull { it.value.toDoubleOrNull() }
            .any { abs(it - expected) <= AMOUNT_TOLERANCE }

    private fun hasSelectedExpiration(
        nodes: List<AccessibilityNodeInfo>,
        seconds: Int,
    ): Boolean {
        if (seconds !in EXPIRATIONS_SECONDS) return false
        val expected = normalize(formatExpiration(seconds))
        return nodes.any { node ->
            val value = sequenceOf(node.text, node.contentDescription)
                .filterNotNull()
                .any { normalize(it.toString()) == expected }
            value && node.isVisibleToUser &&
                (node.isSelected || node.isChecked || hasSelectedAncestor(node))
        }
    }

    private fun hasSelectedAncestor(node: AccessibilityNodeInfo): Boolean {
        var parent = node.parent
        repeat(MAX_CLICKABLE_ANCESTORS) {
            if (parent == null) return false
            if (parent.isSelected || parent.isChecked) return true
            parent = parent.parent
        }
        return false
    }

    private fun overlaps(first: Rect?, second: Rect?): Boolean =
        first != null && second != null && Rect.intersects(first, second)

    private fun formatExpiration(seconds: Int): String =
        "%02d:%02d".format(Locale.US, seconds / 60, seconds % 60)

    private fun parseAmount(raw: String): Double? =
        AMOUNT_PATTERN.find(raw.replace(',', '.'))?.value?.toDoubleOrNull()

    private fun directionLabels(direction: PredictionDirection): Set<String> = when (direction) {
        PredictionDirection.BUY -> setOf("BUY", "ПОКУПКА", "КУПИТЬ")
        PredictionDirection.SELL -> setOf("SELL", "ПРОДАЖА", "ПРОДАТЬ")
        PredictionDirection.NO_TRADE -> emptySet()
    }

    private fun normalize(value: CharSequence): String =
        value.toString().uppercase(Locale.ROOT).replace(Regex("[^A-ZА-ЯЁ0-9]"), "")

    private data class ButtonCandidate(
        val node: AccessibilityNodeInfo,
        val bounds: Rect,
    )

    companion object {
        private const val POCKET_OPTION_BRAND = "POCKETOPTION"
        private const val ACCEPTANCE_CHECK_DELAY_MS = 1_200L
        private const val MAX_ACCESSIBLE_NODES = 1_000
        private const val MAX_NODE_DEPTH = 32
        private const val MAX_CLICKABLE_ANCESTORS = 4
        private const val PRICE_TOLERANCE = 0.0000001
        private const val AMOUNT_TOLERANCE = 0.000001
        private val PRICE_PATTERN = Regex("""\d{1,8}[.,]\d{2,10}""")
        private val AMOUNT_PATTERN = Regex("""\d{1,8}(?:\.\d{1,4})?""")
        private val EXPIRATIONS_SECONDS = setOf(15, 30, 60, 120, 180)
        private val BUY_LABELS = setOf("BUY", "ПОКУПКА", "КУПИТЬ")
        private val SELL_LABELS = setOf("SELL", "ПРОДАЖА", "ПРОДАТЬ")
        private val CONFIRMATION_LABELS = setOf(
            "TRADEOPENED",
            "DEALOPENED",
            "POSITIONOPENED",
            "СДЕЛКАОТКРЫТА",
            "ПОЗИЦИЯОТКРЫТА",
        )

        @Volatile
        private var connectedService: TradingAccessibilityService? = null

        fun requestExecution(
            command: TradingExecutionCommand,
            callback: (ExecutionAttemptResult) -> Unit,
        ): Boolean = connectedService?.enqueueExecution(command, callback) == true

        fun cancelExecution(commandId: String) {
            connectedService?.cancelExecution(commandId)
        }
    }
}
