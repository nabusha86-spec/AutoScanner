package com.autoscanner.trader.vision

import android.content.Context
import com.autoscanner.trader.R

class VisionStrings(context: Context) {
    private val appContext = context.applicationContext

    fun get(resourceId: Int, vararg arguments: Any): String =
        appContext.getString(resourceId, *arguments)

    val unknown: String get() = get(R.string.undetermined)
    val green: String get() = get(R.string.vision_green)
    val red: String get() = get(R.string.vision_red)
    val buy: String get() = get(R.string.vision_buy)
    val sell: String get() = get(R.string.vision_sell)
}
