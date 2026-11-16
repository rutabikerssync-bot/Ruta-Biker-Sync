package com.rutabikersync.app.core.network

import com.rutabikersync.app.BuildConfig
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseBaseUrlSecurityTest {
    @Test
    fun `standardRelease BASE_URL uses https`() {
        assertTrue(
            "Release BASE_URL must start with https:// (TODO_EXTERNAL:URL_PROD). Actual=${BuildConfig.BASE_URL}",
            BuildConfig.BASE_URL.startsWith("https://")
        )
    }
}
