package com.rutabikersync.app.core.util

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Desenvuelve de forma recursiva cualquier ContextWrapper o ContextThemeWrapper de Compose
 * hasta encontrar la Activity subyacente.
 */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Gestor dinámico y persistente de idioma y localización para Ruta Biker Sync.
 * Soporta sincronización automática con el sistema móvil (system) y selección manual
 * entre Español (es), Inglés (en) y Portugués (pt).
 */
object LocaleHelper {
    private const val PREFS_NAME = "rbs_settings_prefs"
    private const val KEY_LANGUAGE = "selected_language"

    const val LANG_SYSTEM = "system"
    const val LANG_ES = "es"
    const val LANG_EN = "en"
    const val LANG_PT = "pt"

    private val _currentLanguage = MutableStateFlow(LANG_ES)
    val currentLanguage: StateFlow<String> = _currentLanguage.asStateFlow()

    /**
     * Detecta el idioma del sistema del dispositivo móvil (Android).
     * Si el móvil está en Portugués -> "pt", en Inglés -> "en", caso contrario "es".
     */
    fun getSystemLanguage(): String {
        val sysLocale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            android.content.res.Resources.getSystem().configuration.locales[0]
        } else {
            @Suppress("DEPRECATION")
            android.content.res.Resources.getSystem().configuration.locale
        }
        val lang = sysLocale.language.lowercase()
        return when {
            lang.startsWith("pt") -> LANG_PT
            lang.startsWith("en") -> LANG_EN
            else -> LANG_ES
        }
    }

    fun init(context: Context) {
        val resolved = getResolvedLanguage(context)
        _currentLanguage.value = resolved
    }

    /**
     * Devuelve el código configurado por el usuario ("system", "es", "en", "pt").
     * Por defecto retorna "system" (sincronizado con el móvil).
     */
    fun getLanguage(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LANGUAGE, LANG_SYSTEM) ?: LANG_SYSTEM
    }

    /**
     * Resuelve el idioma efectivo para cargar recursos ("es", "en", "pt").
     * Si el usuario eligió "system", consulta el locale del dispositivo.
     */
    fun getResolvedLanguage(context: Context): String {
        val selected = getLanguage(context)
        return if (selected == LANG_SYSTEM) getSystemLanguage() else selected
    }

    fun setLanguage(context: Context, languageCode: String): Context {
        persist(context, languageCode)
        val resolved = if (languageCode == LANG_SYSTEM) getSystemLanguage() else languageCode
        _currentLanguage.value = resolved
        val newContext = applyLocale(context, resolved)
        context.findActivity()?.recreate()
        return newContext
    }

    private fun persist(context: Context, languageCode: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LANGUAGE, languageCode).apply()
    }

    fun applyLocale(context: Context): Context {
        val lang = getResolvedLanguage(context)
        return applyLocale(context, lang)
    }

    fun applyLocale(context: Context, languageCode: String): Context {
        val resolved = if (languageCode == LANG_SYSTEM) getSystemLanguage() else languageCode
        val locale = when (resolved.lowercase()) {
            "en" -> Locale.ENGLISH
            "pt" -> Locale("pt", "BR")
            else -> Locale("es", "ES")
        }
        Locale.setDefault(locale)

        val res = context.resources
        val config = Configuration(res.configuration)

        config.setLocale(locale)
        config.setLayoutDirection(locale)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val localeList = LocaleList(locale)
            LocaleList.setDefault(localeList)
            config.setLocales(localeList)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val localeManager = context.getSystemService(Context.LOCALE_SERVICE) as? LocaleManager
                localeManager?.applicationLocales = LocaleList(locale)
            } catch (_: Exception) {}
        }

        @Suppress("DEPRECATION")
        res.updateConfiguration(config, res.displayMetrics)

        return context.createConfigurationContext(config)
    }
}

