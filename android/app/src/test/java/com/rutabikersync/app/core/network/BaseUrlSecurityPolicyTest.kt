package com.rutabikersync.app.core.network

import com.rutabikersync.app.BuildConfig
import org.junit.Assert.assertTrue
import org.junit.Test

class BaseUrlSecurityPolicyTest {
    @Test
    fun `release BASE_URL must be https (T-02 B1-4)`() {
        if (!BuildConfig.DEBUG) {
            assertTrue(
                "Release BASE_URL must start with https:// (TODO_EXTERNAL:URL_PROD). Actual=${BuildConfig.BASE_URL}",
                BuildConfig.BASE_URL.startsWith("https://")
            )
        } else {
            // Debug: policy enforced at runtime; BASE_URL may be localhost for adb reverse.
            assertTrue(BuildConfig.BASE_URL.isNotBlank())
        }
    }
}
