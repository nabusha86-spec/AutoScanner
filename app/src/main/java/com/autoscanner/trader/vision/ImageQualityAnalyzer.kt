package com.autoscanner.trader.vision

import android.graphics.Bitmap
import com.autoscanner.trader.R
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.imgproc.Imgproc
import kotlin.math.pow

class ImageQualityAnalyzer(private val strings: VisionStrings) {
    fun analyze(bitmap: Bitmap): ImageQuality {
        val rgba = Mat()
        val gray = Mat()
        val laplacian = Mat()
        val grayMean = MatOfDouble()
        val grayDeviation = MatOfDouble()
        return try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.Laplacian(gray, laplacian, CvType.CV_64F)
            Core.meanStdDev(gray, grayMean, grayDeviation)
            val brightness = Core.mean(gray).`val`[0]
            val contrast = grayDeviation.toArray().firstOrNull() ?: 0.0
            val lapMean = MatOfDouble()
            val lapDeviation = MatOfDouble()
            try {
                Core.meanStdDev(laplacian, lapMean, lapDeviation)
                val sharpness = (lapDeviation.toArray().firstOrNull() ?: 0.0).pow(2)
                val warning = when {
                    bitmap.width < MIN_WIDTH || bitmap.height < MIN_HEIGHT ->
                        strings.get(R.string.vision_quality_resolution)
                    brightness < MIN_BRIGHTNESS -> strings.get(R.string.vision_quality_dark)
                    brightness > MAX_BRIGHTNESS -> strings.get(R.string.vision_quality_bright)
                    contrast < MIN_CONTRAST -> strings.get(R.string.vision_quality_contrast)
                    sharpness < MIN_SHARPNESS -> strings.get(R.string.vision_quality_blur)
                    else -> null
                }
                ImageQuality(
                    width = bitmap.width,
                    height = bitmap.height,
                    sharpness = sharpness,
                    brightness = brightness,
                    contrast = contrast,
                    isAdequate = warning == null,
                    warning = warning,
                )
            } finally {
                lapMean.release()
                lapDeviation.release()
            }
        } finally {
            rgba.release()
            gray.release()
            laplacian.release()
            grayMean.release()
            grayDeviation.release()
        }
    }

    companion object {
        private const val MIN_WIDTH = 320
        private const val MIN_HEIGHT = 240
        private const val MIN_BRIGHTNESS = 20.0
        private const val MAX_BRIGHTNESS = 245.0
        private const val MIN_CONTRAST = 12.0
        private const val MIN_SHARPNESS = 18.0
    }
}
