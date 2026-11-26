package com.rutabikersync.app.feature.auth

import android.content.Context
import android.content.SharedPreferences
import com.rutabikersync.app.feature.groups.AccountType
import java.util.UUID

/**
 * Contrato de repositorio de perfil y sesión de usuario para desacoplamiento y pruebas unitarias puras.
 */
interface IProfileRepository {
    fun isAuthenticated(): Boolean
    fun getGoogleUid(): String?
    fun getGoogleEmail(): String?
    fun getGooglePhotoUrl(): String?
    fun setAuthenticated(uid: String, email: String, displayName: String?, photoUrl: String?)
    fun getUserProfile(): UserProfile?
    fun isProfileComplete(): Boolean
    fun saveUserProfile(profile: UserProfile)
    fun getUserRole(): AccountType?
    fun saveUserRole(role: AccountType)
    fun getClubId(): String?
    fun saveClubId(clubId: String?)
    fun clearAll()
}

/**
 * Repositorio de persistencia del perfil de usuario y estado de autenticación.
 */
class ProfileRepository(context: Context) : IProfileRepository {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        const val PREFS_NAME = "rbs_auth_prefs"

        private const val KEY_IS_AUTH = "is_authenticated"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USER_EMAIL = "user_email"
        private const val KEY_USER_DISPLAY_NAME = "user_display_name"
        private const val KEY_USER_PHOTO_URL = "user_photo_url"
        private const val KEY_ACCOUNT_TYPE = "account_type"
        private const val KEY_BIKE_MODEL = "user_bike_model"
        private const val KEY_MAP_COLOR = "user_map_color"
        private const val KEY_EMERGENCY_NAME = "emergency_contact_name"
        private const val KEY_EMERGENCY_PHONE = "emergency_contact_phone"
        private const val KEY_BLOOD_TYPE = "blood_type"
        private const val KEY_PROFILE_COMPLETE = "user_profile_complete"
        private const val KEY_CLUB_ID = "current_club_id"
        private const val KEY_MOTO_BRAND = "motorcycle_brand"
        private const val KEY_MOTO_DISPLACEMENT = "motorcycle_displacement"
        private const val KEY_MOTO_YEAR = "motorcycle_year"
        private const val KEY_MOTO_COLOR = "motorcycle_color"
        private const val KEY_MOTO_SERIAL = "motorcycle_serial"
        private const val KEY_MOTO_PLATE = "motorcycle_plate"
        private const val KEY_MOTO_PHOTO_URL = "motorcycle_photo_url"
        private const val KEY_USER_PHOTO_BASE64 = "user_photo_base64"
        private const val KEY_MOTO_PHOTO_BASE64 = "motorcycle_photo_base64"
        private const val KEY_PROFILE_VISIBILITY = "profile_visibility"
        private const val KEY_MOTO_VISIBILITY = "motorcycle_visibility"

