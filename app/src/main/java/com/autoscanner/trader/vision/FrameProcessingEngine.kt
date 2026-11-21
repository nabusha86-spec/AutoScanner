package com.autoscanner.trader.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.autoscanner.trader.R
import com.autoscanner.trader.analytics.AnalyticsCoordinator
import com.autoscanner.trader.analytics.DirectionInfluence
import com.autoscanner.trader.analytics.EngineAssessment
import com.autoscanner.trader.analytics.ForecastHistoryStore
import com.autoscanner.trader.analytics.RiskFactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.android.OpenCVLoader
import java.io.File
import java.io.IOException
import kotlin.math.abs

class FrameProcessingEngine(context: Context) {
    private val appContext = context.applicationContext
    private val strings = VisionStrings(appContext)

    suspend fun processSequence(
        directoryPath: String,
        screenshotBitmap: Bitmap? = null,
    ): AnalysisReport = withContext(Dispatchers.IO) {
        try {
            processSequenceInternal(directoryPath, screenshotBitmap)
        } finally {
            screenshotBitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
        }
    }

    private suspend fun processSequenceInternal(
        directoryPath: String,
        screenshotBitmap: Bitmap?,
    ): AnalysisReport {
        val directory = File(directoryPath)
        if (!directory.isDirectory) throw IOException(strings.get(R.string.vision_error_capture_directory))
        val frameFiles = directory.listFiles { file ->
            file.isFile && FRAME_FILE_REGEX.matches(file.name)
        }?.sortedBy { FRAME_FILE_REGEX.matchEntire(it.name)?.groupValues?.get(1)?.toIntOrNull() }
            .orEmpty()
        if (frameFiles.isEmpty()) throw IOException(strings.get(R.string.vision_error_no_frames))

        val errors = mutableListOf<String>()
        val openCvAvailable = try {
            OpenCVLoader.initLocal()
        } catch (_: RuntimeException) {
            errors += strings.get(R.string.vision_error_opencv_init)
            false
        } catch (_: LinkageError) {
            errors += strings.get(R.string.vision_error_opencv_native)
            false
        }
        val openCvProcessor = if (openCvAvailable) OpenCVProcessor(strings) else null
        val qualityAnalyzer = if (openCvAvailable) ImageQualityAnalyzer(strings) else null
        val interfaceRecognizer = TradingInterfaceRecognitionEngine(strings)
        val frames = ArrayList<FrameAnalysis>(frameFiles.size)
        var ocrProcessor: OCRProcessor? = null
        var previousBitmap: Bitmap? = null
        val coroutineContext = currentCoroutineContext()
        try {
            try {
                ocrProcessor = OCRProcessor(appContext)
            } catch (error: IOException) {
                errors += strings.get(R.string.vision_error_ocr_models)
            } catch (_: RuntimeException) {
                errors += strings.get(R.string.vision_error_ocr_init)
            } catch (_: UnsatisfiedLinkError) {
                errors += strings.get(R.string.vision_error_ocr_native)
            }

            for ((fileIndex, file) in frameFiles.withIndex()) {
                coroutineContext.ensureActive()
                val sequence = FRAME_FILE_REGEX.matchEntire(file.name)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull() ?: fileIndex + 1
                val timestamp = file.lastModified().takeIf { it > 0L } ?: 0L
                val frameErrors = mutableListOf<String>()
                val bitmap = if (
                    frameFiles.size == 1 && fileIndex == 0 &&
                    screenshotBitmap != null && !screenshotBitmap.isRecycled
                ) screenshotBitmap else try {
                    BitmapFactory.decodeFile(file.absolutePath)
                } catch (_: RuntimeException) {
                    frameErrors += strings.get(R.string.vision_error_decode)
                    null
                }
                if (bitmap == null) {
                    frameErrors += strings.get(R.string.vision_error_corrupt_frame)
                    frames += failedFrame(sequence, timestamp, frameErrors)
                    continue
                }

                try {
                    val quality = if (qualityAnalyzer != null) {
                        try {
                            qualityAnalyzer.analyze(bitmap)
                        } catch (_: RuntimeException) {
                            frameErrors += strings.get(R.string.vision_error_quality)
                            unavailableQuality(bitmap)
                        } catch (_: UnsatisfiedLinkError) {
                            frameErrors += strings.get(R.string.vision_error_quality_native)
                            unavailableQuality(bitmap)
                        }
                    } else {
                        frameErrors += strings.get(R.string.vision_error_opencv_unavailable)
                        unavailableQuality(bitmap)
                    }

                    coroutineContext.ensureActive()
                    val textBoxes = ocrProcessor?.let { processor ->
                        try {
                            processor.recognize(bitmap)
                        } catch (_: RuntimeException) {
                            frameErrors += strings.get(R.string.vision_error_ocr)
                            emptyList()
                        } catch (_: UnsatisfiedLinkError) {
                            frameErrors += strings.get(R.string.vision_error_ocr_frame_native)
                            emptyList()
                        }
                    } ?: emptyList()
                    if (textBoxes.isEmpty() && ocrProcessor != null) {
                        frameErrors += strings.get(R.string.vision_warning_no_text)
                    } else if (ocrProcessor == null) {
                        frameErrors += strings.get(R.string.vision_error_ocr_unavailable)
                    }

                    coroutineContext.ensureActive()
                    val chart = if (openCvProcessor != null) {
                        try {
                            openCvProcessor.analyze(bitmap)
                        } catch (_: RuntimeException) {
                            frameErrors += strings.get(R.string.vision_error_chart)
                            unavailableChart()
                        } catch (_: UnsatisfiedLinkError) {
                            frameErrors += strings.get(R.string.vision_error_chart_native)
                            unavailableChart()
                        }
                    } else {
                        unavailableChart()
                    }
                    coroutineContext.ensureActive()
                    val interfaceResult = try {
                        interfaceRecognizer.recognize(
                            textBoxes = textBoxes,
                            chart = chart,
                            width = bitmap.width,
                            height = bitmap.height,
                        )
                    } catch (_: RuntimeException) {
                        frameErrors += strings.get(R.string.vision_error_interface_classifier)
                        TradingInterfaceRecognitionEngine.InterfaceResult(
                            tradingScreenDetected = false,
                            instrument = strings.unknown,
                            price = strings.unknown,
                            amount = strings.unknown,
                            timeframe = strings.unknown,
                            timer = strings.unknown,
                            expiration = strings.unknown,
                            indicators = emptyList(),
                            otherElements = emptyList(),
                        )
                    }

                    val changedPercent = previousBitmap?.let { previous ->
                        try {
                            openCvProcessor?.compare(previous, bitmap)
                        } catch (_: RuntimeException) {
                            frameErrors += strings.get(R.string.vision_error_frame_compare)
                            null
                        }
                    }
                    val frameChart = chart.copy(changedPercent = changedPercent)
                    frames += FrameAnalysis(
                        sequence = sequence,
                        timestampMillis = timestamp,
                        width = bitmap.width,
                        height = bitmap.height,
                        quality = quality,
                        tradingScreenDetected = interfaceResult.tradingScreenDetected,
                        instrument = interfaceResult.instrument,
                        price = interfaceResult.price,
                        amount = interfaceResult.amount,
                        timeframe = interfaceResult.timeframe,
                        timer = interfaceResult.timer,
                        expiration = interfaceResult.expiration,
                        chart = frameChart,
                        indicators = interfaceResult.indicators,
                        otherElements = interfaceResult.otherElements,
                        textBoxes = textBoxes,
                        errors = frameErrors.toList(),
                    )

                    previousBitmap?.recycle()
                    previousBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                } finally {
                    if (bitmap !== screenshotBitmap) bitmap.recycle()
                }
            }
        } finally {
            previousBitmap?.recycle()
            ocrProcessor?.close()
        }

        val report = aggregate(
            frames = frames,
            initializationErrors = errors,
            directory = directory,
            openCvAvailable = openCvAvailable,
            ocrInitialized = ocrProcessor != null,
        )
        persistReport(report, directory)
        return report
    }

