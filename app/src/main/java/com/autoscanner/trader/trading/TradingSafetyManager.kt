package com.autoscanner.trader.trading

import com.autoscanner.trader.analytics.PredictionDirection
import com.autoscanner.trader.vision.AnalysisReport
import java.util.UUID

class TradingSafetyManager(private val limits: TradingSafetyLimits = TradingSafetyLimits()) {
    private val executedCommandIds = mutableSetOf<String>()

    fun preflight(
        report: AnalysisReport?,
        projectionAvailable: Boolean,
        emergencyStopped: Boolean,
        runningTrades: Boolean,
        completedTrades: Int,
        currentAmount: Double? = null,
    ): String? {
        if (emergencyStopped) return "Нажата аварийная остановка"
        if (!projectionAvailable) return "Разрешение MediaProjection недоступно"
        if (runningTrades) return "Есть уже открытая сделка"
        if (completedTrades >= limits.maxTradesPerSession) return "Достигнут лимит сделок за сессию"
        if (report == null) return "Результат захвата недоступен"
        if (!report.tradingScreenDetected) return "ТОРГОВЫЙ ИНТЕРФЕЙС НЕ РАСПОЗНАН"
        val adequate = report.frames.count { it.quality.isAdequate }
        if (adequate == 0 || adequate * 2 < report.frames.size) return "Качество изображения недостаточно"
        if (report.instrument == "НЕ ОПРЕДЕЛЕНО") return "Валютная пара не распознана"
        val amount = currentAmount ?: parseAmount(report.amount)
        if (amount == null) return "Сумма сделки не распознана надёжно"
        if (!amount.isFinite() || amount <= 0.0) return "Сумма сделки должна быть положительной"
        if (amount > limits.maxTradeAmount) return "Сумма сделки превышает установленный лимит"
        return null
    }

    fun validateButtons(
        report: AnalysisReport,
        direction: PredictionDirection,
    ): String? {
        val buy = report.buyButton
        val sell = report.sellButton
        if (buy.ambiguous || sell.ambiguous || !buy.detected || !sell.detected) {
            return "Обе торговые кнопки должны быть однозначно распознаны"
        }
        if (!matchesButton(buy, PredictionDirection.BUY) ||
            !matchesButton(sell, PredictionDirection.SELL)
        ) return "Цвет, текст и направление кнопок противоречат друг другу"
        if (listOf(buy, sell).any {
                it.left == null || it.top == null || it.right == null || it.bottom == null
            }
        ) return "Геометрия обеих торговых кнопок не подтверждена"
        if (rectanglesOverlap(buy, sell)) return "Геометрия кнопок перекрывается"
        val frame = report.frames.lastOrNull()
        if (frame != null && (listOf(buy, sell).any { button ->
                button.left!! < 0 || button.top!! < 0 ||
                    button.right!! > frame.width || button.bottom!! > frame.height ||
                    button.left!! >= button.right!! || button.top!! >= button.bottom!!
            })
        ) return "Координаты кнопок выходят за границы кадра"

        val button = if (direction == PredictionDirection.BUY) report.buyButton else
            if (direction == PredictionDirection.SELL) report.sellButton else null
        if (direction == PredictionDirection.NO_TRADE) return "Прогноз НЕТ СДЕЛКИ"
        if (button == null) return "Кнопка для направления не определена"
        if (button.confidence < 0.40f) return "Уверенность распознавания кнопки недостаточна"
        val textExpected = if (direction == PredictionDirection.BUY)
            listOf("BUY", "ПОКУП") else listOf("SELL", "ПРОДА")
        val normalized = button.text.uppercase().replace(Regex("[^A-ZА-ЯЁ]"), "")
        if (textExpected.none { normalized.contains(it) }) return "Текст кнопки не соответствует прогнозу"
        if (button.left == null || button.top == null || button.right == null || button.bottom == null) {
            return "Геометрическое положение кнопки не подтверждено"
        }
        return null
    }

    private fun matchesButton(
        button: com.autoscanner.trader.vision.TradingButton,
        direction: PredictionDirection,
    ): Boolean {
        val normalizedText = button.text.uppercase().replace(Regex("[^A-ZА-ЯЁ]"), "")
        val normalizedKind = button.kind.uppercase().replace(Regex("[^A-ZА-ЯЁ]"), "")
        val normalizedMessage = button.message.uppercase()
        return when (direction) {
            PredictionDirection.BUY ->
                (normalizedKind.contains("ПОКУП") || normalizedKind.contains("BUY")) &&
                    (normalizedText.contains("ПОКУП") || normalizedText.contains("BUY")) &&
                    normalizedMessage.contains("ЗЕЛЁН")
            PredictionDirection.SELL ->
                (normalizedKind.contains("ПРОДА") || normalizedKind.contains("SELL")) &&
                    (normalizedText.contains("ПРОДА") || normalizedText.contains("SELL")) &&
                    normalizedMessage.contains("КРАСН")
            PredictionDirection.NO_TRADE -> false
        }
    }

    private fun rectanglesOverlap(
        first: com.autoscanner.trader.vision.TradingButton,
        second: com.autoscanner.trader.vision.TradingButton,
    ): Boolean =
        first.left!! < second.right!! &&
            first.right!! > second.left!! &&
            first.top!! < second.bottom!! &&
            first.bottom!! > second.top!!

    fun createCommand(
        report: AnalysisReport,
        direction: PredictionDirection,
        expirationSeconds: Int,
        amountOverride: Double? = null,
    ): TradingExecutionCommand? {
        if (direction == PredictionDirection.NO_TRADE || expirationSeconds !in 15..180) return null
        val instrument = report.instrument.takeIf { it != "НЕ ОПРЕДЕЛЕНО" } ?: return null
        val price = report.price.replace(',', '.').toDoubleOrNull() ?: return null
        val amount = amountOverride ?: parseAmount(report.amount) ?: return null
        if (!price.isFinite() || price <= 0.0 ||
            !amount.isFinite() || amount <= 0.0 || amount > limits.maxTradeAmount
        ) return null
        val signature = listOf(
            report.reportPath,
            instrument,
            direction.name,
            price.toString(),
            amount.toString(),
            expirationSeconds.toString(),
        ).joinToString("|")
        val id = UUID.nameUUIDFromBytes(signature.toByteArray(Charsets.UTF_8)).toString()
        return TradingExecutionCommand(id, direction, instrument, price, amount, expirationSeconds)
    }

    fun isDuplicate(commandId: String): Boolean {
        if (executedCommandIds.contains(commandId)) return true
        executedCommandIds += commandId
        return false
    }

    private fun parseAmount(raw: String): Double? {
        val normalized = raw.replace(',', '.').replace(Regex("[^0-9.]"), "")
        return normalized.toDoubleOrNull()
    }
}
