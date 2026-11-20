package com.autoscanner.trader

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RgbaFrameConverterTest {
    @Test
    fun convertsPixelsWithPaddedRowsAndPixels() {
        val bytes = ByteBuffer.allocate(28)
        bytes.put(0, 255.toByte())
        bytes.put(1, 0.toByte())
        bytes.put(2, 0.toByte())
        bytes.put(3, 255.toByte())
        bytes.put(5, 0.toByte())
        bytes.put(6, 255.toByte())
        bytes.put(7, 0.toByte())
        bytes.put(8, 255.toByte())
        bytes.put(12, 0.toByte())
        bytes.put(13, 0.toByte())
        bytes.put(14, 255.toByte())
        bytes.put(15, 255.toByte())
        bytes.put(17, 255.toByte())
        bytes.put(18, 255.toByte())
        bytes.put(19, 255.toByte())
        bytes.put(20, 255.toByte())

        val pixels = RgbaFrameConverter.toArgbPixels(
            buffer = bytes,
            width = 2,
            height = 2,
            pixelStride = 5,
            rowStride = 12,
        )

        assertArrayEquals(
            intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt(), -1),
            pixels,
        )
    }

    @Test
    fun rejectsBuffersThatCannotContainTheDeclaredStrideLayout() {
        assertThrows(IllegalArgumentException::class.java) {
            RgbaFrameConverter.toArgbPixels(
                buffer = ByteBuffer.allocate(8),
                width = 2,
                height = 2,
                pixelStride = 4,
                rowStride = 8,
            )
        }
    }
}
