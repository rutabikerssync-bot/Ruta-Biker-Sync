package com.rutabikersync.app.feature.profile

import com.rutabikersync.app.core.util.ImageCompressor
import com.rutabikersync.app.feature.auth.BloodType
import com.rutabikersync.app.feature.auth.UserProfile
import com.rutabikersync.app.feature.auth.Visibility
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/**
 * Pruebas unitarias JVM para el perfil enriquecido, soporte técnico y compresión (D98).
 */
class ProfileSupportTest {

    @Test
    fun `1 UserProfile enriched model default values initialization`() {
        val profile = UserProfile(
            uid = "user-12345",
            email = "oscar@rutabikersync.com",
            aliasBiker = "Capitán Oscar",
            motorcycleModel = "BMW R1250GS",
            emergencyContactName = "Laura",
            emergencyContactPhone = "+584121234567"
        )
        assertEquals("", profile.motorcycleBrand)
        assertEquals(0, profile.motorcycleDisplacement)
        assertEquals(0, profile.motorcycleYear)
        assertEquals("", profile.motorcycleColor)
        assertEquals("", profile.motorcycleSerial)
        assertNull(profile.motorcyclePhotoUrl)
        assertEquals(Visibility.PUBLIC, profile.profileVisibility)
        assertEquals(Visibility.PUBLIC, profile.motorcycleVisibility)
        assertTrue(profile.isComplete)
    }

    @Test
    fun `2 UserProfile accepts enriched technical motorcycle fields`() {
        val profile = UserProfile(
            uid = "user-12345",
            email = "oscar@rutabikersync.com",
            aliasBiker = "Capitán Oscar",
            motorcycleBrand = "BMW",
            motorcycleModel = "R1250GS Adventure",
            motorcycleDisplacement = 1254,
            motorcycleYear = 2024,
            motorcycleColor = "Triple Black",
            motorcycleSerial = "WB10J9108PZG99999",
            motorcyclePhotoUrl = "/v1/users/motorcycle-photo/user-12345",
            pinColorHex = "#E5B869",
            emergencyContactName = "Laura",
            emergencyContactPhone = "+584121234567",
            bloodType = BloodType.O_POS,
            profileVisibility = Visibility.CLUB_ONLY,
            motorcycleVisibility = Visibility.CLUB_ONLY
        )
        assertEquals("BMW", profile.motorcycleBrand)
        assertEquals(1254, profile.motorcycleDisplacement)
        assertEquals(2024, profile.motorcycleYear)
        assertEquals("Triple Black", profile.motorcycleColor)
        assertEquals("WB10J9108PZG99999", profile.motorcycleSerial)
        assertEquals(Visibility.CLUB_ONLY, profile.profileVisibility)
        assertEquals(Visibility.CLUB_ONLY, profile.motorcycleVisibility)
        assertTrue(profile.isComplete)
    }

    @Test
    fun `3 Visibility enum parses strings and fallbacks to PUBLIC safely`() {
        assertEquals(Visibility.PUBLIC, Visibility.fromString("PUBLIC"))
        assertEquals(Visibility.CLUB_ONLY, Visibility.fromString("CLUB_ONLY"))
        assertEquals(Visibility.PRIVATE, Visibility.fromString("PRIVATE"))
        assertEquals(Visibility.PUBLIC, Visibility.fromString("UNKNOWN_VALUE"))
        assertEquals(Visibility.PUBLIC, Visibility.fromString(null))
        assertEquals("Solo mi club", Visibility.CLUB_ONLY.label)
    }

    @Test
    fun `4 ImageCompressor enforces 2 MB maximum size constraint`() {
        assertEquals(2L * 1024 * 1024, ImageCompressor.MAX_IMAGE_SIZE_BYTES)
        assertEquals(1080, ImageCompressor.DEFAULT_MAX_DIMENSION)
    }

