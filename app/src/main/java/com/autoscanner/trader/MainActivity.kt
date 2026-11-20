package com.autoscanner.trader

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.autoscanner.trader.ui.theme.AutoScannerTheme
import com.autoscanner.trader.vision.AnalysisReport
import com.autoscanner.trader.vision.FrameProcessingEngine
import com.autoscanner.trader.analytics.MarketCondition
import com.autoscanner.trader.analytics.PredictionDirection
import com.autoscanner.trader.analytics.TrendDirection
import com.autoscanner.trader.analytics.VolatilityLevel
import com.autoscanner.trader.trading.AutoTradingController
import com.autoscanner.trader.trading.AutoTradingStatus
import com.autoscanner.trader.trading.ExecutionStatus
import com.autoscanner.trader.trading.EntryDecision
import com.autoscanner.trader.trading.TradingMode
import com.autoscanner.trader.trading.TradingSessionState
import kotlinx.coroutines.launch
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    private var autoTradingState by mutableStateOf(TradingSessionState())
    private lateinit var autoTradingController: AutoTradingController
    private var autoStartRequested = false
    private var autoStartAfterSessionReady = false
    private var pendingScreenshotAfterSessionReady = false
    private var frameAnalysisJob: Job? = null

    private val captureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ScreenCaptureService.ACTION_SESSION_READY) {
                captureSessionActive = true
                captureRunning = false
                captureStatus = R.string.capture_session_ready
                if (autoStartAfterSessionReady) {
                    autoStartAfterSessionReady = false
                    autoTradingController.attachActiveProjectionSession()
                    autoTradingController.start()
                }
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
            capturedFrames = intent.getIntExtra(ScreenCaptureService.EXTRA_FRAMES, 0)
            val succeeded = intent.getBooleanExtra(ScreenCaptureService.EXTRA_SUCCEEDED, false)
            val continuousSession = intent.getBooleanExtra(
                ScreenCaptureService.EXTRA_CONTINUOUS_SESSION,
                false,
            )
            captureStatus = if (succeeded) R.string.capture_completed else R.string.capture_failed
            resultStatus = if (succeeded) {
                R.string.analysis_processing
            } else {
                R.string.capture_error
            }
            if (!succeeded) {
                analysisReport = null
                analysisError = null
                val errorResource = intent.getIntExtra(
                    ScreenCaptureService.EXTRA_ERROR_RESOURCE,
                    R.string.capture_failed,
                )
                captureStatus = intent.getIntExtra(
                    ScreenCaptureService.EXTRA_ERROR_RESOURCE,
                    R.string.capture_failed,
                )
                if (errorResource == R.string.capture_permission_lost ||
                    errorResource == R.string.capture_interrupted
                ) {
                    captureSessionActive = false
                    recordingEnabled = false
                }
                autoTradingController.onCaptureFailed(getString(
                    errorResource,
                ))
                showOverlayError(getString(errorResource))
            }
            if (succeeded) {
                analysisReport = null
                analysisError = null
                analysisRunning = true
                val directory = intent.getStringExtra(ScreenCaptureService.EXTRA_CAPTURE_DIRECTORY)
                if (directory.isNullOrBlank()) {
                    analysisRunning = false
                    resultStatus = R.string.analysis_failed
                    val message = getString(R.string.analysis_directory_missing)
                    analysisError = message
                    showOverlayError(message)
                    if (autoTradingState.running) {
                        autoTradingController.onCaptureFailed(getString(R.string.analysis_directory_missing))
                    }
                } else {
                    frameAnalysisJob?.cancel()
                    frameAnalysisJob = lifecycleScope.launch {
                        try {
                            val report = FrameProcessingEngine(this@MainActivity)
                                .processSequence(directory)
                            analysisReport = report
                            resultStatus = R.string.analysis_complete
                            showOverlayResult(report, directory)
                            if (autoTradingState.running) {
                                autoTradingController.onCaptureCompleted(analysisReport)
                            }
                        } catch (error: IOException) {
                            Log.e(TAG, "Frame sequence analysis failed", error)
                            resultStatus = R.string.analysis_failed
                            val message = error.message ?: getString(R.string.analysis_processing_error)
                            analysisError = message
                            showOverlayError(message)
                            if (autoTradingState.running) {
                                autoTradingController.onCaptureFailed(getString(R.string.analysis_processing_error))
                            }
                        } catch (error: CancellationException) {
                            if (!isFinishing) {
                                showOverlayError(getString(R.string.analysis_cancelled))
                            }
                            throw error
                        } catch (error: RuntimeException) {
                            Log.e(TAG, "Frame sequence analysis failed", error)
                            resultStatus = R.string.analysis_failed
                            val message = error.message ?: getString(R.string.analysis_processing_error)
                            analysisError = message
                            showOverlayError(message)
                            if (autoTradingState.running) {
                                autoTradingController.onCaptureFailed(getString(R.string.analysis_processing_error))
                            }
                        } finally {
                            analysisRunning = false
                            if (continuousSession) removeProcessedFrames(directory)
                        }
                    }
                }
            }
        }
    }

    private val overlayCommandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ScreenCaptureService.ACTION_OVERLAY_MANUAL -> {
                    if (autoTradingState.running) stopAutoTrading()
                    setTradingMode(TradingMode.MANUAL)
                }
                ScreenCaptureService.ACTION_OVERLAY_AUTO -> {
                    setTradingMode(TradingMode.AUTO)
                    startAutoTrading()
                }
                ScreenCaptureService.ACTION_OVERLAY_STOP_AUTO -> stopAutoTrading()
            }
        }
    }

    private val overlaySettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (Settings.canDrawOverlays(this)) {
            launchProjectionPermission()
        } else {
            autoStartRequested = false
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
            val shouldStartAuto = autoStartRequested
            autoStartRequested = false
            autoStartAfterSessionReady = shouldStartAuto
            if (shouldStartAuto) {
                autoTradingController.grantProjection(result.resultCode, result.data!!)
            }
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
            autoStartRequested = false
            captureRunning = false
            captureStatus = R.string.capture_permission_denied
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        autoTradingController = AutoTradingController(this)
        autoTradingController.setStateListener { state ->
            autoTradingState = state
            if (state.status == AutoTradingStatus.WAITING_NEXT ||
                (!state.running && state.tradeCycleState != com.autoscanner.trader.trading.TradeCycleState.NONE)
            ) {
                showOverlayExecutionStatus(state)
            }
        }
        autoTradingController.setCaptureAction { intent ->
            val serviceIntent = Intent(this, ScreenCaptureService::class.java)
            captureRunning = true
            try {
                if (intent.getBooleanExtra("next_cycle", false)) {
                    serviceIntent.action = ScreenCaptureService.ACTION_START_NEXT_CYCLE
                    startService(serviceIntent)
                } else {
                    serviceIntent
                        .putExtra(
                            ScreenCaptureService.EXTRA_RESULT_CODE,
                            intent.getIntExtra("result_code", 0),
                        )
                        .putExtra(
                            ScreenCaptureService.EXTRA_RESULT_DATA,
                            if (Build.VERSION.SDK_INT >= 33) {
                                intent.getParcelableExtra("result_data", Intent::class.java)
                            } else {
                                @Suppress("DEPRECATION")
                                intent.getParcelableExtra<Intent>("result_data")
                            },
                        )
                        .putExtra(ScreenCaptureService.EXTRA_CONTINUOUS_SESSION, true)
                    startForegroundService(serviceIntent)
                }
            } catch (_: SecurityException) {
                captureRunning = false
                autoTradingController.onCaptureFailed(getString(R.string.capture_permission_error))
            } catch (_: IllegalStateException) {
                captureRunning = false
                autoTradingController.onCaptureFailed(getString(R.string.capture_service_error))
            }
        }
        autoTradingController.setStopCaptureAction {
            try {
                startService(
                    Intent(this, ScreenCaptureService::class.java)
                        .setAction(ScreenCaptureService.ACTION_STOP_AUTO),
                )
            } catch (_: IllegalStateException) {
                Log.w(TAG, "Capture service was already stopped while stopping automatic analysis")
            }
            captureRunning = false
        }
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
        val overlayFilter = IntentFilter().apply {
            addAction(ScreenCaptureService.ACTION_OVERLAY_MANUAL)
            addAction(ScreenCaptureService.ACTION_OVERLAY_AUTO)
            addAction(ScreenCaptureService.ACTION_OVERLAY_STOP_AUTO)
        }
        ContextCompat.registerReceiver(
            this,
            overlayCommandReceiver,
            overlayFilter,
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
                    captureRunning = captureRunning || analysisRunning,
                    captureSessionActive = captureSessionActive,
                    recordingEnabled = recordingEnabled,
                    analysisReport = analysisReport,
                    analysisRunning = analysisRunning,
                    analysisError = analysisError,
                    onStartCapture = ::requestCapture,
                    onManualCapture = ::requestSingleCapture,
                    onToggleRecording = ::toggleRecording,
                    onStopSession = ::stopCaptureSession,
                    autoTradingState = autoTradingState,
                    onStartAutoTrading = ::startAutoTrading,
                    onStopAutoTrading = ::stopAutoTrading,
                    onOpenAccessibilitySettings = ::openAccessibilitySettings,
                    onEmergencyStop = ::emergencyStop,
                    onSetMode = ::setTradingMode,
                    onConfirmEmergency = ::confirmEmergencyResume,
                    onSetMartingale = ::setMartingale,
                    onSetInitialAmount = autoTradingController::setInitialAmount,
                )
            }
        }
    }

    override fun onDestroy() {
        frameAnalysisJob?.cancel()
        autoTradingController.close()
        if (isFinishing && !isChangingConfigurations) {
            stopService(Intent(this, ScreenCaptureService::class.java))
        }
        unregisterReceiver(captureReceiver)
        unregisterReceiver(overlayCommandReceiver)
        super.onDestroy()
    }

    private fun requestCapture() {
        if (captureSessionActive) {
            captureStatus = R.string.capture_session_ready
            return
        }
        if (captureRunning || analysisRunning) return
        autoStartRequested = false
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
        stopAutoTrading()
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

    private fun startAutoTrading() {
        if (autoTradingState.running) {
            stopAutoTrading()
            return
        }
        if (autoTradingState.mode != TradingMode.AUTO || autoTradingState.emergencyLocked ||
            autoTradingState.tradeCycleState == com.autoscanner.trader.trading.TradeCycleState.BLOCKED_UNCONFIRMED
        ) return
        if (captureRunning || analysisRunning) return
        if (captureSessionActive) {
            autoTradingController.attachActiveProjectionSession()
            autoTradingController.start()
            return
        }
        autoStartRequested = true
        requestProjectionWithOverlayPermission()
    }

    private fun stopAutoTrading() {
        frameAnalysisJob?.cancel()
        autoTradingController.stop()
        captureRunning = false
        analysisRunning = false
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (error: android.content.ActivityNotFoundException) {
            Log.e(TAG, "Accessibility settings are unavailable", error)
            captureStatus = R.string.accessibility_settings_unavailable
        } catch (error: SecurityException) {
            Log.e(TAG, "Unable to open Accessibility settings", error)
            captureStatus = R.string.accessibility_settings_unavailable
        }
    }

    private fun emergencyStop() {
        frameAnalysisJob?.cancel()
        autoTradingController.stopEmergency(getString(R.string.auto_emergency_reason))
    }

    private fun confirmEmergencyResume(resetAmount: Boolean) {
        autoTradingController.confirmEmergencyResume(resetAmount, true)
    }

    private fun setMartingale(enabled: Boolean) {
        autoTradingController.configureMartingale(enabled, true)
    }

    private fun setTradingMode(mode: TradingMode) {
        autoTradingController.setMode(mode)
    }

    private fun removeProcessedFrames(directoryPath: String) {
        val directory = File(directoryPath)
        directory.listFiles { file ->
            file.isFile && file.name.matches(Regex("frame_\\d+\\.jpg"))
        }?.forEach { frame ->
            if (!frame.delete()) {
                Log.w(TAG, "Unable to remove processed capture frame: ${frame.name}")
            }
        }
    }

    private fun showOverlayResult(report: AnalysisReport, directory: String) {
        val prediction = report.prediction
        val signalIsDefined = prediction?.direction?.let {
            it == PredictionDirection.BUY || it == PredictionDirection.SELL
        } == true
        val summary = when {
            prediction == null -> getString(R.string.analysis_not_ready)
            prediction.direction == PredictionDirection.BUY -> getString(R.string.overlay_direction_up)
            prediction.direction == PredictionDirection.SELL -> getString(R.string.overlay_direction_down)
            !report.tradingScreenDetected -> getString(R.string.vision_warning_interface_none)
            else -> getString(R.string.overlay_no_signal)
        }
        val confidence = prediction?.takeIf { signalIsDefined }
            ?.let { getString(R.string.forecast_confidence_inline, (it.confidence * 100).toInt()) }
            ?: getString(R.string.forecast_confidence_undefined)
        val expiration = prediction?.expiration?.seconds?.let {
            getString(R.string.forecast_expiration_seconds, it)
        } ?: getString(R.string.forecast_expiration_undefined)
        val resultText = listOf(
            getString(R.string.screenshot_analyzed),
            summary,
            getString(R.string.overlay_expiration_inline, expiration),
            getString(R.string.overlay_confidence_inline, confidence),
            when {
                !report.tradingScreenDetected -> getString(R.string.not_enough_analysis_data)
                prediction?.direction == PredictionDirection.NO_TRADE ->
                    prediction.reasons.lastOrNull() ?: getString(R.string.not_enough_analysis_data)
                else -> report.marketMovement
            },
        ).joinToString("\n")
        val capturedImage = File(
            directory,
            "frame_%03d.jpg".format(Locale.US, report.processedFrames.coerceAtLeast(1)),
        )
        val previewImage = File(filesDir, "last-capture-preview.jpg")
        val imagePath = try {
            capturedImage.copyTo(previewImage, overwrite = true).absolutePath
        } catch (error: IOException) {
            Log.e(TAG, "Unable to preserve the latest capture preview", error)
            null
        }
        startService(
            Intent(this, ScreenCaptureService::class.java)
                .setAction(ScreenCaptureService.ACTION_SHOW_RESULT)
                .putExtra(ScreenCaptureService.EXTRA_RESULT_TEXT, resultText)
                .putExtra(ScreenCaptureService.EXTRA_RESULT_IMAGE, imagePath)
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

    private fun showOverlayExecutionStatus(state: TradingSessionState) {
        if (!captureSessionActive || isFinishing) return
        val message = listOf(
            getString(autoStatusResource(state.status)),
            getString(executionStatusResource(state.executionStatus)),
            state.lastSkipReason,
        ).joinToString("\n")
        startService(
            Intent(this, ScreenCaptureService::class.java)
                .setAction(ScreenCaptureService.ACTION_SHOW_RESULT)
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
    onStartCapture: () -> Unit,
    onManualCapture: () -> Unit,
    onToggleRecording: () -> Unit,
    onStopSession: () -> Unit,
    autoTradingState: TradingSessionState,
    onStartAutoTrading: () -> Unit,
    onStopAutoTrading: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onEmergencyStop: () -> Unit,
    onSetMode: (TradingMode) -> Unit,
    onConfirmEmergency: (Boolean) -> Unit,
    onSetMartingale: (Boolean) -> Unit,
    onSetInitialAmount: (Double) -> Boolean,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            stringResource(R.string.app_name),
                            fontWeight = FontWeight.SemiBold,
                        )
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
            AutoTradingCard(
                state = autoTradingState,
                onToggle = onStartAutoTrading,
                onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                onEmergencyStop = onEmergencyStop,
                onSetMode = onSetMode,
                onConfirmEmergency = onConfirmEmergency,
                onSetMartingale = onSetMartingale,
                onSetInitialAmount = onSetInitialAmount,
            )
            AnalysisCard(
                resultStatus = stringResource(resultStatus),
                report = analysisReport,
                processing = analysisRunning,
                error = analysisError,
            )
            SafetyNote()
        }
    }
}

@Composable
private fun StatusCard(title: String, status: String, action: String, onAction: () -> Unit) {
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .padding(top = 5.dp)
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error),
                )
                Text(status, style = MaterialTheme.typography.bodyMedium)
            }
            OutlinedButton(onClick = onAction, modifier = Modifier.fillMaxWidth()) {
                Text(action)
            }
        }
    }
}

