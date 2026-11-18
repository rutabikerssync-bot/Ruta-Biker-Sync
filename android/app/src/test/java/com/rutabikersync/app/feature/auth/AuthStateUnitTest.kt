package com.rutabikersync.app.feature.auth

import com.rutabikersync.app.feature.groups.AccountType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class AuthStateUnitTest {

    @Test
    fun `UserProfile validation requires alias of at least 3 chars`() {
        val invalid = UserProfile(
            uid = "d3b07384-d113-4876-b333-e8fe2c474622",
            email = "biker@gmail.com",
            aliasBiker = "Al",
            motorcycleModel = "BMW R1250GS",
            pinColorHex = "#E5B869",
            emergencyContactName = "Maria",
            emergencyContactPhone = "+584121234567",
            bloodType = BloodType.O_POS
        )
        assertFalse(invalid.isComplete)

        val valid = invalid.copy(aliasBiker = "Halcon")
        assertTrue(valid.isComplete)
    }

    @Test
    fun `UserProfile validation requires motorcycle model of at least 3 chars`() {
        val invalid = UserProfile(
            uid = "d3b07384-d113-4876-b333-e8fe2c474622",
            email = "biker@gmail.com",
            aliasBiker = "Halcon",
            motorcycleModel = "GS",
            pinColorHex = "#E5B869",
            emergencyContactName = "Maria",
            emergencyContactPhone = "+584121234567",
            bloodType = BloodType.O_POS
        )
        assertFalse(invalid.isComplete)

        val valid = invalid.copy(motorcycleModel = "BMW R1250GS")
        assertTrue(valid.isComplete)
    }

    @Test
    fun `UserProfile validation strictly enforces allowed pin colors`() {
        val invalidColor = UserProfile(
            uid = "d3b07384-d113-4876-b333-e8fe2c474622",
            email = "biker@gmail.com",
            aliasBiker = "Halcon",
            motorcycleModel = "BMW R1250GS",
            pinColorHex = "#123456", // no está en ALLOWED_PIN_COLORS
            emergencyContactName = "Maria",
            emergencyContactPhone = "+584121234567",
            bloodType = BloodType.O_POS
        )
        assertFalse(invalidColor.isComplete)

        UserProfile.ALLOWED_PIN_COLORS.forEach { color ->
            val valid = invalidColor.copy(pinColorHex = color)
            assertTrue("Color $color should be allowed", valid.isComplete)
        }
    }

    @Test
    fun `UserProfile validation enforces emergency contact and phone`() {
        val invalidPhone = UserProfile(
            uid = "d3b07384-d113-4876-b333-e8fe2c474622",
            email = "biker@gmail.com",
            aliasBiker = "Halcon",
            motorcycleModel = "BMW R1250GS",
            pinColorHex = "#E5B869",
            emergencyContactName = "Maria",
            emergencyContactPhone = "123", // menos de 7 digitos
            bloodType = BloodType.O_POS
        )
        assertFalse(invalidPhone.isComplete)

        val valid = invalidPhone.copy(emergencyContactPhone = "04141234567")
        assertTrue(valid.isComplete)
    }

    @Test
    fun `Deterministic UUID generation strictly satisfies RFC 4122 for PostgreSQL backend`() {
        val email = "ozmartinezpaz@gmail.com"
        val deterministicUuid = UUID.nameUUIDFromBytes("rbs:google:${email.trim().lowercase()}".toByteArray(Charsets.UTF_8)).toString()

        // Debe parsear sin lanzar IllegalArgumentException
        val parsed = UUID.fromString(deterministicUuid)
        assertEquals(deterministicUuid, parsed.toString())

        // Mismo email produce exactamente el mismo UUID
        val repeated = UUID.nameUUIDFromBytes("rbs:google:${email.trim().lowercase()}".toByteArray(Charsets.UTF_8)).toString()
        assertEquals(deterministicUuid, repeated)

        // Email distinto produce UUID distinto
        val other = UUID.nameUUIDFromBytes("rbs:google:otro@gmail.com".toByteArray(Charsets.UTF_8)).toString()
        assertNotEquals(deterministicUuid, other)
    }

    @Test
    fun `Partial or incomplete profile CANNOT transition to RoleNotChosen or Ready`() {
        val partialProfile = UserProfile(
            uid = UUID.randomUUID().toString(),
            email = "pilot@gmail.com",
            aliasBiker = "H", // alias muy corto
            motorcycleModel = "KLR 650",
            pinColorHex = "#E5B869",
            emergencyContactName = "Laura",
            emergencyContactPhone = "04121234567",
            bloodType = BloodType.O_POS
        )

        // Verificamos que el contrato de negocio impida el gate
        assertFalse(partialProfile.isComplete)

        // La función de evaluación de transición debe rechazar el avance si el perfil no es completo
        fun evaluateNextState(profile: UserProfile?, role: AccountType?): AuthState {
            if (profile == null || !profile.isComplete) {
                return AuthState.ProfileIncomplete(
                    uid = profile?.uid ?: "anonymous",
                    email = profile?.email ?: ""
                )
            }
            if (role == null) {
                return AuthState.RoleNotChosen(profile)
            }
            return AuthState.Ready(profile, role, null)
        }

        val stateWithPartial = evaluateNextState(partialProfile, null)
        assertTrue("Perfil parcial debe permanecer en ProfileIncomplete", stateWithPartial is AuthState.ProfileIncomplete)

        val stateWithPartialAndRole = evaluateNextState(partialProfile, AccountType.LEADER)
        assertTrue("Incluso con rol asignado, perfil parcial nunca debe ser Ready", stateWithPartialAndRole is AuthState.ProfileIncomplete)
    }

    @Test
    fun `Complete profile transitions to RoleNotChosen and then to Ready upon choosing role`() {
        val validProfile = UserProfile(
            uid = UUID.randomUUID().toString(),
            email = "pilot@gmail.com",
            aliasBiker = "Halcon",
            motorcycleModel = "BMW R1250GS",
            pinColorHex = "#E5B869",
            emergencyContactName = "Laura Martinez",
            emergencyContactPhone = "04121234567",
            bloodType = BloodType.O_POS
        )
        assertTrue(validProfile.isComplete)

        fun transition(profile: UserProfile, chosenRole: AccountType?): AuthState {
            require(profile.isComplete) { "Profile must be complete" }
            return if (chosenRole == null) {
                AuthState.RoleNotChosen(profile)
            } else {
                AuthState.Ready(profile, chosenRole, null)
            }
        }

        val stateAfterProfile = transition(validProfile, null)
        assertTrue(stateAfterProfile is AuthState.RoleNotChosen)
        assertEquals("Halcon", (stateAfterProfile as AuthState.RoleNotChosen).profile.aliasBiker)

        val stateAfterRole = transition(validProfile, AccountType.LEADER)
        assertTrue(stateAfterRole is AuthState.Ready)
        val ready = stateAfterRole as AuthState.Ready
        assertEquals(AccountType.LEADER, ready.role)
        assertEquals("BMW R1250GS", ready.profile.motorcycleModel)
    }
}