    private suspend fun aggregate(
        frames: List<FrameAnalysis>,
        initializationErrors: List<String>,
        directory: File,
        openCvAvailable: Boolean,
        ocrInitialized: Boolean,
    ): AnalysisReport {
        val allErrors = (initializationErrors + frames.flatMap { frame ->
            frame.errors.map { strings.get(R.string.vision_frame_error, frame.sequence, it) }
        })
            .distinct()
        val detectedFrames = frames.count { it.tradingScreenDetected }
        val latestFrame = frames.lastOrNull()
        val instrument = latestFrame?.instrument ?: strings.unknown
        val price = latestFrame?.price ?: strings.unknown
        val amount = latestFrame?.amount ?: strings.unknown
        val timeframe = latestFrame?.timeframe ?: strings.unknown
        val timer = latestFrame?.timer ?: strings.unknown
        val expiration = latestFrame?.expiration ?: strings.unknown
        val candles = latestFrame?.chart?.candles.orEmpty()
        val qualityCount = frames.count { it.quality.isAdequate }
        val qualitySummary = when {
            frames.isEmpty() -> strings.unknown
            qualityCount == frames.size ->
                strings.get(R.string.vision_quality_done, qualityCount, frames.size)
            qualityCount == 0 -> strings.get(R.string.vision_quality_none, frames.size)
            else -> strings.get(R.string.vision_quality_partial, qualityCount, frames.size)
        }
        val warnings = buildList {
            if (detectedFrames == 0) {
                add(strings.get(R.string.vision_warning_interface_none))
            } else if (detectedFrames < frames.size) {
                add(strings.get(R.string.vision_warning_interface_partial))
            }
            if (instrument == strings.unknown) add(strings.get(R.string.vision_warning_instrument))
            if (price == strings.unknown) add(strings.get(R.string.vision_warning_price))
            if (amount == strings.unknown) add(strings.get(R.string.vision_warning_amount))
            if (timeframe == strings.unknown) add(strings.get(R.string.vision_warning_timeframe))
            if (timer == strings.unknown) add(strings.get(R.string.vision_warning_timer))
            if (expiration == strings.unknown) add(strings.get(R.string.vision_warning_expiration))
            if (candles.isEmpty()) add(strings.get(R.string.vision_warning_candles))
            if (frames.any { it.quality.warning != null }) {
                add(strings.get(R.string.vision_warning_quality))
            }
            addAll(sequenceWarnings(frames, strings))
        }.distinct()

        val baseReport = AnalysisReport(
            processedFrames = frames.size,
            tradingScreenDetected = detectedFrames > 0,
            instrument = instrument,
            price = price,
            amount = amount,
            timeframe = timeframe,
            timer = timer,
            expiration = expiration,
            candles = candles,
            marketMovement = detectSequenceMovement(frames, strings),
            indicators = frames.flatMap { it.indicators }.distinct(),
            imageQuality = qualitySummary,
            warnings = warnings,
            errors = allErrors,
            frames = frames,
            reportPath = File(directory, REPORT_FILE_NAME).absolutePath,
            prediction = null,
        )
        val minimumConfidence = appContext.getSharedPreferences(
            "autoscanner_settings",
            android.content.Context.MODE_PRIVATE,
        ).getFloat("minimum_confidence", 0.68f).toDouble().coerceIn(0.50, 0.95)
        val prediction = AnalyticsCoordinator(
            minimumConfidence = minimumConfidence,
        ).analyze(
            frames = frames,
            instrument = baseReport.instrument,
            price = baseReport.price,
            timeframe = baseReport.timeframe,
        )
        val visionEngines = listOf(
            EngineAssessment(
                name = "OpenCV / свечная геометрия",
                direction = DirectionInfluence.NEUTRAL,
                confidence = if (frames.any { it.chart.detected }) 0.7 else 0.0,
                weight = 0.0,
                successful = openCvAvailable && frames.any { it.chart.detected },
                failureReason = when {
                    !openCvAvailable -> "OpenCV не инициализирован"
                    frames.none { it.chart.detected } -> "Область графика или свечи не распознаны"
                    else -> null
                },
            ),
            EngineAssessment(
                name = "OCR Tesseract",
                direction = DirectionInfluence.NEUTRAL,
                confidence = if (frames.any { it.textBoxes.isNotEmpty() }) 0.65 else 0.0,
                weight = 0.0,
                successful = ocrInitialized && frames.any { it.textBoxes.isNotEmpty() },
                failureReason = when {
                    !ocrInitialized -> "OCR не запущен"
                    frames.none { it.textBoxes.isNotEmpty() } -> "Текст на кадрах не распознан"
                    else -> null
                },
            ),
            EngineAssessment(
                name = "Распознавание Pocket Option",
                direction = DirectionInfluence.NEUTRAL,
                confidence = if (frames.any { it.tradingScreenDetected }) 0.6 else 0.0,
                weight = 0.0,
                successful = frames.any { it.tradingScreenDetected },
                failureReason = if (frames.any { it.tradingScreenDetected }) null else
                    "Интерфейс Pocket Option не подтверждён",
            ),
        )
        val combinedPrediction = prediction.copy(
            risks = prediction.risks + visionEngines.filterNot { it.successful }.map { engine ->
                RiskFactor(
                    "Движок ${engine.name}",
                    engine.failureReason ?: "Модуль не предоставил результат",
                )
            },
            engineAssessments = visionEngines + prediction.engineAssessments,
        )
        val report = baseReport.copy(prediction = combinedPrediction)
        ForecastHistoryStore(appContext).append(combinedPrediction, qualitySummary)
        return report
    }