@Composable
private fun AutoTradingCard(
    state: TradingSessionState,
    onToggle: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onEmergencyStop: () -> Unit,
    onSetMode: (TradingMode) -> Unit,
    onConfirmEmergency: (Boolean) -> Unit,
    onSetMartingale: (Boolean) -> Unit,
    onSetInitialAmount: (Double) -> Boolean,
) {
    var showEmergencyConfirmation by remember { mutableStateOf(false) }
    var showMartingaleConfirmation by remember { mutableStateOf(false) }
    var initialAmountText by remember(state.initialAmount) {
        mutableStateOf(state.initialAmount.toString())
    }
    var initialAmountError by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.auto_trading_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onSetMode(TradingMode.MANUAL) },
                    enabled = !state.running,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.mode_manual))
                }
                OutlinedButton(
                    onClick = { onSetMode(TradingMode.AUTO) },
                    enabled = !state.running && !state.emergencyLocked,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.mode_auto))
                }
            }
            Text(
                stringResource(R.string.accessibility_setup_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onOpenAccessibilitySettings,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.accessibility_setup_button))
            }
            OutlinedTextField(
                value = initialAmountText,
                onValueChange = { initialAmountText = it },
                enabled = !state.running && state.consecutiveLosses == 0,
                label = { Text(stringResource(R.string.auto_initial_amount)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                isError = initialAmountError,
                supportingText = if (initialAmountError) {
                    { Text(stringResource(R.string.initial_amount_invalid)) }
                } else null,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = {
                    val amount = initialAmountText.replace(',', '.').toDoubleOrNull()
                    initialAmountError = amount == null || !onSetInitialAmount(amount)
                },
                enabled = !state.running && state.consecutiveLosses == 0,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.initial_amount_save))
            }
            AnalysisValue(
                stringResource(R.string.auto_status_label),
                stringResource(autoStatusResource(state.status)),
            )
            AnalysisValue(
                stringResource(R.string.auto_mode_label),
                if (state.mode == TradingMode.MANUAL) stringResource(R.string.mode_manual)
                else stringResource(R.string.mode_auto),
            )
            AnalysisValue(
                stringResource(R.string.auto_last_analysis_time),
                state.lastAnalysisTimeMillis?.let {
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale("ru")).format(Date(it))
                } ?: stringResource(R.string.undetermined),
            )
            AnalysisValue(stringResource(R.string.instrument_label), state.instrument)
            AnalysisValue(stringResource(R.string.price_label), state.currentPrice)
            AnalysisValue(
                stringResource(R.string.auto_forecast_label),
                when (state.forecast) {
                    PredictionDirection.BUY -> stringResource(R.string.forecast_buy)
                    PredictionDirection.SELL -> stringResource(R.string.forecast_sell)
                    PredictionDirection.NO_TRADE -> stringResource(R.string.forecast_no_trade)
                },
            )
            AnalysisValue(
                stringResource(R.string.forecast_confidence),
                "${(state.confidence * 100).toInt()}%",
            )
            AnalysisValue(
                stringResource(R.string.forecast_expiration),
                state.expirationSeconds?.let {
                    stringResource(R.string.forecast_expiration_seconds, it)
                } ?: stringResource(R.string.forecast_expiration_undefined),
            )
            AnalysisValue(stringResource(R.string.auto_capture_state), state.captureState)
            AnalysisValue(stringResource(R.string.auto_buy_button), state.buyButtonState)
            AnalysisValue(stringResource(R.string.auto_sell_button), state.sellButtonState)
            AnalysisValue(
                stringResource(R.string.auto_execution_state),
                stringResource(executionStatusResource(state.executionStatus)),
            )
            AnalysisValue(stringResource(R.string.auto_completed_cycles), state.completedCycles.toString())
            AnalysisValue(stringResource(R.string.auto_skipped_trades), state.skippedTrades.toString())
            AnalysisValue(stringResource(R.string.auto_confirmed_trades), state.confirmedTrades.toString())
            AnalysisValue(stringResource(R.string.auto_initial_amount), "%.2f".format(state.initialAmount))
            AnalysisValue(stringResource(R.string.auto_current_amount), "%.2f".format(state.currentAmount))
            AnalysisValue(stringResource(R.string.auto_max_amount), "%.2f".format(state.maxAmount))
            AnalysisValue(stringResource(R.string.auto_consecutive_losses), state.consecutiveLosses.toString())
            AnalysisValue(stringResource(R.string.auto_confirmed_wins), state.confirmedWins.toString())
            AnalysisValue(stringResource(R.string.auto_confirmed_losses), state.confirmedLosses.toString())
            AnalysisValue(stringResource(R.string.auto_confirmed_draws), state.confirmedDraws.toString())
            AnalysisValue(stringResource(R.string.auto_daily_loss), "%.2f".format(state.dailyLoss))
            AnalysisValue(stringResource(R.string.auto_martingale_doublings), state.martingaleDoublings.toString())
            AnalysisValue(stringResource(R.string.auto_martingale_state),
                if (state.martingaleEnabled) stringResource(R.string.martingale_enabled)
                else stringResource(R.string.martingale_disabled))
            AnalysisValue(stringResource(R.string.auto_entry_decision),
                stringResource(entryDecisionResource(state.entryDecision)))
            AnalysisValue(stringResource(R.string.auto_last_skip), state.lastSkipReason)
            AnalysisValue(stringResource(R.string.auto_last_result), state.lastConfirmedResult)
            if (state.emergencyLocked) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            stringResource(R.string.emergency_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            state.emergencyReason ?: stringResource(R.string.emergency_default_text),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Button(
                            onClick = { showEmergencyConfirmation = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.emergency_confirm_button))
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = {
                    if (state.martingaleEnabled) onSetMartingale(false)
                    else showMartingaleConfirmation = true
                },
                enabled = !state.running,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.martingaleEnabled) stringResource(R.string.martingale_disable)
                    else stringResource(R.string.martingale_enable_confirm))
            }
            Button(
                onClick = onToggle,
                enabled = state.running || (
                    state.mode == TradingMode.AUTO &&
                        !state.emergencyLocked &&
                    state.tradeCycleState != com.autoscanner.trader.trading.TradeCycleState.BLOCKED_UNCONFIRMED &&
                    state.tradeCycleState != com.autoscanner.trader.trading.TradeCycleState.OBSERVING
                    ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (state.running) R.string.auto_stop_button
                        else R.string.auto_start_button,
                    ),
                )
            }
            OutlinedButton(
                onClick = onEmergencyStop,
                enabled = state.running,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.auto_emergency_button))
            }
        }
    }
    if (showEmergencyConfirmation) {
        var resetAmount by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { showEmergencyConfirmation = false },
            title = { Text(stringResource(R.string.emergency_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.emergency_confirm_warning))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { resetAmount = true },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.emergency_reset_amount))
                        }
                        OutlinedButton(
                            onClick = { resetAmount = false },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.emergency_keep_amount))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onConfirmEmergency(resetAmount)
                    showEmergencyConfirmation = false
                }) {
                    Text(stringResource(R.string.emergency_confirm_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmergencyConfirmation = false }) {
                    Text(stringResource(R.string.cancel_button))
                }
            },
        )
    }
    if (showMartingaleConfirmation) {
        AlertDialog(
            onDismissRequest = { showMartingaleConfirmation = false },
            title = { Text(stringResource(R.string.martingale_confirm_title)) },
            text = { Text(stringResource(R.string.martingale_confirm_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    onSetMartingale(true)
                    showMartingaleConfirmation = false
                }) {
                    Text(stringResource(R.string.martingale_confirm_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { showMartingaleConfirmation = false }) {
                    Text(stringResource(R.string.cancel_button))
                }
            },
        )
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
                Text(
                    stringResource(R.string.frames_captured, capturedFrames),
                    style = MaterialTheme.typography.bodySmall,
                )
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
                        Text(stringResource(if (recordingEnabled) R.string.recording_stop else R.string.recording_start))
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
            if (processing) {
                Text(stringResource(R.string.analysis_processing_frames))
            }
            if (report != null) {
                AnalysisDetails(report)
            }
            if (error != null) {
                Text(
                    stringResource(R.string.analysis_error_details, error),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                stringResource(R.string.trade_execution_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
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
        AnalysisValue(
            stringResource(R.string.trading_interface_label),
            stringResource(
                if (report.tradingScreenDetected) R.string.detected
                else R.string.vision_warning_interface_none,
            ),
        )
        AnalysisValue(stringResource(R.string.instrument_label), report.instrument)
        AnalysisValue(stringResource(R.string.price_label), report.price)
        AnalysisValue(stringResource(R.string.amount_label), report.amount)
        AnalysisValue(stringResource(R.string.timeframe_label), report.timeframe)
        AnalysisValue(stringResource(R.string.timer_label), report.timer)
        AnalysisValue(stringResource(R.string.expiration_label), report.expiration)
        report.prediction?.let { prediction ->
            val forecastText = when (prediction.direction) {
                PredictionDirection.BUY -> stringResource(R.string.forecast_buy)
                PredictionDirection.SELL -> stringResource(R.string.forecast_sell)
                PredictionDirection.NO_TRADE -> stringResource(R.string.forecast_no_trade)
            }
            AnalysisValue(stringResource(R.string.forecast_prediction_label), forecastText)
            AnalysisValue(
                stringResource(R.string.forecast_confidence),
                if (prediction.direction == PredictionDirection.NO_TRADE) {
                    stringResource(R.string.forecast_confidence_undefined)
                } else {
                    "${(prediction.confidence * 100).toInt()}%"
                },
            )
            val expirationText = prediction.expiration.seconds?.let {
                stringResource(R.string.forecast_expiration_seconds, it)
            } ?: stringResource(R.string.forecast_expiration_undefined)
            AnalysisValue(stringResource(R.string.forecast_expiration), expirationText)
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
            AnalysisValue(
                stringResource(R.string.forecast_patterns),
                prediction.candlePatterns.takeIf { it.isNotEmpty() }
                    ?.joinToString("; ") { "${it.name} (${(it.confidence * 100).toInt()}%)" }
                    ?: stringResource(R.string.forecast_no_patterns),
            )
            val supportText = prediction.supportResistance.support.takeIf { it.isNotEmpty() }
                ?.joinToString("; ") { "${it.confirmations} — ${it.confidence.times(100).toInt()}%" }
                ?: stringResource(R.string.forecast_no_levels)
            val resistanceText = prediction.supportResistance.resistance.takeIf { it.isNotEmpty() }
                ?.joinToString("; ") { "${it.confirmations} — ${it.confidence.times(100).toInt()}%" }
                ?: stringResource(R.string.forecast_no_levels)
            AnalysisValue(stringResource(R.string.forecast_support), supportText)
            AnalysisValue(stringResource(R.string.forecast_resistance), resistanceText)
            AnalysisValue(
                stringResource(R.string.forecast_analysis_time),
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale("ru")).format(Date(prediction.analysisTimeMillis)),
            )
            Text(stringResource(R.string.forecast_reasons), fontWeight = FontWeight.SemiBold)
            prediction.reasons.forEach { reason ->
                Text(stringResource(R.string.analysis_list_item, reason), style = MaterialTheme.typography.bodySmall)
            }
            if (prediction.risks.isNotEmpty()) {
                Text(stringResource(R.string.forecast_risks), fontWeight = FontWeight.SemiBold)
                prediction.risks.forEach { risk ->
                    Text(
                        stringResource(R.string.analysis_list_item, "${risk.name}: ${risk.description}"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (prediction.expiration.limitations.isNotEmpty()) {
                Text(stringResource(R.string.forecast_limitations), fontWeight = FontWeight.SemiBold)
                prediction.expiration.limitations.forEach { limitation ->
                    Text(
                        stringResource(R.string.analysis_list_item, limitation),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        AnalysisValue(
            stringResource(R.string.candles_label),
            report.candles.size.takeIf { it > 0 }?.let {
                stringResource(R.string.candles_count, it)
            } ?: stringResource(R.string.not_detected),
        )
        AnalysisValue(
            stringResource(R.string.market_movement_label),
            report.marketMovement,
        )
        val chart = report.frames.lastOrNull { it.chart.detected }?.chart
        val chartBounds = if (
            chart?.left != null && chart.top != null && chart.right != null && chart.bottom != null
        ) {
            stringResource(
                R.string.button_coordinates,
                chart.left,
                chart.top,
                chart.right,
                chart.bottom,
            )
        } else {
            stringResource(R.string.undetermined)
        }
        AnalysisValue(stringResource(R.string.chart_region_label), chartBounds)
        report.candles.take(MAX_VISIBLE_CANDLE_DETAILS).forEach { candle ->
            Text(
                stringResource(
                    R.string.candle_detail,
                    candle.index,
                    candle.color,
                    candle.left,
                    candle.top,
                    candle.right,
                    candle.bottom,
                    candle.wickTop,
                    candle.wickBottom,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        AnalysisValue(
            stringResource(R.string.indicators_label),
            report.indicators.takeIf { it.isNotEmpty() }?.joinToString(", ")
                ?: stringResource(R.string.undetermined),
        )
        AnalysisValue(
            stringResource(R.string.other_elements_label),
            report.frames.lastOrNull { it.otherElements.isNotEmpty() }
                ?.otherElements
                ?.joinToString(", ")
                ?: stringResource(R.string.undetermined),
        )
        TradingButtonResult(
            label = stringResource(R.string.buy_button_label),
            button = report.buyButton,
        )
        TradingButtonResult(
            label = stringResource(R.string.sell_button_label),
            button = report.sellButton,
        )
        AnalysisValue(stringResource(R.string.image_quality_label), report.imageQuality)
        if (report.warnings.isNotEmpty()) {
            Text(
                stringResource(R.string.analysis_warnings_label),
                fontWeight = FontWeight.SemiBold,
            )
            report.warnings.forEach {
                Text(stringResource(R.string.analysis_list_item, it), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (report.errors.isNotEmpty()) {
            Text(
                stringResource(R.string.analysis_errors_label),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error,
            )
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

private const val MAX_VISIBLE_CANDLE_DETAILS = 8

private fun executionStatusResource(status: ExecutionStatus): Int = when (status) {
    ExecutionStatus.NOT_ATTEMPTED -> R.string.execution_not_attempted
    ExecutionStatus.BLOCKED_NO_APPROVED_MECHANISM -> R.string.execution_unavailable
    ExecutionStatus.BLOCKED_SAFETY -> R.string.execution_blocked_safety
    ExecutionStatus.RESULT_NOT_CONFIRMED -> R.string.execution_result_unconfirmed
    ExecutionStatus.EXECUTION_IN_PROGRESS -> R.string.execution_in_progress
    ExecutionStatus.ACTION_PERFORMED_ACCEPTANCE_UNCONFIRMED -> R.string.execution_action_unconfirmed
    ExecutionStatus.PLATFORM_ACCEPTANCE_CONFIRMED -> R.string.execution_acceptance_confirmed
    ExecutionStatus.EXECUTION_IMPOSSIBLE -> R.string.execution_impossible
}

private fun entryDecisionResource(decision: EntryDecision): Int = when (decision) {
    EntryDecision.ENTER_NOW -> R.string.entry_enter_now
    EntryDecision.WAIT_FOR_CANDLE_CLOSE -> R.string.entry_wait_close
    EntryDecision.ENTER_AT_NEXT_CANDLE -> R.string.entry_next_candle
    EntryDecision.WAIT_FOR_CONFIRMATION -> R.string.entry_wait_confirmation
    EntryDecision.NO_TRADE -> R.string.entry_no_trade
}

private fun autoStatusResource(status: AutoTradingStatus): Int = when (status) {
    AutoTradingStatus.WAITING -> R.string.auto_status_waiting
    AutoTradingStatus.CHECKING -> R.string.auto_status_checking
    AutoTradingStatus.CHECKING_SCREEN -> R.string.auto_status_screen_check
    AutoTradingStatus.CAPTURING -> R.string.auto_status_capturing
    AutoTradingStatus.ANALYZING -> R.string.auto_status_analyzing
    AutoTradingStatus.DETERMINING_DIRECTION -> R.string.auto_status_direction
    AutoTradingStatus.SELECTING_EXPIRATION -> R.string.auto_status_expiration
    AutoTradingStatus.SAFETY_CHECK -> R.string.auto_status_safety
    AutoTradingStatus.EXECUTING -> R.string.auto_status_executing
    AutoTradingStatus.TRADE_SKIPPED -> R.string.auto_status_skipped
    AutoTradingStatus.EXECUTION_UNAVAILABLE -> R.string.auto_status_execution_unavailable
    AutoTradingStatus.WAITING_NEXT -> R.string.auto_status_waiting_next
    AutoTradingStatus.STOPPED -> R.string.auto_status_stopped
    AutoTradingStatus.EMERGENCY_STOPPED -> R.string.auto_status_emergency
    AutoTradingStatus.ERROR -> R.string.auto_status_error
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
private fun TradingButtonResult(
    label: String,
    button: com.autoscanner.trader.vision.TradingButton,
) {
    val coordinates = if (button.left != null && button.top != null &&
        button.right != null && button.bottom != null
    ) {
        stringResource(
            R.string.button_coordinates,
            button.left,
            button.top,
            button.right,
            button.bottom,
        )
    } else {
        stringResource(R.string.undetermined)
    }
    AnalysisValue(
        label,
        stringResource(
            R.string.button_result_format,
            button.message,
            button.state,
            stringResource(R.string.confidence_percent, (button.confidence * 100).toInt()),
            coordinates,
        ),
    )
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
