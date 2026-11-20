package com.autoscanner.trader.vision

import android.graphics.Bitmap
import com.autoscanner.trader.R
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

class OpenCVProcessor(private val strings: VisionStrings) {
    fun analyze(bitmap: Bitmap): ChartAnalysis {
        val rgba = Mat()
        val hsv = Mat()
        val green = Mat()
        val redLow = Mat()
        val redHigh = Mat()
        val red = Mat()
        val hierarchy = Mat()
        val contours = ArrayList<MatOfPoint>()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, hsv, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(hsv, hsv, Imgproc.COLOR_RGB2HSV)
            Core.inRange(hsv, Scalar(35.0, 55.0, 45.0), Scalar(100.0, 255.0, 255.0), green)
            Core.inRange(hsv, Scalar(0.0, 55.0, 45.0), Scalar(12.0, 255.0, 255.0), redLow)
            Core.inRange(hsv, Scalar(168.0, 55.0, 45.0), Scalar(180.0, 255.0, 255.0), redHigh)
            Core.bitwise_or(redLow, redHigh, red)

            val search = Rect(
                (bitmap.width * CHART_LEFT).toInt(),
                (bitmap.height * CHART_TOP).toInt(),
                (bitmap.width * (CHART_RIGHT - CHART_LEFT)).toInt().coerceAtLeast(1),
                (bitmap.height * (CHART_BOTTOM - CHART_TOP)).toInt().coerceAtLeast(1),
            )
            val combined = Mat()
            val greenRegion = green.submat(search)
            val redRegion = red.submat(search)
            try {
                Core.bitwise_or(greenRegion, redRegion, combined)
                Imgproc.findContours(
                    combined,
                    contours,
                    hierarchy,
                    Imgproc.RETR_EXTERNAL,
                    Imgproc.CHAIN_APPROX_SIMPLE,
                )
                val candidates = contours.mapNotNull { contour ->
                    val bounds = Imgproc.boundingRect(contour)
                    if (bounds.width !in MIN_CANDLE_WIDTH..MAX_CANDLE_WIDTH ||
                        bounds.height < MIN_CANDLE_HEIGHT ||
                        bounds.height > search.height * MAX_CANDLE_HEIGHT_RATIO ||
                        bounds.height.toDouble() / bounds.width < MIN_CANDLE_ASPECT
                    ) {
                        null
                    } else {
                        val greenCrop = greenRegion.submat(bounds)
                        val redCrop = redRegion.submat(bounds)
                        val greenPixels: Int
                        val redPixels: Int
                        try {
                            greenPixels = Core.countNonZero(greenCrop)
                            redPixels = Core.countNonZero(redCrop)
                        } finally {
                            greenCrop.release()
                            redCrop.release()
                        }
                        if (greenPixels + redPixels < MIN_CANDLE_COLOR_PIXELS) {
                            null
                        } else {
                            Candidate(
                                bounds = bounds,
                                color = if (greenPixels >= redPixels) strings.green else strings.red,
                                greenPixels = greenPixels,
                                redPixels = redPixels,
                            )
                        }
                    }
                }.sortedBy { it.bounds.x }

                val candles = candidates.mapIndexed { index, candidate ->
                    val bounds = candidate.bounds
                    val centerX = bounds.x + bounds.width / 2
                    val colorMask = if (candidate.color == strings.green) greenRegion else redRegion
                    val (wickTop, wickBottom) = verticalColorExtent(colorMask, centerX, bounds.y, bounds.y + bounds.height)
                    val (bodyTop, bodyBottom) = bodyVerticalExtent(colorMask, bounds)
                    Candle(
                        index = index + 1,
                        left = search.x + bounds.x,
                        top = search.y + bodyTop,
                        right = search.x + bounds.x + bounds.width,
                        bottom = search.y + bodyBottom,
                        wickTop = search.y + wickTop,
                        wickBottom = search.y + wickBottom,
                        color = candidate.color,
                        closeY = if (candidate.color == strings.green) {
                            search.y + bodyTop
                        } else {
                            search.y + bodyBottom
                        },
                    )
                }

                val distinctPositions = candles.map { (it.left + it.right) / 2 }.distinct().size
                val detected = candles.size >= MIN_CANDLES_FOR_CHART && distinctPositions >= MIN_CANDLES_FOR_CHART
                val bounds = if (candles.isNotEmpty()) {
                    intArrayOf(
                        candles.minOf { it.left },
                        candles.minOf { it.wickTop },
                        candles.maxOf { it.right },
                        candles.maxOf { it.wickBottom },
                    )
                } else {
                    null
                }
                return ChartAnalysis(
                    detected = detected,
                    left = bounds?.get(0),
                    top = bounds?.get(1),
                    right = bounds?.get(2),
                    bottom = bounds?.get(3),
                    candles = candles,
                    movement = strings.get(R.string.vision_chart_undetermined),
                    changedPercent = null,
                )
            } finally {
                combined.release()
                greenRegion.release()
                redRegion.release()
            }
        } finally {
            rgba.release()
            hsv.release()
            green.release()
            redLow.release()
            redHigh.release()
            red.release()
            hierarchy.release()
            contours.forEach(Mat::release)
        }
    }

    fun compare(previous: Bitmap, current: Bitmap): Double {
        val width = minOf(previous.width, current.width)
        val height = minOf(previous.height, current.height)
        if (width <= 0 || height <= 0) return 0.0
        val previousMat = Mat()
        val currentMat = Mat()
        val previousGray = Mat()
        val currentGray = Mat()
        val difference = Mat()
        val thresholded = Mat()
        var previousScaled: Bitmap? = null
        var currentScaled: Bitmap? = null
        try {
            previousScaled = Bitmap.createScaledBitmap(previous, width, height, true)
            currentScaled = Bitmap.createScaledBitmap(current, width, height, true)
            Utils.bitmapToMat(previousScaled, previousMat)
            Utils.bitmapToMat(currentScaled, currentMat)
            Imgproc.cvtColor(previousMat, previousGray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.cvtColor(currentMat, currentGray, Imgproc.COLOR_RGBA2GRAY)
            Core.absdiff(previousGray, currentGray, difference)
            Imgproc.threshold(difference, thresholded, CHANGE_THRESHOLD, 255.0, Imgproc.THRESH_BINARY)
            return Core.countNonZero(thresholded).toDouble() /
                (width.toDouble() * height.toDouble()) * 100.0
        } finally {
            previousMat.release()
            currentMat.release()
            previousGray.release()
            currentGray.release()
            difference.release()
            thresholded.release()
            previousScaled?.takeIf { it !== previous }?.recycle()
            currentScaled?.takeIf { it !== current }?.recycle()
        }
    }

    private fun verticalColorExtent(mask: Mat, x: Int, top: Int, bottom: Int): Pair<Int, Int> {
        var first = top
        var last = bottom
        for (y in top until bottom) {
            if (mask.get(y, x)?.firstOrNull()?.let { it > 0.0 } == true) {
                first = y
                break
            }
        }
        for (y in bottom - 1 downTo top) {
            if (mask.get(y, x)?.firstOrNull()?.let { it > 0.0 } == true) {
                last = y
                break
            }
        }
        return Pair(first, last)
    }

    private fun bodyVerticalExtent(mask: Mat, bounds: Rect): Pair<Int, Int> {
        val crop = mask.submat(bounds)
        try {
            val threshold = maxOf(2, (bounds.width * BODY_ROW_FRACTION).toInt())
            val bodyRows = (0 until bounds.height).filter { row ->
                val rowView = crop.row(row)
                try {
                    Core.countNonZero(rowView) >= threshold
                } finally {
                    rowView.release()
                }
            }
            return if (bodyRows.isEmpty()) {
                Pair(bounds.y, bounds.y + bounds.height)
            } else {
                Pair(bounds.y + bodyRows.first(), bounds.y + bodyRows.last() + 1)
            }
        } finally {
            crop.release()
        }
    }

    private data class Candidate(
        val bounds: Rect,
        val color: String,
        val greenPixels: Int,
        val redPixels: Int,
    )

    companion object {
        private const val CHART_LEFT = 0.02f
        private const val CHART_TOP = 0.10f
        private const val CHART_RIGHT = 0.82f
        private const val CHART_BOTTOM = 0.82f
        private const val MIN_CANDLE_WIDTH = 2
        private const val MAX_CANDLE_WIDTH = 22
        private const val MIN_CANDLE_HEIGHT = 5
        private const val MAX_CANDLE_HEIGHT_RATIO = 0.45
        private const val MIN_CANDLE_ASPECT = 1.15
        private const val MIN_CANDLE_COLOR_PIXELS = 8
        private const val MIN_CANDLES_FOR_CHART = 3
        private const val BODY_ROW_FRACTION = 0.35
        private const val CHANGE_THRESHOLD = 22.0
    }
}
