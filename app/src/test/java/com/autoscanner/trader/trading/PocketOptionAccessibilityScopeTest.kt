package com.autoscanner.trader.trading

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketOptionAccessibilityScopeTest {
    @Test
    fun allowsOnlyChromeForAccessibilityWindowReads() {
        assertTrue(PocketOptionAccessibilityScope.allowsPackage("com.android.chrome"))
        assertFalse(PocketOptionAccessibilityScope.allowsPackage("com.android.settings"))
        assertFalse(PocketOptionAccessibilityScope.allowsPackage("org.mozilla.firefox"))
        assertFalse(PocketOptionAccessibilityScope.allowsPackage(null))
    }

    @Test
    fun allowsPocketOptionHttpsHostAndSubdomains() {
        assertTrue(PocketOptionAccessibilityScope.allowsAddress("https://pocketoption.com/en/cabinet"))
        assertTrue(PocketOptionAccessibilityScope.allowsAddress("www.pocketoption.com"))
        assertTrue(PocketOptionAccessibilityScope.allowsAddress("https://trade.pocketoption.com"))
    }

    @Test
    fun rejectsLookalikeHostsAndNonHttpsAddresses() {
        assertFalse(PocketOptionAccessibilityScope.allowsAddress("https://pocketoption.com.attacker.invalid"))
        assertFalse(PocketOptionAccessibilityScope.allowsAddress("https://attacker.invalid/?next=pocketoption.com"))
        assertFalse(PocketOptionAccessibilityScope.allowsAddress("http://pocketoption.com"))
        assertFalse(PocketOptionAccessibilityScope.allowsAddress("https://pocketoption.com@attacker.invalid"))
        assertFalse(PocketOptionAccessibilityScope.allowsAddress("not a URL"))
    }
}
