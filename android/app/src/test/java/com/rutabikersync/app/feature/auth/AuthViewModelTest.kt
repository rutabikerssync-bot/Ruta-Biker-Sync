package com.rutabikersync.app.feature.auth

import android.app.Application
import com.rutabikersync.app.feature.groups.AccountType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Falso en memoria del repositorio de perfil para pruebas deterministas y herméticas del AuthViewModel.
 */
class FakeProfileRepository : IProfileRepository {
    private var authenticated: Boolean = false
    private var uid: String? = null
    private var email: String? = null
    private var photoUrl: String? = null
    private var profile: UserProfile? = null
    private var role: AccountType? = null
    private var clubId: String? = null

    override fun isAuthenticated(): Boolean = authenticated
    override fun getGoogleUid(): String? = uid
    override fun getGoogleEmail(): String? = email
    override fun getGooglePhotoUrl(): String? = photoUrl

    override fun setAuthenticated(uid: String, email: String, displayName: String?, photoUrl: String?) {
        this.authenticated = true
        this.uid = uid
        this.email = email
        this.photoUrl = photoUrl
    }

    override fun getUserProfile(): UserProfile? = profile
    override fun isProfileComplete(): Boolean = profile?.isComplete == true

    override fun saveUserProfile(profile: UserProfile) {
        this.profile = profile
    }

    override fun getUserRole(): AccountType? = role
    override fun saveUserRole(role: AccountType) {
        this.role = role
    }

    override fun getClubId(): String? = clubId
    override fun saveClubId(clubId: String?) {
        this.clubId = clubId
    }

    override fun clearAll() {
        authenticated = false
        uid = null
        email = null
        photoUrl = null
        profile = null
        role = null
        clubId = null
    }
}

/**
 * Pruebas unitarias de la máquina de estados AuthViewModel (Bloque A).
 * Verifica que ninguna pantalla principal pueda abrirse sin pasar por la cadena estricta de gates.
 */
class AuthViewModelTest {

    private lateinit var fakeRepo: FakeProfileRepository
    private lateinit var app: Application

    @Before
    fun setUp() {
        fakeRepo = FakeProfileRepository()
        app = Application()
    }

    @Test
    fun `checkSession emite NotAuthenticated si no hay sesion iniciada`() {
        val viewModel = AuthViewModel(app, fakeRepo)
        assertEquals(AuthState.NotAuthenticated, viewModel.authState.value)
    }

    @Test
    fun `checkSession emite NotAuthenticated si UID o email son nulos aunque isAuth sea true`() {
        fakeRepo.setAuthenticated("", "", null, null)
        val viewModel = AuthViewModel(app, fakeRepo)
        viewModel.checkSession()
        assertEquals(AuthState.NotAuthenticated, viewModel.authState.value)
    }

    @Test
    fun `checkSession emite ProfileIncomplete si hay sesion Google pero perfil no existe o es incompleto`() {
        val testUid = UUID.randomUUID().toString()
        val testEmail = "piloto@biker.com"
        fakeRepo.setAuthenticated(testUid, testEmail, "Piloto", null)

        val viewModel = AuthViewModel(app, fakeRepo)
        viewModel.checkSession()

        val state = viewModel.authState.value
        assertTrue("Debe estar en ProfileIncomplete", state is AuthState.ProfileIncomplete)
        val incomplete = state as AuthState.ProfileIncomplete
        assertEquals(testUid, incomplete.uid)
        assertEquals(testEmail, incomplete.email)
    }

    @Test
    fun `checkSession emite RoleNotChosen si el perfil motero esta completo pero rol es nulo`() {
        val testUid = UUID.randomUUID().toString()
        val testEmail = "piloto@biker.com"
        fakeRepo.setAuthenticated(testUid, testEmail, "Halcon", null)

        val completeProfile = UserProfile(
            uid = testUid,
            email = testEmail,
            aliasBiker = "Halcon",
            motorcycleModel = "Yamaha Tenere 700",
            pinColorHex = "#E5B869",
            emergencyContactName = "Laura",
            emergencyContactPhone = "04121234567",
            bloodType = BloodType.O_POS
        )
        fakeRepo.saveUserProfile(completeProfile)

        val viewModel = AuthViewModel(app, fakeRepo)
        viewModel.checkSession()

        val state = viewModel.authState.value
        assertTrue("Debe estar en RoleNotChosen", state is AuthState.RoleNotChosen)
        val roleNotChosen = state as AuthState.RoleNotChosen
        assertEquals("Halcon", roleNotChosen.profile.aliasBiker)
        assertTrue(roleNotChosen.profile.isComplete)
    }

