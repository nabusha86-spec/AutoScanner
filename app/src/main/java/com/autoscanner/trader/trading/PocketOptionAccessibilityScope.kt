package com.autoscanner.trader.trading

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

internal object PocketOptionAccessibilityScope {
    const val CHROME_PACKAGE = "com.android.chrome"
    const val CHROME_ADDRESS_BAR_VIEW_ID = "$CHROME_PACKAGE:id/url_bar"

    fun allowsPackage(packageName: String?): Boolean = packageName == CHROME_PACKAGE

    fun allowsAddress(address: CharSequence): Boolean {
        val value = address.toString().trim()
        if (value.isEmpty() || value.any(Char::isWhitespace)) return false
        val uriValue = if ("://" in value) value else "https://$value"
        val uri = try {
            URI(uriValue)
        } catch (_: URISyntaxException) {
            return false
        }
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            uri.rawUserInfo != null ||
            uri.port != -1
        ) {
            return false
        }
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        return host == POCKET_OPTION_HOST || host.endsWith(".$POCKET_OPTION_HOST")
    }

    private const val POCKET_OPTION_HOST = "pocketoption.com"
}
