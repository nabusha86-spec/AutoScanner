package com.autoscanner.trader

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.autoscanner.trader.analytics.MarketCondition
import com.autoscanner.trader.analytics.PredictionDirection
import com.autoscanner.trader.analytics.TrendDirection
import com.autoscanner.trader.analytics.VolatilityLevel
import com.autoscanner.trader.ui.theme.AutoScannerTheme
import com.autoscanner.trader.vision.AnalysisReport
import com.autoscanner.trader.vision.FrameProcessingEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.IOException

class MainActivity : ComponentActivity() {
    private var captureStatus by mutableIntStateOf(R.string.capture_idle)
    private var resultStatus by mutableIntStateOf(R.string.analysis_not_ready)
    private var capturedFrames by mutableIntStateOf(0)
    private var captureRunning by mutableStateOf(false)
    private var captureSessionActive by mutableStateOf(false)
    private var recordingEnabled by mutableStateOf(false)
    private var analysisReport by mutableStateOf<AnalysisReport?>(null)
    private var analysisRunning by mutableStateOf(false)
    private var analysisError by mutableStateOf<String?>(null)
    private var manualConfirmationReceived by mutableStateOf(false)
    private var pendingScreenshotAfterSessionReady = false

    private val captureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ScreenCaptureService.ACTION_SESSION_READY) {
                captureSessionActive = true
                captureRunning = false
                captureStatus = R.string.capture_session_ready
                if (pendingScreenshotAfterSessionReady) {
                    pendingScreenshotAfterSessionReady = false
                    requestSingleCapture()
                }
                return
            }
            if (intent.action == ScreenCaptureService.ACTION_RECORDING_STATE) {
                recordingEnabled = intent.getBooleanExtra(
                    ScreenCaptureService.EXTRA_RECORDING_ENABLED,
                    false,
                )
                return
            }

            captureRunning = false
            captureSessionActive = intent.getBooleanExtra(
                ScreenCaptureService.EXTRA_SESSION_ACTIVE,
                captureSessionActive,
            )
            if (!captureSessionActive) recordingEnabled = false
            capturedFrames = intent.getIntExtra(ScreenCaptureService.EXTRA_FRAMES, 0)
            val succeeded = intent.getBooleanExtra(ScreenCaptureService.EXTRA_SUCCEEDED, false)
            captureStatus = if (succeeded) R.string.capture_completed else R.string.capture_failed
            resultStatus = if (succeeded) R.string.analysis_processing else R.string.capture_error
            if (!succeeded) {
                CapturedFrameStore.clear()
                analysisReport = null
                analysisError = null
                val errorResource = intent.getIntExtra(
                    ScreenCaptureService.EXTRA_ERROR_RESOURCE,
                    R.string.capture_failed,
                )
                captureStatus = errorResource
                if (errorResource == R.string.capture_permission_lost ||
                    errorResource == R.string.capture_interrupted
                ) {
                    captureSessionActive = false
                    recordingEnabled = false
                }
                showOverlayError(getString(errorResource))
                return
            }

            analysisReport = null
            analysisError = null
            manualConfirmationReceived = false
            analysisRunning = true
            val directory = intent.getStringExtra(ScreenCaptureService.EXTRA_CAPTURE_DIRECTORY)
            val imagePath = intent.getStringExtra(ScreenCaptureService.EXTRA_CAPTURE_IMAGE)
            val captureTimestamp = intent.getLongExtra(ScreenCaptureService.EXTRA_CAPTURE_TIMESTAMP, 0L)
            val screenshotBitmap = CapturedFrameStore.take()
            if (directory.isNullOrBlank()) {
                screenshotBitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
                analysisRunning = false
                resultStatus = R.string.analysis_failed
                val message = getString(R.string.analysis_directory_missing)
                analysisError = message
                showOverlayError(message)
                startService(
                    Intent(this@MainActivity, ScreenCaptureService::class.java)
                        .setAction(ScreenCaptureService.ACTION_ANALYSIS_FINISHED),
                )
                return
            }
            lifecycleScope.launch {
                try {
                    val report = FrameProcessingEngine(this@MainActivity)
                        .processSequence(directory, screenshotBitmap)
                    analysisReport = report
                    resultStatus = R.string.analysis_complete
                    showOverlayResult(report, imagePath, captureTimestamp)
                } catch (error: IOException) {
                    Log.e(TAG, "Frame sequence analysis failed", error)
                    resultStatus = R.string.analysis_failed
                    val message = error.message ?: getString(R.string.analysis_processing_error)
                    analysisError = message
                    showOverlayError(message)
                } catch (error: CancellationException) {
                    if (!isFinishing) showOverlayError(getString(R.string.analysis_cancelled))
                    throw error
                } catch (error: RuntimeException) {
                    Log.e(TAG, "Frame sequence analysis failed", error)
                    resultStatus = R.string.analysis_failed
                    val message = error.message ?: getString(R.string.analysis_processing_error)
                    analysisError = message
                    showOverlayError(message)
                } finally {
                    screenshotBitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
                    analysisRunning = false
                    startService(
                        Intent(this@MainActivity, ScreenCaptureService::class.java)
                            .setAction(ScreenCaptureService.ACTION_ANALYSIS_FINISHED),
                    )
                }
            }
        }
    }

    private val overlaySettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (Settings.canDrawOverlays(this)) {
            launchProjectionPermission()
        } else {
            captureRunning = false
            captureStatus = R.string.overlay_permission_required
        }
    }

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        captureRunning = false
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java)
                .setAction(ScreenCaptureService.ACTION_START_SESSION)
                .putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                .putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
            captureRunning = true
            analysisReport = null
            analysisError = null
            resultStatus = R.string.analysis_not_ready
            captureStatus = R.string.capture_starting
            try {
                ContextCompat.startForegroundService(this, serviceIntent)
            } catch (_: SecurityException) {
                captureRunning = false
                captureStatus = R.string.capture_permission_error
                resultStatus = R.string.capture_error
            } catch (_: IllegalStateException) {
                captureRunning = false
                captureStatus = R.string.capture_service_error
                resultStatus = R.string.capture_error
            }
        } else {
            captureRunning = false
            captureStatus = R.string.capture_permission_denied
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val filter = IntentFilter().apply {
            addAction(ScreenCaptureService.ACTION_CAPTURE_COMPLETE)
            addAction(ScreenCaptureService.ACTION_SESSION_READY)
            addAction(ScreenCaptureService.ACTION_RECORDING_STATE)
        }
        ContextCompat.registerReceiver(
            this,
            captureReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        startService(
            Intent(this, ScreenCaptureService::class.java)
                .setAction(ScreenCaptureService.ACTION_QUERY_STATE),
        )

        setContent {
            AutoScannerTheme {
                AutoScannerApp(
                    captureStatus = captureStatus,
                    resultStatus = resultStatus,
                    capturedFrames = capturedFrames,
                    captureRunning = captureRunning,
                    captureSessionActive = captureSessionActive,
                    recordingEnabled = recordingEnabled,
                    analysisReport = analysisReport,
                    analysisRunning = analysisRunning,
                    analysisError = analysisError,
                    manualConfirmationReceived = manualConfirmationReceived,
                    onStartCapture = ::requestCapture,
                    onManualCapture = ::requestSingleCapture,
                    onToggleRecording = ::toggleRecording,
                    onStopSession = ::stopCaptureSession,
                    onManualConfirmation = { manualConfirmationReceived = true },
                )
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing && !isChangingConfigurations) {
            stopService(Intent(this, ScreenCaptureService::class.java))
        }
        unregisterReceiver(captureReceiver)
        super.onDestroy()
    }

    private fun requestCapture() {
        if (captureSessionActive) {
            captureStatus = R.string.capture_session_ready
            return
        }
        if (captureRunning || analysisRunning) return
        requestProjectionWithOverlayPermission()
    }

    private fun requestSingleCapture() {
        if (!captureSessionActive) {
            pendingScreenshotAfterSessionReady = true
            requestCapture()
            return
        }
        if (captureRunning || analysisRunning) return
        captureRunning = true
        resultStatus = R.string.capture_getting_frame
        captureStatus = R.string.capture_getting_frame
        try {
            startService(
                Intent(this, ScreenCaptureService::class.java)
                    .setAction(ScreenCaptureService.ACTION_CAPTURE_SINGLE),
            )
        } catch (_: SecurityException) {
            captureRunning = false
            captureStatus = R.string.capture_permission_error
            resultStatus = R.string.capture_error
        } catch (_: IllegalStateException) {
            captureRunning = false
            captureStatus = R.string.capture_service_error
            resultStatus = R.string.capture_error
        }
    }

    private fun toggleRecording() {
        if (captureRunning || analysisRunning) return
        if (!captureSessionActive) {
            requestCapture()
            return
        }
        try {
            startService(
                Intent(this, ScreenCaptureService::class.java)
                    .setAction(ScreenCaptureService.ACTION_TOGGLE_RECORDING),
            )
        } catch (_: SecurityException) {
            captureStatus = R.string.capture_permission_error
        } catch (_: IllegalStateException) {
            captureStatus = R.string.capture_service_error
        }
    }

    private fun stopCaptureSession() {
        try {
            startService(
                Intent(this, ScreenCaptureService::class.java)
                    .setAction(ScreenCaptureService.ACTION_STOP_SESSION),
            )
            captureSessionActive = false
            recordingEnabled = false
            captureStatus = R.string.capture_idle
            resultStatus = R.string.analysis_not_ready
        } catch (_: IllegalStateException) {
            captureStatus = R.string.capture_service_error
        }
    }

    private fun requestProjectionWithOverlayPermission() {
        captureRunning = true
        captureStatus = R.string.capture_permission_request
        if (!Settings.canDrawOverlays(this)) {
            captureRunning = false
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            )
            overlaySettingsLauncher.launch(intent)
            return
        }
        launchProjectionPermission()
    }

    private fun launchProjectionPermission() {
        captureRunning = true
        captureStatus = R.string.capture_permission_request
        val manager = getSystemService(MediaProjectionManager::class.java)
        try {
            captureLauncher.launch(manager.createScreenCaptureIntent())
        } catch (_: SecurityException) {
            captureRunning = false
            captureStatus = R.string.capture_permission_error
        } catch (_: IllegalStateException) {
            captureRunning = false
            captureStatus = R.string.capture_service_error
        }
    }

    private fun showOverlayResult(
        report: AnalysisReport,
        imagePath: String?,
        captureTimestamp: Long,
    ) {
        val prediction = report.prediction
        val hasSignal = prediction?.direction == PredictionDirection.BUY ||
            prediction?.direction == PredictionDirection.SELL
        val direction = when (prediction?.direction) {
            PredictionDirection.BUY -> getString(R.string.overlay_direction_up)
            PredictionDirection.SELL -> getString(R.string.overlay_direction_down)
            PredictionDirection.NO_TRADE, null -> getString(R.string.overlay_no_signal)
        }
        val confidence = prediction
            ?.let { getString(R.string.forecast_confidence_inline, (it.confidence * 100).toInt()) }
            ?: getString(R.string.forecast_confidence_undefined)
        val upScore = prediction?.upScorePercent
            ?.let { getString(R.string.overlay_score_percent, it) }
            ?: getString(R.string.undetermined)
        val downScore = prediction?.downScorePercent
            ?.let { getString(R.string.overlay_score_percent, it) }
            ?: getString(R.string.undetermined)
        val engines = prediction?.engineAssessments.orEmpty()
        val failedEngines = engines.filterNot { it.successful }
        val engineSummary = getString(
            R.string.overlay_engine_summary,
            engines.count { it.successful },
            engines.size,
        )
        val reasons = prediction?.reasons.orEmpty().take(2)
        val explanation = reasons.ifEmpty { listOf(report.marketMovement) }
            .joinToString("\n") { getString(R.string.analysis_list_item, it) }
        val insufficientData = prediction?.direction == PredictionDirection.NO_TRADE
        val warning = buildList {
            if (insufficientData) add(getString(R.string.overlay_insufficient_data))
            if (failedEngines.isNotEmpty()) {
                add(getString(
                    R.string.overlay_failed_engines,
                    failedEngines.joinToString { it.name },
                ))
            }
            val directions = engines.filter {
                    it.successful && it.direction != com.autoscanner.trader.analytics.DirectionInfluence.NEUTRAL
                }.map { it.direction }.distinct()
            if (directions.size > 1) add(getString(R.string.overlay_engine_conflict))
        }.takeIf { it.isNotEmpty() }?.joinToString("\n")
        val resultText = listOf(
            getString(R.string.screenshot_received_at, java.text.SimpleDateFormat(
                "HH:mm:ss",
                java.util.Locale.getDefault(),
            ).format(java.util.Date(captureTimestamp))),
            getString(R.string.screenshot_analyzed),
            direction,
            getString(R.string.overlay_score_up, upScore),
            getString(R.string.overlay_score_down, downScore),
            getString(R.string.overlay_confidence_inline, confidence),
            engineSummary,
            explanation,
            warning,
            getString(R.string.manual_execution_only),
        ).filterNotNull().joinToString("\n")
        startService(
            Intent(this, ScreenCaptureService::class.java)
                .setAction(ScreenCaptureService.ACTION_SHOW_RESULT)
                .putExtra(ScreenCaptureService.EXTRA_RESULT_TEXT, resultText)
                .putExtra(ScreenCaptureService.EXTRA_RESULT_IMAGE, imagePath)
                .putExtra(ScreenCaptureService.EXTRA_HAS_SIGNAL, hasSignal)
                .putExtra(ScreenCaptureService.EXTRA_KEEP_HIDDEN, false),
        )
    }

    private fun showOverlayError(message: String) {
        startService(
            Intent(this, ScreenCaptureService::class.java)
                .setAction(ScreenCaptureService.ACTION_SHOW_ERROR)
                .putExtra(ScreenCaptureService.EXTRA_RESULT_TEXT, message),
        )
    }

    private companion object {
        const val TAG = "AutoScanner"
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun AutoScannerApp(
    captureStatus: Int,
    resultStatus: Int,
    capturedFrames: Int,
    captureRunning: Boolean,
    captureSessionActive: Boolean,
    recordingEnabled: Boolean,
    analysisReport: AnalysisReport?,
    analysisRunning: Boolean,
    analysisError: String?,
    manualConfirmationReceived: Boolean,
    onStartCapture: () -> Unit,
    onManualCapture: () -> Unit,
    onToggleRecording: () -> Unit,
    onStopSession: () -> Unit,
    onManualConfirmation: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.app_name), fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(R.string.app_subtitle),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CaptureCard(
                status = stringResource(captureStatus),
                capturedFrames = capturedFrames,
                captureRunning = captureRunning,
                captureSessionActive = captureSessionActive,
                recordingEnabled = recordingEnabled,
                onStartSession = onStartCapture,
                onScreenshot = onManualCapture,
                onToggleRecording = onToggleRecording,
                onStopSession = onStopSession,
            )
            AnalysisCard(
                resultStatus = stringResource(resultStatus),
                report = analysisReport,
                processing = analysisRunning,
                error = analysisError,
                manualConfirmationReceived = manualConfirmationReceived,
                onManualConfirmation = onManualConfirmation,
            )
            SafetyNote()
        }
    }
}

