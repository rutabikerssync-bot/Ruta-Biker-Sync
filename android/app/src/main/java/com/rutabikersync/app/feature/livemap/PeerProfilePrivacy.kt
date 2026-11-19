package com.rutabikersync.app.feature.livemap

/**
 * Regla de privacidad del perfil en el mapa (R4.7/R4.8 reforzada con groupId real).
 *
 * - El piloto local siempre ve su propio perfil: no hay tercero al que proteger.
 * - Un peer remoto sólo muestra nombre/avatar/rol reales si ambos tienen un
 *   `groupId` no vacío y **idéntico**.
 * - groupId ausente, vacío o distinto ⇒ desconocido ⇒ perfil redactado.
 *
 * Función pura: no consulta red, malla ni almacenamiento; decide únicamente a
 * partir de los valores ya validados que le entrega `LiveMapController`.
 */
object PeerProfilePrivacy {

    fun canShowRealProfile(
        isSelf: Boolean,
        peerGroupId: String?,
        localGroupId: String?
    ): Boolean {
        if (isSelf) return true
        val peer = peerGroupId?.trim().takeUnless { it.isNullOrBlank() } ?: return false
        val local = localGroupId?.trim().takeUnless { it.isNullOrBlank() } ?: return false
        return peer == local
    }

    fun visibleName(shortId: UShort, realName: String, showRealProfile: Boolean): String =
        if (showRealProfile) realName else "Piloto #${shortId.toInt()}"

    fun visibleAbbreviation(
        shortId: UShort,
        realAbbreviation: String,
        showRealProfile: Boolean
    ): String = if (showRealProfile) realAbbreviation else "Piloto #${shortId.toInt()}"
}
