package com.autoscanner.trader

import android.app.Activity
import android.graphics.Color
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.TextView
import android.widget.FrameLayout
import java.io.File

class ScreenshotPreviewActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_IMAGE_PATH)
        val image = path?.let(::File)?.takeIf(File::isFile)?.let {
            BitmapFactory.decodeFile(it.absolutePath)
        }
        if (image == null) {
            setContentView(TextView(this).apply {
                text = getString(R.string.preview_unavailable)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setBackgroundColor(Color.BLACK)
            })
            return
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(ImageView(this@ScreenshotPreviewActivity).apply {
                setImageBitmap(image)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ))
        })
    }

    companion object {
        const val EXTRA_IMAGE_PATH = "screenshot_image_path"
    }
}