        fun generateDeterministicUuid(email: String): String {
            val cleanEmail = email.trim().lowercase()
            return UUID.nameUUIDFromBytes("rbs:google:$cleanEmail".toByteArray(Charsets.UTF_8)).toString()
        }
    }

    override fun isAuthenticated(): Boolean = prefs.getBoolean(KEY_IS_AUTH, false)

    override fun getGoogleUid(): String? = prefs.getString(KEY_USER_ID, null)

    override fun getGoogleEmail(): String? = prefs.getString(KEY_USER_EMAIL, null)

    override fun getGooglePhotoUrl(): String? = prefs.getString(KEY_USER_PHOTO_URL, null)

    private fun ensureValidUuid(uid: String, email: String): String {
        return try {
            UUID.fromString(uid)
            uid
        } catch (_: Exception) {
            generateDeterministicUuid(email)
        }
    }

    override fun setAuthenticated(uid: String, email: String, displayName: String?, photoUrl: String?) {
        val cleanEmail = email.trim().lowercase()
        val validUid = ensureValidUuid(uid, cleanEmail)

        prefs.edit()
            .putBoolean(KEY_IS_AUTH, true)
            .putString(KEY_USER_ID, validUid)
            .putString(KEY_USER_EMAIL, cleanEmail)
            .putString(KEY_USER_DISPLAY_NAME, displayName ?: cleanEmail.substringBefore("@"))
            .putString(KEY_USER_PHOTO_URL, photoUrl)
            .apply()
    }

    override fun getUserProfile(): UserProfile? {
        val uid = prefs.getString(KEY_USER_ID, null) ?: return null
        val email = prefs.getString(KEY_USER_EMAIL, "") ?: ""
        val alias = prefs.getString(KEY_USER_DISPLAY_NAME, "")?.replace("Capit?n", "Capitán") ?: ""
        val bike = prefs.getString(KEY_BIKE_MODEL, "") ?: ""
        val color = prefs.getString(KEY_MAP_COLOR, "#E5B869") ?: "#E5B869"
        val emergencyName = prefs.getString(KEY_EMERGENCY_NAME, "") ?: ""
        val emergencyPhone = prefs.getString(KEY_EMERGENCY_PHONE, "") ?: ""
        val bloodLabel = prefs.getString(KEY_BLOOD_TYPE, "O+") ?: "O+"
        val photoUrl = prefs.getString(KEY_USER_PHOTO_URL, null)
        val photoBase64 = prefs.getString(KEY_USER_PHOTO_BASE64, null)

        val motoBrand = prefs.getString(KEY_MOTO_BRAND, "") ?: ""
        val motoDisp = prefs.getInt(KEY_MOTO_DISPLACEMENT, 0)
        val motoYear = prefs.getInt(KEY_MOTO_YEAR, 0)
        val motoColor = prefs.getString(KEY_MOTO_COLOR, "") ?: ""
        val motoSerial = prefs.getString(KEY_MOTO_SERIAL, "") ?: ""
        val motoPlate = prefs.getString(KEY_MOTO_PLATE, "") ?: ""
        val motoPhotoUrl = prefs.getString(KEY_MOTO_PHOTO_URL, null)
        val motoPhotoBase64 = prefs.getString(KEY_MOTO_PHOTO_BASE64, null)
        val profVis = Visibility.fromString(prefs.getString(KEY_PROFILE_VISIBILITY, "PUBLIC"))
        val motoVis = Visibility.fromString(prefs.getString(KEY_MOTO_VISIBILITY, "PUBLIC"))

        val profile = UserProfile(
            uid = uid,
            email = email,
            photoUrl = photoUrl,
            photoBase64 = photoBase64,
            aliasBiker = alias,
            motorcycleModel = bike,
            pinColorHex = color,
            emergencyContactName = emergencyName,
            emergencyContactPhone = emergencyPhone,
            bloodType = BloodType.fromLabel(bloodLabel),
            motorcycleBrand = motoBrand,
            motorcycleDisplacement = motoDisp,
            motorcycleYear = motoYear,
            motorcycleColor = motoColor,
            motorcycleSerial = motoSerial,
            motorcyclePlate = motoPlate,
            motorcyclePhotoUrl = motoPhotoUrl,
            motorcyclePhotoBase64 = motoPhotoBase64,
            profileVisibility = profVis,
            motorcycleVisibility = motoVis
        )
        return profile
    }

    /**
     * Derivación pura del estado completo del perfil a partir de los campos reales.
     * Cero banderas booleanas desincronizadas.
     */
    override fun isProfileComplete(): Boolean = getUserProfile()?.isComplete == true

    override fun saveUserProfile(profile: UserProfile) {
        val cleanColor = if (UserProfile.ALLOWED_PIN_COLORS.contains(profile.pinColorHex.uppercase())) {
            profile.pinColorHex.uppercase()
        } else {
            "#E5B869"
        }
        val cleanEmail = profile.email.trim().lowercase()
        val validUid = ensureValidUuid(profile.uid, cleanEmail)

        prefs.edit()
            .putString(KEY_USER_ID, validUid)
            .putString(KEY_USER_EMAIL, cleanEmail)
            .putString(KEY_USER_DISPLAY_NAME, profile.aliasBiker.trim())
            .putString(KEY_BIKE_MODEL, profile.motorcycleModel.trim())
            .putString(KEY_MAP_COLOR, cleanColor)
            .putString(KEY_EMERGENCY_NAME, profile.emergencyContactName.trim())
            .putString(KEY_EMERGENCY_PHONE, profile.emergencyContactPhone.trim())
            .putString(KEY_BLOOD_TYPE, profile.bloodType.label)
            .putString(KEY_USER_PHOTO_URL, profile.photoUrl)
            .putString(KEY_USER_PHOTO_BASE64, profile.photoBase64)
            .putString(KEY_MOTO_BRAND, profile.motorcycleBrand.trim())
            .putInt(KEY_MOTO_DISPLACEMENT, profile.motorcycleDisplacement)
            .putInt(KEY_MOTO_YEAR, profile.motorcycleYear)
            .putString(KEY_MOTO_COLOR, profile.motorcycleColor.trim())
            .putString(KEY_MOTO_SERIAL, profile.motorcycleSerial.trim())
            .putString(KEY_MOTO_PLATE, profile.motorcyclePlate.trim().uppercase())
            .putString(KEY_MOTO_PHOTO_URL, profile.motorcyclePhotoUrl)
            .putString(KEY_MOTO_PHOTO_BASE64, profile.motorcyclePhotoBase64)
            .putString(KEY_PROFILE_VISIBILITY, profile.profileVisibility.name)
            .putString(KEY_MOTO_VISIBILITY, profile.motorcycleVisibility.name)
            .remove(KEY_PROFILE_COMPLETE) // Purga de la bandera heredada
            .apply()
    }

    override fun getUserRole(): AccountType? {
        val uid = getGoogleUid()
        val email = getGoogleEmail()

        // 1. Clave canónica
        val directStr = prefs.getString(KEY_ACCOUNT_TYPE, null)
        if (!directStr.isNullOrBlank()) {
            runCatching { return AccountType.valueOf(directStr) }
        }

        // 2. Clave indexada por UID
        if (!uid.isNullOrBlank()) {
            val uidRoleStr = prefs.getString("user_role_$uid", null)
            if (!uidRoleStr.isNullOrBlank()) {
                runCatching { return AccountType.valueOf(uidRoleStr) }
            }
        }

        // 3. Clave indexada por email
        if (!email.isNullOrBlank()) {
            val emailRoleStr = prefs.getString("google_role_${email.lowercase()}", null)
            if (!emailRoleStr.isNullOrBlank()) {
                runCatching { return AccountType.valueOf(emailRoleStr) }
            }
        }

        return null
    }

    override fun saveUserRole(role: AccountType) {
        val uid = getGoogleUid()
        val email = getGoogleEmail()
        val editor = prefs.edit().putString(KEY_ACCOUNT_TYPE, role.name)

        if (!uid.isNullOrBlank()) {
            editor.putString("user_role_$uid", role.name)
        }
        if (!email.isNullOrBlank()) {
            editor.putString("google_role_${email.lowercase()}", role.name)
            editor.putString("google_role_last", role.name)
        }
        editor.apply()
    }

    override fun getClubId(): String? = prefs.getString(KEY_CLUB_ID, null)

    override fun saveClubId(clubId: String?) {
        if (clubId.isNullOrBlank()) {
            prefs.edit().remove(KEY_CLUB_ID).apply()
        } else {
            prefs.edit().putString(KEY_CLUB_ID, clubId).apply()
        }
    }

    override fun clearAll() {
        prefs.edit().clear().apply()
    }
}