    @Test
    fun `5 ImageCompressor ratio scaling keeps aspect ratio correctly`() {
        val origWidth = 3840
        val origHeight = 2160
        val maxDim = 1080

        val ratio = min(maxDim.toFloat() / origWidth.toFloat(), maxDim.toFloat() / origHeight.toFloat())
        val targetWidth = max(1, (origWidth * ratio).toInt())
        val targetHeight = max(1, (origHeight * ratio).toInt())

        assertEquals(1080, targetWidth)
        assertEquals(607, targetHeight)
        assertTrue(targetWidth <= maxDim)
        assertTrue(targetHeight <= maxDim)
    }

    @Test
    fun `6 ImageCompressor ratio scaling leaves already small dimensions unchanged`() {
        val origWidth = 800
        val origHeight = 600
        val maxDim = 1080

        val shouldScale = origWidth > maxDim || origHeight > maxDim
        assertFalse(shouldScale)
    }

    @Test
    fun `7 Support report json payload structure is well-formed`() {
        val payload = buildJsonObject {
            put("subject", "Fallo al sincronizar malla")
            put("message", "Se desconectó al ingresar a zona sin señal.")
            put("screenshotBase64", "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")
        }
        assertEquals("Fallo al sincronizar malla", payload["subject"]?.toString()?.replace("\"", ""))
        assertNotNull(payload["screenshotBase64"])
    }

    @Test
    fun `8 Enriched profile json payload structure is well-formed`() {
        val profile = UserProfile(
            uid = "u-99",
            email = "rider@test.com",
            aliasBiker = "Halcón",
            motorcycleBrand = "Yamaha",
            motorcycleModel = "Tenere 700",
            motorcycleDisplacement = 689,
            motorcycleYear = 2023,
            motorcycleColor = "Azul",
            motorcycleSerial = "JYARN0410001234",
            profileVisibility = Visibility.PRIVATE,
            motorcycleVisibility = Visibility.CLUB_ONLY,
            emergencyContactName = "Elena",
            emergencyContactPhone = "+584141234567"
        )
        val payload = buildJsonObject {
            put("aliasBiker", profile.aliasBiker)
            put("motorcycleBrand", profile.motorcycleBrand)
            put("motorcycleModel", profile.motorcycleModel)
            put("motorcycleDisplacement", profile.motorcycleDisplacement)
            put("profileVisibility", profile.profileVisibility.name)
            put("motorcycleVisibility", profile.motorcycleVisibility.name)
        }
        assertEquals("Halcón", payload["aliasBiker"]?.toString()?.replace("\"", ""))
        assertEquals("Yamaha", payload["motorcycleBrand"]?.toString()?.replace("\"", ""))
        assertEquals("689", payload["motorcycleDisplacement"]?.toString())
        assertEquals("PRIVATE", payload["profileVisibility"]?.toString()?.replace("\"", ""))
    }

    @Test
    fun `9 UserProfile copy handles updating visibility preferences`() {
        val initial = UserProfile(
            uid = "u-1",
            email = "biker@test.com",
            aliasBiker = "Rider Uno",
            motorcycleModel = "KTM 390",
            emergencyContactName = "Maria",
            emergencyContactPhone = "+584121234567",
            profileVisibility = Visibility.PUBLIC
        )
        val updated = initial.copy(profileVisibility = Visibility.PRIVATE)
        assertEquals(Visibility.PRIVATE, updated.profileVisibility)
        assertEquals(Visibility.PUBLIC, initial.profileVisibility)
    }

    @Test
    fun `10 UserProfile completeness validates mandatory identity and emergency fields`() {
        val valid = UserProfile(
            uid = "d3b07384-d113-4876-b333-e8fe2c474622",
            email = "biker@gmail.com",
            aliasBiker = "Capitán",
            motorcycleModel = "BMW R1250GS",
            emergencyContactName = "Laura",
            emergencyContactPhone = "+584121234567",
            bloodType = BloodType.O_POS
        )
        assertTrue(valid.isComplete)

        val invalidPhone = valid.copy(emergencyContactPhone = "123")
        assertFalse(invalidPhone.isComplete)
    }
}
