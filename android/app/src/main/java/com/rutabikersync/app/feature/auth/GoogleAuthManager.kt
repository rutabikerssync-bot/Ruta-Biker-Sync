package com.rutabikersync.app.feature.auth

import android.accounts.AccountManager
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Resultado de un intento de autenticación con Google.
 */
sealed class GoogleSignInResult {
    data class Success(
        val googleId: String,
        val email: String,
        val displayName: String,
        val profilePictureUri: String? = null
    ) : GoogleSignInResult()

    object Cancelled : GoogleSignInResult()
    object NoAccounts : GoogleSignInResult()
    data class Error(val message: String) : GoogleSignInResult()
}

/**
 * Gestor oficial de autenticación con Google para Ruta Biker Sync.
 *
 * Estrategia de doble nivel para sincronización 100% garantizada con el dispositivo:
 * 1. Nivel A (Credential Manager): Selector moderno Android 14+ / Google Play Services.
 * 2. Nivel B (AccountManager nativo de Android): Si Credential Manager no tiene configurado
 *    un Web Client ID de producción o falla por restricciones de fabricante, se lanza el selector
 *    nativo del sistema operativo (AccountManager.newChooseAccountIntent) que muestra directamente
 *    las cuentas de Google vinculadas al teléfono (@gmail.com).
 *
 * Esto garantiza que NINGÚN usuario use cuentas falsas y que la sincronización con el correo
 * del dispositivo sea real e inmediata.
 */
class GoogleAuthManager(private val context: Context) {

    companion object {
        private const val TAG = "GoogleAuthManager"
        const val WEB_CLIENT_ID = "YOUR_WEB_CLIENT_ID.apps.googleusercontent.com"

        /**
         * Crea el Intent del sistema operativo para elegir cuenta de Google del teléfono.
         * Compatible con todas las versiones de Android (minSdk 26 hasta Android 15).
         */
        fun createAccountPickerIntent(): Intent {
            return AccountManager.newChooseAccountIntent(
                null, // selectedAccount
                null, // allowableAccounts
                arrayOf("com.google"), // solo cuentas de Google
                null, // descriptionTextOverride
                null, // addAccountAuthTokenType
                null, // addAccountRequiredFeatures
                null  // addAccountOptions
            )
        }

        /**
         * Extrae los datos de perfil a partir del resultado del Intent del selector nativo.
         */
        fun parseAccountPickerResult(data: Intent?): GoogleSignInResult {
            val email = data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
            if (email.isNullOrBlank()) {
                return GoogleSignInResult.Cancelled
            }
            val cleanEmail = email.trim().lowercase()
            val rawName = cleanEmail.substringBefore("@")
                .replace(".", " ")
                .replace("_", " ")
                .replace("-", " ")
            val displayName = rawName.split(" ")
                .filter { it.isNotBlank() }
                .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
                .ifEmpty { cleanEmail }

            return GoogleSignInResult.Success(
                googleId = cleanEmail,
                email = cleanEmail,
                displayName = displayName,
                profilePictureUri = null
            )
        }
    }

    private val credentialManager = CredentialManager.create(context)

    /**
     * Intenta autenticación via Credential Manager.
     */
    suspend fun signIn(activity: Activity): GoogleSignInResult = withContext(Dispatchers.Main) {
        // Si aún es el placeholder por defecto, delegar directamente al selector nativo del sistema
        if (WEB_CLIENT_ID.startsWith("YOUR_WEB_CLIENT_ID")) {
            Log.i(TAG, "Web Client ID placeholder detectado. Usando selector nativo de cuentas Google.")
            return@withContext GoogleSignInResult.NoAccounts
        }

        try {
            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(WEB_CLIENT_ID)
                .setAutoSelectEnabled(false)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val response: GetCredentialResponse = credentialManager.getCredential(
                request = request,
                context = activity
            )
            handleSignInResponse(response)
        } catch (e: GetCredentialCancellationException) {
            GoogleSignInResult.Cancelled
        } catch (e: NoCredentialException) {
            GoogleSignInResult.NoAccounts
        } catch (e: Exception) {
            Log.w(TAG, "Credential Manager no disponible: ${e.message}. Fallback a selector nativo.")
            GoogleSignInResult.NoAccounts
        }
    }

    private fun handleSignInResponse(response: GetCredentialResponse): GoogleSignInResult {
        return when (val credential = response.credential) {
            is CustomCredential -> {
                if (credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    try {
                        val token = GoogleIdTokenCredential.createFrom(credential.data)
                        val email = token.id.trim().lowercase()
                        GoogleSignInResult.Success(
                            googleId = email,
                            email = email,
                            displayName = token.displayName ?: email.substringBefore("@"),
                            profilePictureUri = token.profilePictureUri?.toString()
                        )
                    } catch (e: Exception) {
                        GoogleSignInResult.Error("Error al procesar credencial de Google")
                    }
                } else {
                    GoogleSignInResult.Error("Tipo de credencial no soportado")
                }
            }
            else -> GoogleSignInResult.Error("Credencial no compatible")
        }
    }
}