@Composable
private fun CaptureCard(
    status: String,
    capturedFrames: Int,
    captureRunning: Boolean,
    captureSessionActive: Boolean,
    recordingEnabled: Boolean,
    onStartSession: () -> Unit,
    onScreenshot: () -> Unit,
    onToggleRecording: () -> Unit,
    onStopSession: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.screen_capture_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(status, style = MaterialTheme.typography.bodyMedium)
            if (capturedFrames > 0) {
                Text(stringResource(R.string.frames_captured, capturedFrames))
            }
            if (!captureSessionActive) {
                Button(
                    onClick = onStartSession,
                    enabled = !captureRunning,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.capture_start_button))
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onScreenshot,
                        enabled = !captureRunning,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.manual_scan_button))
                    }
                    OutlinedButton(
                        onClick = onToggleRecording,
                        enabled = !captureRunning,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            stringResource(
                                if (recordingEnabled) R.string.recording_stop else R.string.recording_start,
                            ),
                        )
                    }
                }
                OutlinedButton(
                    onClick = onStopSession,
                    enabled = !captureRunning,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.capture_session_stop))
                }
            }
            Text(
                stringResource(R.string.capture_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AnalysisCard(
    resultStatus: String,
    report: AnalysisReport?,
    processing: Boolean,
    error: String?,
    manualConfirmationReceived: Boolean,
    onManualConfirmation: () -> Unit,
) {
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.analysis_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(resultStatus, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (processing) Text(stringResource(R.string.analysis_processing_frames))
            report?.let {
                AnalysisDetails(it)
                if (it.prediction?.direction == PredictionDirection.BUY ||
                    it.prediction?.direction == PredictionDirection.SELL
                ) {
                    Button(
                        onClick = onManualConfirmation,
                        enabled = !manualConfirmationReceived,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(
                                if (manualConfirmationReceived) R.string.manual_confirmation_done
                                else R.string.manual_confirmation_button,
                            ),
                        )
                    }
                }
            }
            if (error != null) {
                Text(
                    stringResource(R.string.analysis_error_details, error),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun AnalysisDetails(report: AnalysisReport) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AnalysisValue(
            stringResource(R.string.frames_processed_label),
            stringResource(R.string.frames_processed_value, report.processedFrames),
        )
        AnalysisValue(stringResource(R.string.instrument_label), report.instrument)
        AnalysisValue(stringResource(R.string.price_label), report.price)
        AnalysisValue(stringResource(R.string.timeframe_label), report.timeframe)
        AnalysisValue(stringResource(R.string.timer_label), report.timer)
        AnalysisValue(stringResource(R.string.candles_label), report.candles.size.toString())
        AnalysisValue(stringResource(R.string.market_movement_label), report.marketMovement)
        report.prediction?.let { prediction ->
            val direction = when (prediction.direction) {
                PredictionDirection.BUY -> stringResource(R.string.forecast_buy)
                PredictionDirection.SELL -> stringResource(R.string.forecast_sell)
                PredictionDirection.NO_TRADE -> stringResource(R.string.forecast_no_trade)
            }
            AnalysisValue(stringResource(R.string.forecast_prediction_label), direction)
            AnalysisValue(
                stringResource(R.string.forecast_confidence),
                if (prediction.direction == PredictionDirection.NO_TRADE) {
                    stringResource(R.string.forecast_confidence_undefined)
                } else {
                    stringResource(R.string.forecast_confidence_inline, (prediction.confidence * 100).toInt())
                },
            )
            AnalysisValue(
                stringResource(R.string.forecast_expiration),
                prediction.expiration.seconds?.let {
                    stringResource(R.string.forecast_expiration_seconds, it)
                } ?: stringResource(R.string.forecast_expiration_undefined),
            )
            AnalysisValue(
                stringResource(R.string.forecast_trend),
                stringResource(trendDirectionResource(prediction.trendDirection)),
            )
            AnalysisValue(
                stringResource(R.string.forecast_trend_strength),
                prediction.trendStrength?.let { "${(it * 100).toInt()}%" }
                    ?: stringResource(R.string.undetermined),
            )
            AnalysisValue(
                stringResource(R.string.forecast_momentum),
                prediction.momentumStrength?.let { "${(it * 100).toInt()}%" }
                    ?: stringResource(R.string.undetermined),
            )
            AnalysisValue(
                stringResource(R.string.forecast_volatility),
                stringResource(volatilityResource(prediction.volatility)),
            )
            AnalysisValue(
                stringResource(R.string.forecast_market_condition),
                stringResource(marketConditionResource(prediction.marketCondition)),
            )
            if (prediction.candlePatterns.isNotEmpty()) {
                AnalysisValue(
                    stringResource(R.string.forecast_patterns),
                    prediction.candlePatterns.joinToString("; ") { it.name },
                )
            }
            prediction.reasons.forEach {
                Text(stringResource(R.string.analysis_list_item, it), style = MaterialTheme.typography.bodySmall)
            }
            prediction.risks.forEach {
                Text(
                    stringResource(R.string.analysis_list_item, "${it.name}: ${it.description}"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        AnalysisValue(stringResource(R.string.image_quality_label), report.imageQuality)
        if (report.warnings.isNotEmpty()) {
            report.warnings.forEach {
                Text(stringResource(R.string.analysis_list_item, it), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (report.errors.isNotEmpty()) {
            report.errors.forEach {
                Text(
                    stringResource(R.string.analysis_list_item, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun trendDirectionResource(direction: TrendDirection): Int = when (direction) {
    TrendDirection.UP -> R.string.trend_up
    TrendDirection.DOWN -> R.string.trend_down
    TrendDirection.SIDEWAYS -> R.string.trend_sideways
    TrendDirection.POSSIBLE_REVERSAL -> R.string.trend_reversal
    TrendDirection.WEAKENING -> R.string.trend_weakening
    TrendDirection.STRENGTHENING -> R.string.trend_strengthening
    TrendDirection.UNCONFIRMED -> R.string.trend_unconfirmed
}

private fun marketConditionResource(condition: MarketCondition): Int = when (condition) {
    MarketCondition.STABLE_TREND -> R.string.market_stable
    MarketCondition.WEAK_TREND -> R.string.market_weak
    MarketCondition.SIDEWAYS -> R.string.market_sideways
    MarketCondition.CONSOLIDATION -> R.string.market_consolidation
    MarketCondition.IMPULSE -> R.string.market_impulse
    MarketCondition.HIGH_VOLATILITY -> R.string.market_high_volatility
    MarketCondition.POSSIBLE_REVERSAL -> R.string.market_reversal
    MarketCondition.UNDEFINED -> R.string.market_undefined
}

private fun volatilityResource(volatility: VolatilityLevel): Int = when (volatility) {
    VolatilityLevel.LOW -> R.string.vol_low
    VolatilityLevel.MODERATE -> R.string.vol_moderate
    VolatilityLevel.HIGH -> R.string.vol_high
    VolatilityLevel.SHARP_INCREASE -> R.string.vol_increase
    VolatilityLevel.SHARP_DECREASE -> R.string.vol_decrease
    VolatilityLevel.UNDEFINED -> R.string.vol_undefined
}

@Composable
private fun AnalysisValue(label: String, value: String) {
    Text(stringResource(R.string.analysis_value, label, value), style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun SafetyNote() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
    ) {
        Text(
            stringResource(R.string.safety_note),
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
