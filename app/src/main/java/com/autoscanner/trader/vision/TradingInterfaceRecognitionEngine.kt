package com.autoscanner.trader.vision

import com.autoscanner.trader.R
import kotlin.math.abs

class TradingInterfaceRecognitionEngine(
    private val strings: VisionStrings,
    private val screenDetector: PocketOptionScreenDetector = PocketOptionScreenDetector(strings),
) {
    fun recognize(
        textBoxes: List<TextBox>,
        chart: ChartAnalysis,
        width: Int,
        height: Int,
    ): InterfaceResult {
        val trustedText = textBoxes.filter { it.confidence >= MIN_TEXT_CONFIDENCE }
        val allText = trustedText.joinToString(" ") { it.text.uppercase() }
        val instrument = PAIR_REGEX.find(allText)
            ?.groupValues
            ?.let { "${it[1]}/${it[2]}" }
            ?: strings.unknown

        val price = trustedText
            .filter { box ->
                box.left >= width * PRICE_REGION_LEFT &&
                    box.left <= width * PRICE_REGION_RIGHT &&
                    (box.left + box.right) / 2 >= width * PRICE_REGION_CENTER &&
                    box.top >= height * PRICE_REGION_TOP &&
                    box.top <= height * PRICE_REGION_BOTTOM
            }
            .mapNotNull { box ->
                PRICE_REGEX.find(box.text.replace(',', '.'))?.value?.let { value ->
                    if (box.confidence >= MIN_PRICE_CONFIDENCE) Pair(value, box.confidence) else null
                }
            }
            .maxByOrNull { it.second }
            ?.first ?: strings.unknown

        val amountLabel = trustedText.firstOrNull {
            AMOUNT_LABEL_REGEX.containsMatchIn(it.text)
        }
        val amount = amountLabel?.let { label ->
            trustedText
                .filter { box ->
                    box !== label &&
                        box.confidence >= MIN_AMOUNT_CONFIDENCE &&
                        abs(box.top - label.bottom) <= height * AMOUNT_LABEL_DISTANCE &&
                        abs(box.left - label.left) <= width * AMOUNT_HORIZONTAL_DISTANCE &&
                        AMOUNT_VALUE_REGEX.matches(box.text.trim()) &&
                        !TIMER_REGEX.matches(box.text.trim()) &&
                        !PRICE_REGEX.matches(box.text.replace(',', '.'))
                }
                .minByOrNull { abs(it.top - label.bottom) + abs(it.left - label.left) * AMOUNT_HORIZONTAL_WEIGHT }
                ?.text
        } ?: strings.unknown

        val timeframe = trustedText
            .filter { it.confidence >= MIN_TIMEFRAME_CONFIDENCE }
            .firstNotNullOfOrNull { TIMEFRAME_REGEX.find(it.text.uppercase())?.value }
            ?.replace(" ", "")
            ?: strings.unknown
        val timerBox = trustedText
            .filter { it.confidence >= MIN_TIMER_CONFIDENCE }
            .firstNotNullOfOrNull { box ->
                TIMER_REGEX.find(box.text)?.value?.let { Pair(it, box.confidence) }
            }
        val timer = timerBox?.first ?: strings.unknown
        val expiration = trustedText
            .firstOrNull { EXPIRATION_LABEL_REGEX.containsMatchIn(it.text) }
            ?.let { label ->
                trustedText
                    .filter { it.top >= label.bottom && it.top - label.bottom <= height * EXPIRATION_LABEL_DISTANCE }
                    .minByOrNull { it.top - label.bottom }
                    ?.let { TIMER_REGEX.find(it.text)?.value }
            } ?: strings.unknown

        val indicators = trustedText.flatMap { box ->
            INDICATOR_MATCHES.filter { (_, _, pattern) -> pattern.containsMatchIn(box.text) }
                .map { (label, resourceId, _) ->
                    if (resourceId == 0) label else strings.get(resourceId)
                }
        }.distinct()
        val recognizedElements = trustedText
            .filterNot { box ->
                PAIR_REGEX.containsMatchIn(box.text) ||
                    PRICE_REGEX.containsMatchIn(box.text.replace(',', '.')) ||
                    TIMER_REGEX.containsMatchIn(box.text) ||
                    TIMEFRAME_REGEX.containsMatchIn(box.text.uppercase()) ||
                    INDICATOR_MATCHES.any { (_, _, pattern) -> pattern.containsMatchIn(box.text) }
            }
            .take(MAX_OTHER_ELEMENTS)
            .map { it.text }
            .distinct()
        val tradingScreen = screenDetector.detect(chart, instrument, price, timeframe, timer)

        return InterfaceResult(
            tradingScreenDetected = tradingScreen,
            instrument = instrument,
            price = price,
            amount = amount,
            timeframe = timeframe,
            timer = timer,
            expiration = expiration,
            indicators = indicators,
            otherElements = recognizedElements,
        )
    }

    data class InterfaceResult(
        val tradingScreenDetected: Boolean,
        val instrument: String,
        val price: String,
        val amount: String,
        val timeframe: String,
        val timer: String,
        val expiration: String,
        val indicators: List<String>,
        val otherElements: List<String>,
    )

    companion object {
        private const val MIN_TEXT_CONFIDENCE = 0.55f
        private const val MIN_PRICE_CONFIDENCE = 0.72f
        private const val MIN_AMOUNT_CONFIDENCE = 0.65f
        private const val MIN_TIMEFRAME_CONFIDENCE = 0.60f
        private const val MIN_TIMER_CONFIDENCE = 0.65f
        private const val PRICE_REGION_LEFT = 0.20f
        private const val PRICE_REGION_RIGHT = 0.88f
        private const val PRICE_REGION_CENTER = 0.62f
        private const val PRICE_REGION_TOP = 0.08f
        private const val PRICE_REGION_BOTTOM = 0.82f
        private const val AMOUNT_LABEL_DISTANCE = 0.10f
        private const val AMOUNT_HORIZONTAL_DISTANCE = 0.32f
        private const val AMOUNT_HORIZONTAL_WEIGHT = 0.35
        private const val EXPIRATION_LABEL_DISTANCE = 0.10f
        private const val MAX_OTHER_ELEMENTS = 12

        private val PAIR_REGEX = Regex(
            """\b(EUR|USD|GBP|JPY|CHF|AUD|NZD|CAD|AED|CNY|HKD|SGD|ZAR|TRY|MXN|NOK|SEK|PLN|BTC|ETH|LTC|XAU|XAG)\s*[/\-]\s*(EUR|USD|GBP|JPY|CHF|AUD|NZD|CAD|AED|CNY|HKD|SGD|ZAR|TRY|MXN|NOK|SEK|PLN|BTC|ETH|LTC|XAU|XAG)\b""",
            RegexOption.IGNORE_CASE,
        )
        private val PRICE_REGEX = Regex("""(?<![\w:])\d{1,5}[.,]\d{3,8}(?!\d)""")
        private val AMOUNT_LABEL_REGEX =
            Regex("""\b(?:AMOUNT|INVESTMENT|СТАВКА|СУММА|РАЗМЕР\s+СДЕЛКИ)\b""", RegexOption.IGNORE_CASE)
        private val AMOUNT_VALUE_REGEX =
            Regex("""(?:[$€₽]\s*)?\d{1,6}(?:[.,]\d{1,2})?(?:\s*(?:USD|EUR|RUB|₽|\$))?""", RegexOption.IGNORE_CASE)
        private val TIMEFRAME_REGEX = Regex("""\b(?:M|H|D|S)\s?\d{1,2}\b|\b\d{1,2}\s?(?:M|H|D|S)\b""")
        private val TIMER_REGEX = Regex("""(?<!\d)(?:\d{1,2}:)?\d{1,2}:\d{2}(?!\d)""")
        private val EXPIRATION_LABEL_REGEX =
            Regex("""(?:EXPIRATION|EXPIRY|ЭКСПИРАЦ|ВРЕМЯ\s+СДЕЛКИ)""", RegexOption.IGNORE_CASE)
        private val INDICATOR_MATCHES = listOf(
            Triple("RSI", 0, Regex("""\bRSI\b""", RegexOption.IGNORE_CASE)),
            Triple("MACD", 0, Regex("""\bMACD\b""", RegexOption.IGNORE_CASE)),
            Triple("", R.string.vision_indicator_bollinger, Regex("""BOLLINGER|БОЛЛИНДЖЕР""", RegexOption.IGNORE_CASE)),
            Triple("", R.string.vision_indicator_moving_average, Regex("""\b(?:EMA|SMA|MA)\b|СКОЛЬЗЯЩ""", RegexOption.IGNORE_CASE)),
            Triple("", R.string.vision_indicator_stochastic, Regex("""STOCH|СТОХАСТ""", RegexOption.IGNORE_CASE)),
        )
    }
}
