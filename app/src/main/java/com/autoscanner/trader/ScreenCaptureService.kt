package com.autoscanner.trader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale

class ScreenCaptureService : Service() {
    @Volatile private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var captureDirectory: File? = null
    private var sampleCount = 0
    private var lastSampleTimeMs = 0L
    private var captureStartedAtMs = 0L
    @Volatile private var cycleActive = false
    @Volatile private var sessionEnded = false
    private var singleFrameCapture = false
    private var captureTimeout: Runnable? = null
    private var captureStartFrameCallback: Choreographer.FrameCallback? = null
    @Volatile private var captureNotBeforeNanos = 0L
    @Volatile private var capturePreparing = false
    @Volatile private var captureGeneration = 0L
    @Volatile private var analysisPending = false
    private var analysisTimeout: Runnable? = null
    private var recordingEnabled = false
    private var sessionInitializationPending = false
    private var overlayRoot: FrameLayout? = null
    private var overlayContent: LinearLayout? = null
    private var overlayBubble: Button? = null
    private var overlayStatus: TextView? = null
    private var overlayResult: TextView? = null
    private var overlayManualConfirmation: Button? = null
    private var overlayThumbnail: ImageView? = null
    private var lastThumbnail: Bitmap? = null
    private var overlayLayoutParams: WindowManager.LayoutParams? = null
    private var overlayAttached = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (!sessionEnded) {
                endSession(
                    errorResource = R.string.capture_permission_lost,
                    projectionAlreadyStopped = true,
                )
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val thread = HandlerThread("screen-capture").also { it.start() }
        handlerThread = thread
        handler = Handler(thread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SESSION) {
            endSession()
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (sessionEnded) return START_NOT_STICKY

        if (intent?.action == ACTION_QUERY_STATE) {
            if (projection != null) {
                sendBroadcast(Intent(ACTION_SESSION_READY).setPackage(packageName))
                sendBroadcast(
                    Intent(ACTION_RECORDING_STATE)
                        .setPackage(packageName)
                        .putExtra(EXTRA_RECORDING_ENABLED, recordingEnabled),
                )
            } else {
                sessionEnded = true
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_CAPTURE_SINGLE -> {
                if (projection == null) {
                    sendCaptureResult(succeeded = false, errorResource = R.string.capture_session_inactive)
                    sessionEnded = true
                    stopSelf(startId)
                } else if (capturePreparing || cycleActive || analysisPending) {
                    Log.i(TAG, "Ignoring duplicate capture request while a cycle is active")
                } else {
                    startCaptureWindow(singleFrame = true)
                }
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_RECORDING -> {
                if (capturePreparing || cycleActive || analysisPending) {
                    Log.i(TAG, "Ignoring recording toggle while a capture cycle is active")
                    return START_NOT_STICKY
                }
                if (projection == null) {
                    sendCaptureResult(succeeded = false, errorResource = R.string.capture_session_inactive)
                    sessionEnded = true
                    stopSelf(startId)
                } else {
                    recordingEnabled = !recordingEnabled
                    sendBroadcast(
                        Intent(ACTION_RECORDING_STATE)
                            .setPackage(packageName)
                            .putExtra(EXTRA_RECORDING_ENABLED, recordingEnabled),
                    )
                    updateOverlayStatus(
                        getString(if (recordingEnabled) R.string.recording_enabled else R.string.recording_disabled),
                    )
                    if (recordingEnabled) {
                        if (!cycleActive) {
                            startCaptureWindow(
                                singleFrame = false,
                            )
                        }
                    }
                    updateOverlayControls()
                }
                return START_NOT_STICKY
            }
            ACTION_SHOW_RESULT -> {
                analysisPending = false
                analysisTimeout?.let(mainHandler::removeCallbacks)
                analysisTimeout = null
                val result = intent.getStringExtra(EXTRA_RESULT_TEXT)
                if (!result.isNullOrBlank()) {
                    overlayResult?.text = result
                    overlayManualConfirmation?.apply {
                        visibility = if (intent.getBooleanExtra(EXTRA_HAS_SIGNAL, false)) {
                            View.VISIBLE
                        } else {
                            View.GONE
                        }
                        isEnabled = true
                    }
                    if (intent.hasExtra(EXTRA_RESULT_IMAGE)) {
                        updateOverlayThumbnailText(intent.getStringExtra(EXTRA_RESULT_IMAGE))
                    }
                    updateOverlayStatus(getString(R.string.analysis_complete))
                }
                setOverlayVisible(!intent.getBooleanExtra(EXTRA_KEEP_HIDDEN, false))
                return START_NOT_STICKY
            }
            ACTION_SHOW_ERROR -> {
                analysisPending = false
                analysisTimeout?.let(mainHandler::removeCallbacks)
                analysisTimeout = null
                val message = intent.getStringExtra(EXTRA_RESULT_TEXT)
                    ?: getString(R.string.analysis_failed)
                overlayResult?.text = message
                overlayManualConfirmation?.apply {
                    visibility = View.GONE
                    isEnabled = true
                }
                updateOverlayStatus(message)
                setOverlayVisible(true)
                return START_NOT_STICKY
            }
        }

        if (intent?.action != ACTION_START_SESSION || projection != null ||
            sessionInitializationPending
        ) {
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = intent?.let(::getProjectionData)
        if (resultCode == 0 || resultData == null) {
            endSession(R.string.capture_permission_error)
            return START_NOT_STICKY
        }

        try {
            startCaptureForeground()
            sessionInitializationPending = true
            handler?.post(
                {
                    try {
                        beginCaptureSession(resultCode, resultData)
                    } catch (_: SecurityException) {
                        endSession(R.string.capture_permission_error)
                    } catch (_: IOException) {
                        endSession(R.string.capture_storage_error)
                    } catch (_: IllegalStateException) {
                        endSession(R.string.capture_service_error)
                    } catch (_: RuntimeException) {
                        endSession(R.string.capture_service_error)
                    }
                },
            )
        } catch (_: SecurityException) {
            endSession(R.string.capture_permission_error)
        } catch (_: IllegalStateException) {
            endSession(R.string.capture_service_error)
        } catch (_: RuntimeException) {
            endSession(R.string.capture_service_error)
        }
        return START_NOT_STICKY
    }

    @Synchronized
    private fun beginCaptureSession(resultCode: Int, resultData: Intent) {
        val mediaProjection = getSystemService(MediaProjectionManager::class.java)
            .getMediaProjection(resultCode, resultData)
        projection = mediaProjection
        mediaProjection.registerCallback(projectionCallback, handler)

        val (width, height, densityDpi) = screenDimensions()
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        reader.setOnImageAvailableListener(::onImageAvailable, handler)
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "AutoScanner screen capture",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler,
        )
        sessionInitializationPending = false
        mainHandler.post {
            if (!sessionEnded) createOverlay()
            updateOverlayStatus(getString(R.string.capture_session_ready))
        }
        sendBroadcast(
            Intent(ACTION_SESSION_READY)
                .setPackage(packageName),
        )
    }

    private fun startCaptureWindow(singleFrame: Boolean) {
        if (sessionEnded || projection == null || virtualDisplay == null) return
        if (capturePreparing || cycleActive || analysisPending) {
            sendCaptureResult(succeeded = false, errorResource = R.string.capture_busy)
            updateOverlayStatus(getString(R.string.capture_busy))
            return
        }
        capturePreparing = true
        captureGeneration += 1
        try {
            val directory = File(
                getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir,
                "screen-captures/${System.currentTimeMillis()}",
            )
            if (!directory.mkdirs()) throw IOException("Unable to create capture directory")
            captureDirectory = directory
            sampleCount = 0
            lastSampleTimeMs = 0L
            captureStartedAtMs = 0L
            singleFrameCapture = singleFrame
            overlayManualConfirmation?.apply {
                visibility = View.GONE
                isEnabled = true
            }
            cycleActive = false
            setOverlayVisible(false)
            updateOverlayStatus(getString(R.string.capture_getting_frame))
            awaitOverlayRemovalFrames(captureGeneration, requiredFrames = OVERLAY_SETTLE_FRAMES)
        } catch (error: IOException) {
            Log.e(TAG, "Unable to prepare capture directory", error)
            failCaptureCycle(R.string.capture_storage_error)
        }
    }

    private fun awaitOverlayRemovalFrames(generation: Long, requiredFrames: Int) {
        if (generation != captureGeneration || !capturePreparing || sessionEnded) return
        val callback = Choreographer.FrameCallback {
            captureStartFrameCallback = null
            if (generation != captureGeneration || !capturePreparing || sessionEnded) return@FrameCallback
            if (requiredFrames > 1) {
                awaitOverlayRemovalFrames(generation, requiredFrames - 1)
            } else {
                armCaptureAfterOverlayHidden()
            }
        }
        captureStartFrameCallback = callback
        Choreographer.getInstance().postFrameCallback(callback)
    }

    private fun armCaptureAfterOverlayHidden() {
        captureNotBeforeNanos = SystemClock.elapsedRealtimeNanos()
        captureStartedAtMs = System.currentTimeMillis()
        lastSampleTimeMs = 0L
        capturePreparing = false
        cycleActive = true
        captureTimeout = Runnable {
            if (sampleCount == 0) {
                failCaptureCycle(R.string.capture_no_frames)
            } else {
                finishCaptureCycle()
            }
        }.also {
            handler?.postDelayed(
                it,
                if (singleFrameCapture) SINGLE_FRAME_TIMEOUT_MS else CAPTURE_DURATION_MS,
            )
        }
    }

    private fun cancelCaptureCycle() {
        captureGeneration += 1
        captureStartFrameCallback?.let(Choreographer.getInstance()::removeFrameCallback)
        captureStartFrameCallback = null
        captureTimeout?.let { handler?.removeCallbacks(it) }
        captureTimeout = null
        capturePreparing = false
        cycleActive = false
        captureNotBeforeNanos = Long.MAX_VALUE
    }

    private fun failCaptureCycle(errorResource: Int) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { failCaptureCycle(errorResource) }
            return
        }
        if (!capturePreparing && !cycleActive) return
        cancelCaptureCycle()
        recordingEnabled = false
        sendBroadcast(
            Intent(ACTION_RECORDING_STATE)
                .setPackage(packageName)
                .putExtra(EXTRA_RECORDING_ENABLED, false),
        )
        sendCaptureResult(succeeded = false, errorResource = errorResource)
        val message = getString(errorResource)
        overlayResult?.text = message
        updateOverlayStatus(message)
        setOverlayVisible(true)
    }

    @Synchronized
    private fun onImageAvailable(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (_: IllegalStateException) {
            if (!sessionEnded) endSession(R.string.capture_processing_error)
            null
        } ?: return

        image.use {
            if (!cycleActive || it.timestamp < captureNotBeforeNanos) return
            val now = System.currentTimeMillis()
            if (singleFrameCapture || now - lastSampleTimeMs >= SAMPLE_INTERVAL_MS) {
                if (sampleCount == 0) updateOverlayStatus(getString(R.string.screen_received))
                saveSample(it, now)
                lastSampleTimeMs = now
                if (singleFrameCapture && cycleActive) finishCaptureCycle()
            }
        }
    }

    private fun saveSample(image: Image, timestampMillis: Long) {
        var bitmapToRecycle: Bitmap? = null
        try {
            val plane = image.planes.firstOrNull() ?: return
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val paddedWidth = rowStride / pixelStride
            val paddedBitmap = Bitmap.createBitmap(
                paddedWidth,
                image.height,
                Bitmap.Config.ARGB_8888,
            )
            bitmapToRecycle = paddedBitmap
            plane.buffer.rewind()
            paddedBitmap.copyPixelsFromBuffer(plane.buffer)
            val frameBitmap = Bitmap.createBitmap(paddedBitmap, 0, 0, image.width, image.height)
            bitmapToRecycle = frameBitmap
            paddedBitmap.recycle()

            val scale = minOf(
                1f,
                MAX_FRAME_DIMENSION.toFloat() / maxOf(frameBitmap.width, frameBitmap.height),
            )
            val scaledBitmap = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    frameBitmap,
                    (frameBitmap.width * scale).toInt().coerceAtLeast(1),
                    (frameBitmap.height * scale).toInt().coerceAtLeast(1),
                    true,
                ).also {
                    frameBitmap.recycle()
                    bitmapToRecycle = it
                }
            } else {
                frameBitmap
            }

            val output = File(
                captureDirectory ?: throw IOException("Capture directory is unavailable"),
                String.format(Locale.US, "frame_%03d.jpg", sampleCount + 1),
            )
            FileOutputStream(output).use { stream ->
                if (!scaledBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)) {
                    throw IOException("Unable to encode captured frame")
                }
            }
            output.setLastModified(timestampMillis)
            sampleCount += 1
        } catch (_: IOException) {
            failCaptureCycle(R.string.capture_storage_error)
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to process captured image", error)
            failCaptureCycle(R.string.capture_processing_error)
        } finally {
            bitmapToRecycle?.takeUnless(Bitmap::isRecycled)?.recycle()
        }
    }

    private fun finishCaptureCycle() {
        if (sessionEnded || !cycleActive) return
        cycleActive = false
        captureTimeout?.let { handler?.removeCallbacks(it) }
        captureTimeout = null
        if (recordingEnabled) {
            recordingEnabled = false
            sendBroadcast(
                Intent(ACTION_RECORDING_STATE)
                    .setPackage(packageName)
                    .putExtra(EXTRA_RECORDING_ENABLED, false),
            )
        }
        sendCaptureResult(succeeded = true)
        analysisPending = true
        analysisTimeout?.let(mainHandler::removeCallbacks)
        analysisTimeout = Runnable {
            analysisPending = false
            val message = getString(R.string.analysis_failed)
            overlayResult?.text = message
            overlayManualConfirmation?.visibility = View.GONE
            updateOverlayStatus(message)
            setOverlayVisible(true)
        }.also { mainHandler.postDelayed(it, ANALYSIS_TIMEOUT_MS) }
        mainHandler.post {
            updateOverlayStatus(getString(R.string.analysis_image_processing))
        }
    }

    private fun sendCaptureResult(succeeded: Boolean, errorResource: Int? = null) {
        sendBroadcast(
            Intent(ACTION_CAPTURE_COMPLETE)
                .setPackage(packageName)
                .putExtra(EXTRA_SUCCEEDED, succeeded)
                .putExtra(EXTRA_FRAMES, sampleCount)
                .putExtra(EXTRA_ERROR_RESOURCE, errorResource ?: 0)
                .putExtra(EXTRA_CAPTURE_DIRECTORY, captureDirectory?.absolutePath)
                .putExtra(EXTRA_CAPTURE_STARTED_AT, captureStartedAtMs)
                .putExtra(EXTRA_CAPTURE_DURATION, CAPTURE_DURATION_MS),
        )
    }

    private fun endSession(
        errorResource: Int? = null,
        projectionAlreadyStopped: Boolean = false,
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { endSession(errorResource, projectionAlreadyStopped) }
            return
        }
        cancelCaptureCycle()
        analysisPending = false
        analysisTimeout?.let(mainHandler::removeCallbacks)
        analysisTimeout = null
        if (sessionEnded) {
            releaseProjectionResources(projectionAlreadyStopped)
            removeOverlayOnMainThread()
            return
        }
        sessionEnded = true
        sessionInitializationPending = false
        recordingEnabled = false
        handler?.removeCallbacksAndMessages(null)
        if (errorResource != null) sendCaptureResult(succeeded = false, errorResource = errorResource)
        releaseProjectionResources(projectionAlreadyStopped)
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (errorResource != null) {
            overlayResult?.text = getString(errorResource)
            overlayStatus?.text = getString(errorResource)
            attachOverlay()
        } else {
            removeOverlayOnMainThread()
            stopSelf()
        }
    }

    @Synchronized
    private fun releaseProjectionResources(projectionAlreadyStopped: Boolean = false) {
        val display = virtualDisplay
        virtualDisplay = null
        try {
            display?.release()
        } catch (error: IllegalStateException) {
            Log.w(TAG, "VirtualDisplay was already released", error)
        }
        val reader = imageReader
        imageReader = null
        try {
            reader?.setOnImageAvailableListener(null, null)
        } catch (error: IllegalStateException) {
            Log.w(TAG, "ImageReader listener was already detached", error)
        }
        try {
            reader?.close()
        } catch (error: IllegalStateException) {
            Log.w(TAG, "ImageReader was already closed", error)
        }
        val mediaProjection = projection
        projection = null
        mediaProjection?.let {
            if (!projectionAlreadyStopped) {
                try {
                    it.unregisterCallback(projectionCallback)
                } catch (error: IllegalStateException) {
                    Log.w(TAG, "MediaProjection callback was already detached", error)
                } catch (error: IllegalArgumentException) {
                    Log.w(TAG, "MediaProjection callback was not registered", error)
                }
                try {
                    it.stop()
                } catch (error: IllegalStateException) {
                    Log.w(TAG, "MediaProjection was already stopped", error)
                }
            }
        }
    }

    private fun screenDimensions(): Triple<Int, Int, Int> {
        val windowManager = getSystemService(WindowManager::class.java)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return Triple(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
    }

    private fun startCaptureForeground() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.capture_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )

        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.capture_notification_text))
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @Suppress("DEPRECATION")
    private fun getProjectionData(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

    override fun onDestroy() {
        if (!sessionEnded) endSession(R.string.capture_interrupted)
        handler?.removeCallbacksAndMessages(null)
        releaseProjectionResources(projectionAlreadyStopped = true)
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
        mainHandler.removeCallbacksAndMessages(null)
        removeOverlay()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        endSession()
        super.onTaskRemoved(rootIntent)
    }

    private fun createOverlay() {
        if (!android.provider.Settings.canDrawOverlays(this)) return
        if (overlayRoot != null) {
            attachOverlay()
            return
        }

        val root = FrameLayout(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(0xE6222631.toInt())
                cornerRadius = dp(16).toFloat()
            }
        }
        val header = TextView(this).apply {
            text = getString(R.string.overlay_title)
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(4), dp(4), dp(4), dp(8))
            setOnTouchListener(OverlayDragListener())
        }
        panel.addView(header)
        overlayStatus = TextView(this).apply {
            text = getString(R.string.capture_session_ready)
            setTextColor(Color.LTGRAY)
            textSize = 11f
            setPadding(dp(4), 0, dp(4), dp(6))
        }
        panel.addView(overlayStatus)
        overlayResult = TextView(this).apply {
            text = getString(R.string.analysis_not_ready)
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(dp(4), dp(4), dp(4), dp(8))
        }
        panel.addView(overlayResult)
        overlayThumbnail = ImageView(this).apply {
            visibility = View.GONE
            adjustViewBounds = true
            maxHeight = dp(72)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setOnClickListener {
                val path = overlayResult?.contentDescription?.toString()
                if (!path.isNullOrBlank()) {
                    startActivity(
                        Intent(this@ScreenCaptureService, ScreenshotPreviewActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra(ScreenshotPreviewActivity.EXTRA_IMAGE_PATH, path),
                    )
                }
            }
        }
        panel.addView(overlayThumbnail)
        panel.addView(buttonRow(
            overlayButton(R.string.manual_scan_button) {
                startService(Intent(this, ScreenCaptureService::class.java).setAction(ACTION_CAPTURE_SINGLE))
            },
            overlayButton(if (recordingEnabled) R.string.recording_stop else R.string.recording_start) {
                startService(Intent(this, ScreenCaptureService::class.java).setAction(ACTION_TOGGLE_RECORDING))
            },
        ))
        overlayManualConfirmation = overlayButton(R.string.manual_confirmation_button) {
            overlayResult?.text = getString(R.string.manual_confirmation_result)
            overlayManualConfirmation?.isEnabled = false
        }.apply {
            visibility = View.GONE
        }
        panel.addView(overlayManualConfirmation)
        panel.addView(buttonRow(
            overlayButton(R.string.overlay_collapse) { setOverlayCollapsed(true) },
        ))

        val bubble = Button(this).apply {
            text = getString(R.string.overlay_bubble)
            textSize = 11f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(0xE6222631.toInt())
                cornerRadius = dp(32).toFloat()
            }
            setOnClickListener { setOverlayCollapsed(false) }
            setOnTouchListener(OverlayDragListener())
            visibility = View.GONE
        }
        overlayContent = panel
        overlayBubble = bubble
        root.addView(panel)
        root.addView(bubble)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(96)
        }
        overlayRoot = root
        overlayLayoutParams = params
        attachOverlay()
    }

    private fun buttonRow(vararg buttons: Button): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            buttons.forEach { button ->
                addView(button, LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                    setMargins(dp(2), dp(2), dp(2), dp(2))
                })
            }
        }

    private fun overlayButton(label: Int, click: () -> Unit): Button =
        Button(this).apply {
            text = getString(label)
            textSize = 9f
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(2), 0, dp(2), 0)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(0xAA3A4658.toInt())
                cornerRadius = dp(8).toFloat()
            }
            setOnClickListener { click() }
        }

    private fun setOverlayCollapsed(collapsed: Boolean) {
        overlayContent?.visibility = if (collapsed) View.GONE else View.VISIBLE
        overlayBubble?.visibility = if (collapsed) View.VISIBLE else View.GONE
        val params = overlayLayoutParams ?: return
        overlayRoot?.let { root ->
            try {
                getSystemService(WindowManager::class.java).updateViewLayout(root, params)
            } catch (_: IllegalArgumentException) {
                removeOverlay()
            }
        }
    }

    private fun updateOverlayStatus(text: String) {
        mainHandler.post { overlayStatus?.text = text }
    }

    private fun setOverlayVisible(visible: Boolean) {
        val update = {
            if (visible) {
                attachOverlay()
            } else {
                detachOverlayForCapture()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) update() else mainHandler.post(update)
    }

    private fun attachOverlay() {
        val root = overlayRoot ?: return
        if (overlayAttached) {
            root.visibility = View.VISIBLE
            return
        }
        if (root.parent != null) {
            overlayAttached = true
            root.visibility = View.VISIBLE
            return
        }
        try {
            root.visibility = View.VISIBLE
            getSystemService(WindowManager::class.java).addView(
                root,
                overlayLayoutParams ?: return,
            )
            overlayAttached = true
        } catch (error: SecurityException) {
            Log.e(TAG, "Overlay permission was revoked", error)
            updateOverlayStatus(getString(R.string.overlay_permission_required))
        } catch (error: WindowManager.BadTokenException) {
            Log.e(TAG, "WindowManager rejected the overlay token", error)
            updateOverlayStatus(getString(R.string.overlay_permission_required))
        } catch (error: IllegalStateException) {
            Log.e(TAG, "Overlay view is already attached", error)
            overlayAttached = root.parent != null
        }
    }

    private fun detachOverlayForCapture() {
        val root = overlayRoot ?: return
        if (!overlayAttached && root.parent == null) return
        try {
            getSystemService(WindowManager::class.java).removeViewImmediate(root)
            overlayAttached = false
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "Overlay was already detached before capture", error)
            overlayAttached = root.parent != null
        } catch (error: SecurityException) {
            Log.e(TAG, "Unable to detach overlay before capture", error)
            failCaptureCycle(R.string.overlay_permission_required)
        }
    }

    private fun updateOverlayControls() {
        mainHandler.post {
            overlayContent?.let { panel ->
                val buttons = findButtons(panel)
                buttons.firstOrNull { it.text == getString(R.string.recording_start) ||
                    it.text == getString(R.string.recording_stop) }?.text =
                    getString(if (recordingEnabled) R.string.recording_stop else R.string.recording_start)
            }
        }
    }

    private fun updateOverlayThumbnailText(imagePath: String?) {
        if (imagePath.isNullOrBlank() || !File(imagePath).isFile) {
            mainHandler.post {
                lastThumbnail?.recycle()
                lastThumbnail = null
                overlayThumbnail?.setImageDrawable(null)
                overlayThumbnail?.visibility = View.GONE
                overlayResult?.contentDescription = null
            }
            return
        }
        mainHandler.post {
            val bitmap = android.graphics.BitmapFactory.decodeFile(imagePath)
            if (bitmap != null) {
                lastThumbnail?.recycle()
                lastThumbnail = bitmap
                overlayResult?.contentDescription = imagePath
                overlayThumbnail?.setImageBitmap(bitmap)
                overlayThumbnail?.visibility = View.VISIBLE
            } else {
                lastThumbnail?.recycle()
                lastThumbnail = null
                overlayThumbnail?.setImageDrawable(null)
                overlayThumbnail?.visibility = View.GONE
                overlayResult?.contentDescription = null
            }
        }
    }

    private fun findButtons(view: View): List<Button> = when (view) {
        is Button -> listOf(view)
        is LinearLayout -> (0 until view.childCount).flatMap { findButtons(view.getChildAt(it)) }
        is FrameLayout -> (0 until view.childCount).flatMap { findButtons(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun removeOverlay() {
        val root = overlayRoot
        overlayRoot = null
        if (root != null && (overlayAttached || root.parent != null)) {
            try {
                root.visibility = View.GONE
                overlayThumbnail?.setImageDrawable(null)
                getSystemService(WindowManager::class.java).removeViewImmediate(root)
                overlayAttached = false
            } catch (_: IllegalArgumentException) {
                // The system can remove an overlay while revoking its permission.
            } catch (error: SecurityException) {
                Log.w(TAG, "Overlay permission was revoked during removal", error)
            }
        }
        overlayAttached = false
        overlayContent = null
        overlayBubble = null
        overlayStatus = null
        overlayResult = null
        overlayManualConfirmation = null
        overlayThumbnail = null
        lastThumbnail?.recycle()
        lastThumbnail = null
        overlayLayoutParams = null
    }

    private fun removeOverlayOnMainThread() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            removeOverlay()
        } else {
            mainHandler.post { removeOverlay() }
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private inner class OverlayDragListener : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var dragged = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val params = overlayLayoutParams ?: return false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    dragged = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(event.rawX - touchX) > dp(4) ||
                        kotlin.math.abs(event.rawY - touchY) > dp(4)
                    ) dragged = true
                    params.x = startX + (event.rawX - touchX).toInt()
                    params.y = startY + (event.rawY - touchY).toInt()
                    overlayRoot?.let { root ->
                        try {
                            getSystemService(WindowManager::class.java).updateViewLayout(root, params)
                        } catch (_: IllegalArgumentException) {
                            removeOverlay()
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) view.performClick()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> return true
            }
            return false
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "ScreenCaptureService"
        const val ACTION_CAPTURE_COMPLETE = "com.autoscanner.trader.CAPTURE_COMPLETE"
        const val ACTION_STOP_SESSION = "com.autoscanner.trader.STOP_CAPTURE_SESSION"
        const val ACTION_CAPTURE_SINGLE = "com.autoscanner.trader.CAPTURE_SINGLE_FRAME"
        const val ACTION_START_SESSION = "com.autoscanner.trader.START_CAPTURE_SESSION"
        const val ACTION_QUERY_STATE = "com.autoscanner.trader.QUERY_CAPTURE_STATE"
        const val ACTION_TOGGLE_RECORDING = "com.autoscanner.trader.TOGGLE_RECORDING"
        const val ACTION_SHOW_RESULT = "com.autoscanner.trader.SHOW_ANALYSIS_RESULT"
        const val ACTION_SHOW_ERROR = "com.autoscanner.trader.SHOW_ANALYSIS_ERROR"
        const val ACTION_SESSION_READY = "com.autoscanner.trader.CAPTURE_SESSION_READY"
        const val ACTION_RECORDING_STATE = "com.autoscanner.trader.RECORDING_STATE"
        const val EXTRA_RESULT_TEXT = "analysis_result_text"
        const val EXTRA_RESULT_IMAGE = "analysis_result_image"
        const val EXTRA_HAS_SIGNAL = "analysis_has_directional_signal"
        const val EXTRA_KEEP_HIDDEN = "keep_overlay_hidden"
        const val EXTRA_RECORDING_ENABLED = "recording_enabled"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_SUCCEEDED = "capture_succeeded"
        const val EXTRA_FRAMES = "captured_frames"
        const val EXTRA_ERROR_RESOURCE = "capture_error_resource"
        const val EXTRA_CAPTURE_DIRECTORY = "capture_directory"
        const val EXTRA_CAPTURE_STARTED_AT = "capture_started_at"
        const val EXTRA_CAPTURE_DURATION = "capture_duration"

        private const val CAPTURE_DURATION_MS = 15_000L
        private const val ANALYSIS_TIMEOUT_MS = 120_000L
        private const val OVERLAY_SETTLE_FRAMES = 3
        private const val SINGLE_FRAME_TIMEOUT_MS = 5_000L
        private const val SAMPLE_INTERVAL_MS = 500L
        private const val MAX_FRAME_DIMENSION = 960
        private const val JPEG_QUALITY = 75
        private const val NOTIFICATION_CHANNEL_ID = "screen_capture"
        private const val NOTIFICATION_ID = 7101
    }
}
