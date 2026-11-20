package com.autoscanner.trader

import android.graphics.Bitmap
import android.media.Image
import java.nio.ByteBuffer

internal object RgbaFrameConverter {
    fun toArgbPixels(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        pixelStride: Int,
        rowStride: Int,
    ): IntArray {
        require(width > 0 && height > 0) { "Image dimensions must be positive" }
        require(width.toLong() * height <= Int.MAX_VALUE) { "Image dimensions exceed supported pixel count" }
        require(pixelStride >= RGBA_BYTES_PER_PIXEL) { "Unsupported pixel stride: $pixelStride" }
        val minimumRowBytes = (width - 1L) * pixelStride + RGBA_BYTES_PER_PIXEL
        require(rowStride.toLong() >= minimumRowBytes) { "Row stride is shorter than the image row" }
        val requiredBytes = (height - 1L) * rowStride + minimumRowBytes
        require(requiredBytes <= buffer.remaining()) { "Image buffer is shorter than its stride layout" }

        val source = buffer.duplicate()
        val start = source.position()
        return IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val offset = start + y * rowStride + x * pixelStride
            val red = source.get(offset).toInt() and 0xff
            val green = source.get(offset + 1).toInt() and 0xff
            val blue = source.get(offset + 2).toInt() and 0xff
            val alpha = source.get(offset + 3).toInt() and 0xff
            (alpha shl 24) or (red shl 16) or (green shl 8) or blue
        }
    }

    fun toBitmap(image: Image): Bitmap {
        require(image.format == android.graphics.PixelFormat.RGBA_8888) {
            "Unsupported image format: ${image.format}"
        }
        val plane = image.planes.firstOrNull() ?: error("Image has no color plane")
        val pixels = toArgbPixels(
            buffer = plane.buffer,
            width = image.width,
            height = image.height,
            pixelStride = plane.pixelStride,
            rowStride = plane.rowStride,
        )
        val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.setPixels(
                pixels,
                0,
                image.width,
                0,
                0,
                image.width,
                image.height,
            )
        } catch (error: RuntimeException) {
            bitmap.recycle()
            throw error
        }
        return bitmap
    }

    private const val RGBA_BYTES_PER_PIXEL = 4
}