    private fun detectSequenceMovement(frames: List<FrameAnalysis>, strings: VisionStrings): String {
        val prices = frames.mapNotNull { frame ->
            if (frame.instrument == strings.unknown) return@mapNotNull null
            frame.price.replace(',', '.').toDoubleOrNull()?.let { frame.instrument to it }
        }
        if (prices.size >= MIN_PRICE_SAMPLES) {
            val instrument = prices.groupingBy { it.first }.eachCount().maxByOrNull { it.value }?.key
            val stableInstrumentPrices = prices.filter { it.first == instrument }
            val differences = stableInstrumentPrices.zipWithNext { first, second -> second.second - first.second }
                .filter { abs(it) > PRICE_EPSILON }
            if (differences.size >= MIN_PRICE_CHANGES) {
                val positive = differences.count { it > 0 }
                val negative = differences.count { it < 0 }
                val majority = maxOf(positive, negative).toFloat() / differences.size
                if (majority >= REQUIRED_DIRECTION_RATIO) {
                    return if (positive > negative) {
                        strings.get(R.string.vision_movement_up_price)
                    } else {
                        strings.get(R.string.vision_movement_down_price)
                    }
                }
            }
        }

        val candleFrames = frames.filter { it.chart.detected && it.chart.candles.isNotEmpty() }
        if (candleFrames.size >= MIN_CANDLE_SAMPLES) {
            val closes = candleFrames.map { frame ->
                val height = frame.height.toDouble().coerceAtLeast(1.0)
                frame.chart.candles.last().closeY / height
            }
            val differences = closes.zipWithNext { first, second -> first - second }
            val meaningful = differences.filter { abs(it) >= MIN_CANDLE_MOVEMENT_RATIO }
            if (meaningful.size >= MIN_CANDLE_CHANGES) {
                val upward = meaningful.count { it > 0 }
                val downward = meaningful.count { it < 0 }
                if (maxOf(upward, downward).toFloat() / meaningful.size >= REQUIRED_DIRECTION_RATIO) {
                    return if (upward > downward) {
                        strings.get(R.string.vision_movement_up_candles)
                    } else {
                        strings.get(R.string.vision_movement_down_candles)
                    }
                }
            }
        }
        return strings.get(R.string.vision_movement_unknown)
    }

