package com.autoscanner.trader

import android.graphics.Bitmap

internal object CapturedFrameStore {
    private var frame: Bitmap? = null

    @Synchronized
    fun replace(bitmap: Bitmap) {
        frame?.takeUnless { it === bitmap || it.isRecycled }?.recycle()
        frame = bitmap
    }

    @Synchronized
    fun take(): Bitmap? = frame.also { frame = null }

    @Synchronized
    fun clear() {
        frame?.takeUnless(Bitmap::isRecycled)?.recycle()
        frame = null
    }
}
