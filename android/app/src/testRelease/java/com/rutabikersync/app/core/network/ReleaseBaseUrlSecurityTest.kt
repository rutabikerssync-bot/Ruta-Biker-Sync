package com.rutabikersync.app.core.network

import com.rutabikersync.app.BuildConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ReleaseBaseUrlSecurityTest {
    @Test
    fun `standardRelease BASE_URL policy PASS_REAL FAIL_PLACEHOLDER FAIL_INSEGURO`() {
        val url = BuildConfig.BASE_URL
        val isPlaceholder = Regex(
            "example\\.invalid|TODO_EXTERNAL|localhost|\\.invalid|pending",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(url)

        if (!url.startsWith("https://")) {
            fail("FAIL_INSEGURO: release BASE_URL no usa esquema TLS (https). Actual=$url")
        }
        if (isPlaceholder) {
            fail("FAIL_PLACEHOLDER: release BASE_URL es placeholder, falta URL_PROD real (-Prbs.prod.url o RBS_PROD_URL). Actual=$url")
        }

        // PASS_REAL
        assertTrue("PASS_REAL: release BASE_URL debe ser https. Actual=$url", url.startsWith("https://"))
        assertFalse("PASS_REAL: release BASE_URL no debe contener marcadores placeholder. Actual=$url", isPlaceholder)
    }
}