    private fun sequenceWarnings(
        frames: List<FrameAnalysis>,
        strings: VisionStrings,
    ): List<String> = buildList {
        val screenStates = frames.map { it.tradingScreenDetected }.distinct()
        if (screenStates.size > 1) add(strings.get(R.string.vision_warning_interface_changed))
        val timers = frames.map { it.timer }.filter { it != strings.unknown }.distinct()
        if (timers.size > 1) add(strings.get(R.string.vision_warning_timer_changed))
        val priceValues = frames.map { it.price }.filter { it != strings.unknown }.distinct()
        if (priceValues.size > 1) add(strings.get(R.string.vision_warning_price_changed))
        if (frames.map { it.price != strings.unknown }.distinct().size > 1) {
            add(strings.get(R.string.vision_warning_price_lost))
        }
        if (frames.map { it.instrument != strings.unknown }.distinct().size > 1) {
            add(strings.get(R.string.vision_warning_instrument_changed))
        }
        if (frames.map { it.timeframe != strings.unknown }.distinct().size > 1) {
            add(strings.get(R.string.vision_warning_timeframe_changed))
        }
        val candleCounts = frames.filter { it.chart.detected }.map { it.chart.candles.size }
        if (candleCounts.zipWithNext().any { (first, next) -> next > first }) {
            add(strings.get(R.string.vision_warning_candle_count))
        }
        if (frames.any { (it.chart.changedPercent ?: 0.0) >= FRAME_CHANGE_PERCENT }) {
            add(strings.get(R.string.vision_warning_frame_changed))
        }
    }

