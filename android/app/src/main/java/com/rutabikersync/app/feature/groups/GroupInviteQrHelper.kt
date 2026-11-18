package com.rutabikersync.app.feature.groups

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Utilidad de generación, parsing y control de acceso para Códigos QR de Invitación a Grupos.
 *
 * Reglas de negocio:
 * 1. El QR contiene: ID de grupo, código de invitación de respaldo (ej. RUTA-TEST-01) y metadatos.
 * 2. Control de acceso estricto: El QR podrá ser compartido ÚNICAMENTE por el Presidente y Vicepresidente del grupo.
 * 3. Fallback: Si la cámara falla o no está disponible, se mantiene el código alfanumérico manual.
 */
object GroupInviteQrHelper {

    const val URI_SCHEME = "rbs"
    const val URI_HOST = "invite"

    enum class QrIntentType(
        val code: String,
        val label: String,
        val badgeText: String,
        val description: String
    ) {
        MEMBER(
            code = "member",
            label = "Admisión de Miembro",
            badgeText = "👤 Miembro",
            description = "Para que aspirantes y pilotos se unan a la membresía de tu club."
        ),
        BROTHERHOOD(
            code = "brotherhood",
            label = "Hermandad Biker Oficial",
            badgeText = "🤝 Hermandad",
            description = "Para pactar respeto, apoyo mutuo y alianza biker con otro club."
        ),
        SPONSORSHIP(
            code = "sponsorship",
            label = "Apadrinamiento Biker",
            badgeText = "⭐ Apadrinar",
            description = "Para actuar como Club Padrino de un club hermano o recibir apadrinamiento."
        ),
        RIDE_INVITE(
            code = "ride",
            label = "Invitación a Rodada Oficial",
            badgeText = "🏍️ Rodada",
            description = "Para invitar a clubes hermanos y pilotos invitados a rodar juntos (100% libre y gratuita)."
        )
    }

    data class InviteQrPayload(
        val groupId: String,
        val inviteCode: String,
        val groupName: String = "",
        val countryCode: String = "",
        val intentType: QrIntentType = QrIntentType.MEMBER,
        val rideId: String? = null,
        val presidentName: String = "",
        val founderId: String = ""
    )

    /**
     * Valida si el usuario actual tiene permisos para generar y compartir el QR del grupo.
     * Restricción estricta: Únicamente Presidente y Vicepresidente (o Líder principal fundador).
     */
    fun canShareQr(isLeader: Boolean, role: ClubRole?): Boolean {
        return (isLeader && role == null) ||
                role == ClubRole.PRESIDENT ||
                role == ClubRole.VICE_PRESIDENT
    }

    /**
     * Codifica los datos de invitación en un payload seguro para el QR.
     * Formato: rbs://invite?groupId=...&code=...&groupName=...&country=...&type=...
     */
    fun encodePayload(payload: InviteQrPayload): String {
        val enc = { s: String -> java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8.toString()) }
        var uri = "$URI_SCHEME://$URI_HOST?" +
                "groupId=${enc(payload.groupId)}" +
                "&code=${enc(payload.inviteCode)}" +
                "&groupName=${enc(payload.groupName)}" +
                "&country=${enc(payload.countryCode)}" +
                "&type=${enc(payload.intentType.code)}"
        if (!payload.rideId.isNullOrBlank()) {
            uri += "&rideId=${enc(payload.rideId)}"
        }
        if (payload.presidentName.isNotEmpty()) {
            uri += "&president=${enc(payload.presidentName)}"
        }
        if (payload.founderId.isNotEmpty()) {
            uri += "&founderId=${enc(payload.founderId)}"
        }
        return uri
    }

    /**
     * Parsea un payload crudo proveniente del escáner de cámara o de entrada manual.
     * Admite tanto el formato URI (rbs://invite?...) como el código simple de respaldo (ej. RUTA-TEST-01, HERM-01, PAD-01).
     */
    fun parsePayload(raw: String): InviteQrPayload? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        if (trimmed.startsWith("$URI_SCHEME://$URI_HOST?")) {
            val query = trimmed.substringAfter("$URI_SCHEME://$URI_HOST?")
            val params = query.split("&").mapNotNull { token ->
                val eqIndex = token.indexOf('=')
                if (eqIndex > 0) {
                    val key = token.substring(0, eqIndex)
                    val rawVal = token.substring(eqIndex + 1)
                    val value = runCatching {
                        java.net.URLDecoder.decode(rawVal, java.nio.charset.StandardCharsets.UTF_8.toString())
                    }.getOrDefault(rawVal)
                    key to value
                } else null
            }.toMap()

            val groupId = params["groupId"] ?: return null
            val code = params["code"] ?: return null
            val typeStr = params["type"] ?: "member"
            val type = QrIntentType.values().find { it.code.equals(typeStr, ignoreCase = true) } ?: QrIntentType.MEMBER

            return InviteQrPayload(
                groupId = groupId,
                inviteCode = code,
                groupName = params["groupName"] ?: "",
                countryCode = params["country"] ?: "",
                intentType = type,
                rideId = params["rideId"],
                presidentName = params["president"] ?: "",
                founderId = params["founderId"] ?: ""
            )
        }

        // Fallback: Si el usuario ingresa un código alfanumérico directo (ej. RUTA-TEST-01, HERM-HALCONES-01, etc.)
        if (trimmed.length >= 4) {
            val upper = trimmed.uppercase()
            val detectedType = when {
                upper.startsWith("HERM") -> QrIntentType.BROTHERHOOD
                upper.startsWith("PAD") -> QrIntentType.SPONSORSHIP
                upper.startsWith("ROD") || upper.startsWith("RIDE") -> QrIntentType.RIDE_INVITE
                else -> QrIntentType.MEMBER
            }
            return InviteQrPayload(
                groupId = "grp-active",
                inviteCode = trimmed,
                groupName = "Grupo Biker",
                countryCode = "MX",
                intentType = detectedType
            )
        }

        return null
    }

    /**
     * Genera un Bitmap Android con la matriz de píxeles del código QR usando ZXing.
     */
    fun generateQrBitmap(content: String, sizePx: Int = 512): Bitmap {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }
}
