package com.rutabikersync.app.feature.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * El login de Google entrega `google-<email>` y no debe destruir el UUID que
 * el backend ya registró para ese mismo email.
 */
class ProfileRepositoryUidTest {

    private val email = "omartinezpaz@gmail.com"
    private val googleUid = "google-$email"
    private val backendUid = "b5d0228d-8ab9-49fd-9cc7-efa452a484f2"

    @Test
    fun conservaUuidDelBackendCuandoElEmailCoincide() {
        val result = ProfileRepository.resolveAuthenticatedUid(
            uid = googleUid,
            email = email,
            existingUid = backendUid,
            existingEmail = email
        )

        assertEquals(backendUid, result)
        assertNotEquals(ProfileRepository.generateDeterministicUuid(email), result)
    }

    @Test
    fun generaDeterministaEnInstalacionNueva() {
        val result = ProfileRepository.resolveAuthenticatedUid(
            uid = googleUid,
            email = email,
            existingUid = null,
            existingEmail = null
        )

        assertEquals(ProfileRepository.generateDeterministicUuid(email), result)
    }

    @Test
    fun noReutilizaUuidGuardadoParaOtroEmail() {
        val otherEmail = "otro@gmail.com"
        val result = ProfileRepository.resolveAuthenticatedUid(
            uid = googleUid,
            email = email,
            existingUid = backendUid,
            existingEmail = otherEmail
        )

        assertEquals(ProfileRepository.generateDeterministicUuid(email), result)
        assertNotEquals(ProfileRepository.generateDeterministicUuid(otherEmail), result)
    }

    @Test
    fun noReutilizaUidSinteticoLocal() {
        val result = ProfileRepository.resolveAuthenticatedUid(
            uid = googleUid,
            email = email,
            existingUid = "u-abc12345",
            existingEmail = email
        )

        assertEquals(ProfileRepository.generateDeterministicUuid(email), result)
    }

    @Test
    fun normalizaMayusculasYEspaciosDelEmail() {
        val result = ProfileRepository.resolveAuthenticatedUid(
            uid = googleUid,
            email = "  OMARTINEZPAZ@GMAIL.COM ",
            existingUid = backendUid,
            existingEmail = " Omartinezpaz@gmail.com "
        )

        assertEquals(backendUid, result)
    }
}
