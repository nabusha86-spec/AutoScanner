package com.autoscanner.trader.vision

import android.content.Context
import android.graphics.Bitmap
import com.autoscanner.trader.R
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class OCRProcessor(context: Context) : AutoCloseable {
    private val api = TessBaseAPI()
    private val strings = VisionStrings(context)

    init {
        try {
            val dataDirectory = File(context.filesDir, "tesseract")
            val tessdataDirectory = File(dataDirectory, "tessdata")
            if (!tessdataDirectory.exists() && !tessdataDirectory.mkdirs()) {
                throw IOException(strings.get(R.string.vision_error_ocr_models))
            }
            listOf("eng", "rus").forEach { language ->
                val model = File(tessdataDirectory, "$language.traineddata")
                if (!model.exists()) {
                    context.assets.open("tessdata/$language.traineddata").use { input ->
                        FileOutputStream(model).use(input::copyTo)
                    }
                }
            }
            if (!api.init(dataDirectory.absolutePath, "eng+rus", TessBaseAPI.OEM_LSTM_ONLY)) {
                throw IOException(strings.get(R.string.vision_error_ocr_models))
            }
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT)
        } catch (error: IOException) {
            api.recycle()
            throw error
        } catch (error: RuntimeException) {
            api.recycle()
            throw error
        }
    }

    fun recognize(bitmap: Bitmap): List<TextBox> {
        api.setImage(bitmap)
        if (api.getUTF8Text().isNullOrBlank()) {
            api.clear()
            return emptyList()
        }
        val result = ArrayList<TextBox>()
        val iterator = api.resultIterator ?: run {
            api.clear()
            return result
        }
        try {
            iterator.begin()
            do {
                val text = iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)?.trim().orEmpty()
                val bounds = iterator.getBoundingBox(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                val confidence = iterator.confidence(TessBaseAPI.PageIteratorLevel.RIL_WORD) / 100f
                if (text.isNotBlank() && bounds.size == 4 && confidence > 0f) {
                    result += TextBox(
                        text = text,
                        confidence = confidence.coerceIn(0f, 1f),
                        left = bounds[0],
                        top = bounds[1],
                        right = bounds[2],
                        bottom = bounds[3],
                    )
                }
            } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
        } finally {
            iterator.delete()
            api.clear()
        }
        return result
    }

    override fun close() {
        api.recycle()
    }
}
