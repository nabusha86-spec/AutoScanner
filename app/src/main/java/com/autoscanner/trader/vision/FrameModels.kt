package com.autoscanner.trader.vision

import com.autoscanner.trader.analytics.PredictionResult

data class TextBox(
    val text: String,
    val confidence: Float,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

data class ImageQuality(
    val width: Int,
    val height: Int,
    val sharpness: Double?,
    val brightness: Double?,
    val contrast: Double?,
    val isAdequate: Boolean,
    val warning: String?,
)

data class Candle(
    val index: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val wickTop: Int,
    val wickBottom: Int,
    val color: String,
    val closeY: Int,
)

data class ChartAnalysis(
    val detected: Boolean,
    val left: Int?,
    val top: Int?,
    val right: Int?,
    val bottom: Int?,
    val candles: List<Candle>,
    val movement: String,
    val changedPercent: Double?,
)

data class TradingButton(
    val kind: String,
    val detected: Boolean,
    val text: String,
    val left: Int?,
    val top: Int?,
    val right: Int?,
    val bottom: Int?,
    val confidence: Float,
    val state: String,
    val ambiguous: Boolean,
    val message: String,
)

data class FrameAnalysis(
    val sequence: Int,
    val timestampMillis: Long,
    val width: Int,
    val height: Int,
    val quality: ImageQuality,
    val tradingScreenDetected: Boolean,
    val instrument: String,
    val price: String,
    val amount: String,
    val timeframe: String,
    val timer: String,
    val expiration: String,
    val chart: ChartAnalysis,
    val buttons: List<TradingButton>,
    val indicators: List<String>,
    val otherElements: List<String>,
    val textBoxes: List<TextBox>,
    val errors: List<String>,
)

data class AnalysisReport(
    val processedFrames: Int,
    val tradingScreenDetected: Boolean,
    val instrument: String,
    val price: String,
    val amount: String,
    val timeframe: String,
    val timer: String,
    val expiration: String,
    val candles: List<Candle>,
    val marketMovement: String,
    val indicators: List<String>,
    val buyButton: TradingButton,
    val sellButton: TradingButton,
    val imageQuality: String,
    val warnings: List<String>,
    val errors: List<String>,
    val frames: List<FrameAnalysis>,
    val reportPath: String,
    val prediction: PredictionResult?,
)
