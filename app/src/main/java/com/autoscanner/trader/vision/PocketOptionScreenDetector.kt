package com.autoscanner.trader.vision

class PocketOptionScreenDetector(
    private val strings: VisionStrings,
) {
    fun detect(
        chart: ChartAnalysis,
        instrument: String,
        price: String,
        timeframe: String,
        timer: String,
        buttons: List<TradingButton>,
    ): Boolean {
        if (!chart.detected || chart.candles.size < MIN_CHART_CANDLES) return false
        val recognizedButton = buttons.any { it.detected && !it.ambiguous }
        val structuredMarketData = instrument != strings.unknown &&
            (price != strings.unknown || timeframe != strings.unknown || timer != strings.unknown)
        return recognizedButton && (structuredMarketData || buttons.count { it.detected } == 2)
    }

    private companion object {
        const val MIN_CHART_CANDLES = 3
    }
}