    @Test
    fun `checkSession emite Ready solo cuando el perfil esta completo Y el rol fue elegido`() {
        val testUid = UUID.randomUUID().toString()
        val testEmail = "lider@biker.com"
        fakeRepo.setAuthenticated(testUid, testEmail, "Lobo", null)

        val completeProfile = UserProfile(
            uid = testUid,
            email = testEmail,
            aliasBiker = "Lobo",
            motorcycleModel = "BMW R1250GS",
            pinColorHex = "#16A34A",
            emergencyContactName = "Carlos",
            emergencyContactPhone = "04141234567",
            bloodType = BloodType.A_POS
        )
        fakeRepo.saveUserProfile(completeProfile)
        fakeRepo.saveUserRole(AccountType.LEADER)
        fakeRepo.saveClubId("club-alpha-01")

        val viewModel = AuthViewModel(app, fakeRepo)
        viewModel.checkSession()

        val state = viewModel.authState.value
        assertTrue("Debe ser Ready", state is AuthState.Ready)
        val ready = state as AuthState.Ready
        assertEquals(AccountType.LEADER, ready.role)
        assertEquals("club-alpha-01", ready.clubId)
        assertEquals("Lobo", ready.profile.aliasBiker)
    }

    @Test
    fun `saveProfile rechaza campos invalidos y no avanza de estado`() {
        val testUid = UUID.randomUUID().toString()
        val testEmail = "piloto@biker.com"
        fakeRepo.setAuthenticated(testUid, testEmail, "Piloto", null)

        val viewModel = AuthViewModel(app, fakeRepo)
        viewModel.checkSession()

        // Intento con alias de 1 letra (invalido segun UserProfile.isComplete)
        val success = viewModel.saveProfile(
            aliasBiker = "X",
            motorcycleModel = "BMW R1250GS",
            pinColorHex = "#E5B869",
            emergencyContactName = "Maria",
            emergencyContactPhone = "04121234567",
            bloodType = BloodType.O_POS
        )

        assertFalse(success)
        assertTrue(viewModel.authState.value is AuthState.ProfileIncomplete)
        assertNull(fakeRepo.getUserProfile())
    }

    @Test
    fun `chooseRole persiste rol y transiciona a Ready de inmediato`() {
        val testUid = UUID.randomUUID().toString()
        val testEmail = "piloto@biker.com"
        fakeRepo.setAuthenticated(testUid, testEmail, "Rayo", null)

        val completeProfile = UserProfile(
            uid = testUid,
            email = testEmail,
            aliasBiker = "Rayo",
            motorcycleModel = "Honda Africa Twin",
            pinColorHex = "#2563EB",
            emergencyContactName = "Ana",
            emergencyContactPhone = "04121234567",
            bloodType = BloodType.O_NEG
        )
        fakeRepo.saveUserProfile(completeProfile)

        val viewModel = AuthViewModel(app, fakeRepo)
        viewModel.checkSession()
        assertTrue(viewModel.authState.value is AuthState.RoleNotChosen)

        viewModel.chooseRole(AccountType.MEMBER)

        assertEquals(AccountType.MEMBER, fakeRepo.getUserRole())
        val finalState = viewModel.authState.value
        assertTrue(finalState is AuthState.Ready)
        assertEquals(AccountType.MEMBER, (finalState as AuthState.Ready).role)
    }

    @Test
    fun `logout limpia la persistencia y regresa de inmediato a NotAuthenticated`() {
        val testUid = UUID.randomUUID().toString()
        val testEmail = "piloto@biker.com"
        fakeRepo.setAuthenticated(testUid, testEmail, "Rayo", null)
        fakeRepo.saveUserRole(AccountType.MEMBER)

        val viewModel = AuthViewModel(app, fakeRepo)
        viewModel.logout()

        assertEquals(AuthState.NotAuthenticated, viewModel.authState.value)
        assertFalse(fakeRepo.isAuthenticated())
        assertNull(fakeRepo.getUserRole())
    }
}