    private fun persistReport(report: AnalysisReport, directory: File) {
        val root = JSONObject()
            .put("processedFrames", report.processedFrames)
            .put("tradingScreenDetected", report.tradingScreenDetected)
            .put("instrument", report.instrument)
            .put("price", report.price)
            .put("amount", report.amount)
            .put("timeframe", report.timeframe)
            .put("timer", report.timer)
            .put("expiration", report.expiration)
            .put("marketMovement", report.marketMovement)
            .put("indicators", JSONArray(report.indicators))
            .put("imageQuality", report.imageQuality)
            .put("warnings", JSONArray(report.warnings))
            .put("errors", JSONArray(report.errors))
            .put("prediction", report.prediction?.let { prediction ->
                JSONObject()
                    .put("direction", prediction.direction.name)
                    .put("confidence", prediction.confidence)
                    .put("upScorePercent", prediction.upScorePercent ?: JSONObject.NULL)
                    .put("downScorePercent", prediction.downScorePercent ?: JSONObject.NULL)
                    .put("instrument", prediction.instrument ?: JSONObject.NULL)
                    .put("currentPrice", prediction.currentPrice ?: JSONObject.NULL)
                    .put("analysisTimeMillis", prediction.analysisTimeMillis)
                    .put("timeframe", prediction.timeframe ?: JSONObject.NULL)
                    .put("marketCondition", prediction.marketCondition.name)
                    .put("trendDirection", prediction.trendDirection.name)
                    .put("trendStrength", prediction.trendStrength ?: JSONObject.NULL)
                    .put("momentumStrength", prediction.momentumStrength ?: JSONObject.NULL)
                    .put("volatility", prediction.volatility.name)
                    .put("reasons", JSONArray(prediction.reasons))
                    .put("risks", JSONArray(prediction.risks.map { it.description }))
                    .put("expirationSeconds", prediction.expiration.seconds ?: JSONObject.NULL)
                    .put("expirationReason", prediction.expiration.reason)
                    .put("expirationConfidence", prediction.expiration.confidence)
                    .put("expirationLimitations", JSONArray(prediction.expiration.limitations))
                    .put("factors", JSONArray(prediction.factors.map { factor ->
                        JSONObject()
                            .put("name", factor.name)
                            .put("value", factor.value)
                            .put("direction", factor.influence.name)
                            .put("weight", factor.weight)
                            .put("confidence", factor.confidence)
                            .put("source", factor.source)
                    }))
                    .put("engines", JSONArray(prediction.engineAssessments.map { engine ->
                        JSONObject()
                            .put("name", engine.name)
                            .put("direction", engine.direction.name)
                            .put("confidence", engine.confidence)
                            .put("weight", engine.weight)
                            .put("successful", engine.successful)
                            .put("failureReason", engine.failureReason ?: JSONObject.NULL)
                    }))
                    .put("candlePatterns", JSONArray(prediction.candlePatterns.map { pattern ->
                        JSONObject()
                            .put("name", pattern.name)
                            .put("type", pattern.type.name)
                            .put("direction", pattern.direction.name)
                            .put("confidence", pattern.confidence)
                            .put("confirmingFeatures", JSONArray(pattern.confirmingFeatures))
                    }))
                    .put("indicators", JSONArray(prediction.indicators.map { indicator ->
                        JSONObject()
                            .put("name", indicator.name)
                            .put("recognized", indicator.recognized)
                            .put("exactValue", indicator.exactValue ?: JSONObject.NULL)
                            .put("visualApproximation", indicator.visualApproximation ?: JSONObject.NULL)
                            .put("confidence", indicator.confidence)
                    }))
            } ?: JSONObject.NULL)
            .put("frames", JSONArray().apply {
                report.frames.forEach { frame -> put(frameJson(frame)) }
            })
        File(directory, REPORT_FILE_NAME).writeText(root.toString(2), Charsets.UTF_8)
    }

