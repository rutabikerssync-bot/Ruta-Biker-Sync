package com.rutabikersync.app.core.network

import com.rutabikersync.app.BuildConfig
import org.junit.Assert.assertTrue
import org.junit.Test

class BaseUrlSecurityPolicyTest {
    @Test
    fun `release BASE_URL must be https (T-02 B1-4)`() {
        if (!BuildConfig.DEBUG) {
            val url = BuildConfig.BASE_URL
            assertTrue(
                "Release BASE_URL must start with https://. Actual=$url",
                url.startsWith("https://")
            )
            assertTrue("Release BASE_URL must not contain placeholders (example.invalid). Actual=$url", !url.contains("example.invalid", ignoreCase = true))
            assertTrue("Release BASE_URL must not contain TODO_EXTERNAL. Actual=$url", !url.contains("TODO_EXTERNAL", ignoreCase = true))
            assertTrue("Release BASE_URL must not contain localhost. Actual=$url", !url.contains("localhost", ignoreCase = true))
        } else {
            // Debug: policy enforced at runtime; BASE_URL may be localhost for adb reverse.
            assertTrue(BuildConfig.BASE_URL.isNotBlank())
        }
    }
}
