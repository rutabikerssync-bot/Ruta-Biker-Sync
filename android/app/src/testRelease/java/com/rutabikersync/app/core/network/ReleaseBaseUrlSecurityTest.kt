package com.rutabikersync.app.core.network

import com.rutabikersync.app.BuildConfig
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseBaseUrlSecurityTest {
    @Test
    fun `standardRelease BASE_URL uses https and no placeholders`() {
        val url = BuildConfig.BASE_URL
        assertTrue("Release BASE_URL must start with https://. Actual=$url", url.startsWith("https://"))
        assertTrue("Release BASE_URL must not contain example.invalid. Actual=$url", !url.contains("example.invalid", ignoreCase = true))
        assertTrue("Release BASE_URL must not contain TODO_EXTERNAL. Actual=$url", !url.contains("TODO_EXTERNAL", ignoreCase = true))
        assertTrue("Release BASE_URL must not contain localhost. Actual=$url", !url.contains("localhost", ignoreCase = true))
    }
}