    private fun frameJson(frame: FrameAnalysis): JSONObject = JSONObject()
        .put("sequence", frame.sequence)
        .put("timestampMillis", frame.timestampMillis)
        .put("width", frame.width)
        .put("height", frame.height)
        .put("quality", JSONObject()
            .put("adequate", frame.quality.isAdequate)
            .put("sharpness", frame.quality.sharpness ?: JSONObject.NULL)
            .put("brightness", frame.quality.brightness ?: JSONObject.NULL)
            .put("contrast", frame.quality.contrast ?: JSONObject.NULL)
            .put("warning", frame.quality.warning ?: JSONObject.NULL))
        .put("tradingScreenDetected", frame.tradingScreenDetected)
        .put("instrument", frame.instrument)
        .put("price", frame.price)
        .put("amount", frame.amount)
        .put("timeframe", frame.timeframe)
        .put("timer", frame.timer)
        .put("expiration", frame.expiration)
        .put("chart", JSONObject()
            .put("detected", frame.chart.detected)
            .put("left", frame.chart.left ?: JSONObject.NULL)
            .put("top", frame.chart.top ?: JSONObject.NULL)
            .put("right", frame.chart.right ?: JSONObject.NULL)
            .put("bottom", frame.chart.bottom ?: JSONObject.NULL)
            .put("movement", frame.chart.movement)
            .put("changedPercent", frame.chart.changedPercent ?: JSONObject.NULL)
            .put("candles", JSONArray().apply {
                frame.chart.candles.forEach { candle -> put(candleJson(candle)) }
            }))
        .put("indicators", JSONArray(frame.indicators))
        .put("otherElements", JSONArray(frame.otherElements))
        .put("textBoxes", JSONArray().apply {
            frame.textBoxes.forEach { box ->
                put(JSONObject()
                    .put("text", box.text)
                    .put("confidence", box.confidence)
                    .put("left", box.left)
                    .put("top", box.top)
                    .put("right", box.right)
                    .put("bottom", box.bottom))
            }
        })
        .put("errors", JSONArray(frame.errors))

    private fun candleJson(candle: Candle): JSONObject = JSONObject()
        .put("index", candle.index)
        .put("left", candle.left)
        .put("top", candle.top)
        .put("right", candle.right)
        .put("bottom", candle.bottom)
        .put("wickTop", candle.wickTop)
        .put("wickBottom", candle.wickBottom)
        .put("color", candle.color)
        .put("closeY", candle.closeY)

    private fun failedFrame(sequence: Int, timestamp: Long, errors: List<String>) = FrameAnalysis(
        sequence = sequence,
        timestampMillis = timestamp,
        width = 0,
        height = 0,
        quality = ImageQuality(
            0,
            0,
            null,
            null,
            null,
            false,
            strings.get(R.string.vision_quality_frame_unreadable),
        ),
        tradingScreenDetected = false,
        instrument = strings.unknown,
        price = strings.unknown,
        amount = strings.unknown,
        timeframe = strings.unknown,
        timer = strings.unknown,
        expiration = strings.unknown,
        chart = unavailableChart(),
        indicators = emptyList(),
        otherElements = emptyList(),
        textBoxes = emptyList(),
        errors = errors,
    )

    private fun unavailableQuality(bitmap: Bitmap) =
        ImageQuality(
            bitmap.width,
            bitmap.height,
            null,
            null,
            null,
            false,
            strings.get(R.string.vision_quality_unavailable_value),
        )

    private fun unavailableChart() = ChartAnalysis(
        detected = false,
        left = null,
        top = null,
        right = null,
        bottom = null,
        candles = emptyList(),
        movement = strings.get(R.string.vision_chart_undetermined),
        changedPercent = null,
    )

    companion object {
        private const val REPORT_FILE_NAME = "analysis.json"
        private const val MIN_PRICE_SAMPLES = 3
        private const val MIN_PRICE_CHANGES = 2
        private const val MIN_CANDLE_SAMPLES = 3
        private const val MIN_CANDLE_CHANGES = 2
        private const val REQUIRED_DIRECTION_RATIO = 0.70f
        private const val PRICE_EPSILON = 0.0000001
        private const val MIN_CANDLE_MOVEMENT_RATIO = 0.003
        private const val FRAME_CHANGE_PERCENT = 0.5
        private val FRAME_FILE_REGEX = Regex("""frame_(\d+)\.jpg""")
    }
}
