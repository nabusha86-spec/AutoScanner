package com.autoscanner.trader.vision

import android.graphics.Bitmap
import com.autoscanner.trader.R

class TradingButtonsRecognitionEngine(
    private val openCVProcessor: OpenCVProcessor,
    private val strings: VisionStrings,
) {
    fun recognize(bitmap: Bitmap, textBoxes: List<TextBox>): List<TradingButton> {
        val regions = openCVProcessor.detectButtonColorRegions(bitmap)
        return listOf(
            recognizeKind(strings.buy, strings.green, bitmap, textBoxes, regions),
            recognizeKind(strings.sell, strings.red, bitmap, textBoxes, regions),
        )
    }

    private fun recognizeKind(
        kind: String,
        expectedColor: String,
        bitmap: Bitmap,
        textBoxes: List<TextBox>,
        regions: List<OpenCVProcessor.ColorRegion>,
    ): TradingButton {
        val relevantText = textBoxes.filter {
            it.confidence >= MIN_TEXT_CONFIDENCE && matchesButtonText(it.text, kind)
        }
        val colorRegions = regions.filter { it.color == expectedColor }
        val oppositeRegions = regions.filter {
            it.color != expectedColor && it.left >= bitmap.width * RIGHT_PANEL_START
        }
        val paired = colorRegions.mapNotNull { region ->
            val text = relevantText
                .filter { isInsideOrNear(it, region, bitmap) }
                .maxByOrNull(TextBox::confidence)
                ?: return@mapNotNull null
            Pair(region, text)
        }
        val contradictory = relevantText.any { text ->
            regions.any { region ->
                region.color != expectedColor && isInsideOrNear(text, region, bitmap)
            }
        } || oppositeRegions.any { region ->
            relevantText.any { isInsideOrNear(it, region, bitmap) }
        }

        if (contradictory || paired.size > 1 || (relevantText.isNotEmpty() && colorRegions.isEmpty())) {
            val candidate = paired.firstOrNull()
            return TradingButton(
                kind = kind,
                detected = false,
                text = relevantText.firstOrNull()?.text ?: strings.unknown,
                left = candidate?.first?.left,
                top = candidate?.first?.top,
                right = candidate?.first?.right,
                bottom = candidate?.first?.bottom,
                confidence = candidate?.let { minOf(it.first.colorFraction, it.second.confidence) } ?: 0f,
                state = strings.get(R.string.vision_state_ambiguous),
                ambiguous = true,
                message = strings.get(R.string.vision_button_conflicting),
            )
        }

        val candidate = paired.singleOrNull()
        if (candidate == null) {
            return TradingButton(
                kind = kind,
                detected = false,
                text = relevantText.firstOrNull()?.text ?: strings.unknown,
                left = null,
                top = null,
                right = null,
                bottom = null,
                confidence = 0f,
                state = strings.get(R.string.vision_state_button_unknown),
                ambiguous = false,
                message = strings.get(R.string.vision_button_color_or_text_missing),
            )
        }

        val (region, text) = candidate
        val confidence = (text.confidence * region.colorFraction).coerceIn(0f, 1f)
        return TradingButton(
            kind = kind,
            detected = confidence >= MIN_RESULT_CONFIDENCE,
            text = text.text,
            left = region.left,
            top = region.top,
            right = region.right,
            bottom = region.bottom,
            confidence = confidence,
            state = strings.get(R.string.vision_state_visible_activity_unknown),
            ambiguous = false,
            message = if (confidence >= MIN_RESULT_CONFIDENCE) {
                if (expectedColor == strings.green) {
                    strings.get(R.string.vision_button_buy_detected)
                } else {
                    strings.get(R.string.vision_button_sell_detected)
                }
            } else {
                strings.get(R.string.vision_button_confidence_low)
            },
        )
    }

    private fun isInsideOrNear(
        text: TextBox,
        region: OpenCVProcessor.ColorRegion,
        bitmap: Bitmap,
    ): Boolean {
        val horizontalPadding = (bitmap.width * TEXT_REGION_PADDING).toInt()
        val verticalPadding = (bitmap.height * TEXT_REGION_PADDING).toInt()
        val centerX = (text.left + text.right) / 2
        val centerY = (text.top + text.bottom) / 2
        return centerX in (region.left - horizontalPadding)..(region.right + horizontalPadding) &&
            centerY in (region.top - verticalPadding)..(region.bottom + verticalPadding)
    }

    private fun matchesButtonText(text: String, kind: String): Boolean {
        val normalized = text.uppercase().replace(Regex("[^A-ZА-ЯЁ]"), "")
        return if (kind == strings.buy) {
            normalized.contains("BUY") || normalized.contains("ПОКУП")
        } else {
            normalized.contains("SELL") || normalized.contains("ПРОДА")
        }
    }

    companion object {
        private const val RIGHT_PANEL_START = 0.64f
        private const val TEXT_REGION_PADDING = 0.035f
        private const val MIN_TEXT_CONFIDENCE = 0.45f
        private const val MIN_RESULT_CONFIDENCE = 0.40f
    }
}
