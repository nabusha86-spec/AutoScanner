package com.autoscanner.trader.analytics

class IndicatorAnalysisEngine {
    fun analyze(names: List<String>): List<IndicatorResult> =
        names.distinct().map { rawName ->
            val name = rawName.uppercase()
            val recognized = name.contains("RSI") || name.contains("MACD") ||
                name.contains("EMA") || name.contains("SMA") ||
                name.contains("СКОЛЬЗЯЩ") || name.contains("БОЛЛИНДЖЕР") ||
                name.contains("СТОХАСТ")
            val kind = when {
                name.contains("RSI") -> "RSI"
                name.contains("MACD") -> "MACD"
                name.contains("БОЛЛИНДЖЕР") -> "Bollinger Bands"
                name.contains("СТОХАСТ") -> "Stochastic"
                name.contains("EMA") -> "EMA"
                name.contains("SMA") -> "SMA"
                name.contains("СКОЛЬЗЯЩ") || name.contains("MA") -> "Moving Average"
                else -> rawName
            }
            IndicatorResult(
                name = kind,
                recognized = recognized,
                exactValue = null,
                visualApproximation = if (recognized) "Название индикатора распознано; числовое значение недоступно" else null,
                direction = DirectionInfluence.NEUTRAL,
                state = "НЕ ОПРЕДЕЛЕНО",
                confidence = if (recognized) 0.55 else 0.0,
            )
        }.ifEmpty { emptyList() }
}
