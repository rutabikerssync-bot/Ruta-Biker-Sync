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

    data class InviteQrPayload(
        val groupId: String,
        val inviteCode: String,
        val groupName: String = "",
        val countryCode: String = ""
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
     * Formato: rbs://invite?groupId=...&code=...&groupName=...&country=...
     */
    fun encodePayload(payload: InviteQrPayload): String {
        val enc = { s: String -> java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8.toString()) }
        return "$URI_SCHEME://$URI_HOST?" +
                "groupId=${enc(payload.groupId)}" +
                "&code=${enc(payload.inviteCode)}" +
                "&groupName=${enc(payload.groupName)}" +
                "&country=${enc(payload.countryCode)}"
    }

    /**
     * Parsea un payload crudo proveniente del escáner de cámara o de entrada manual.
     * Admite tanto el formato URI (rbs://invite?...) como el código simple de respaldo (ej. RUTA-TEST-01).
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
            return InviteQrPayload(
                groupId = groupId,
                inviteCode = code,
                groupName = params["groupName"] ?: "",
                countryCode = params["country"] ?: ""
            )
        }

        // Fallback: Si el usuario ingresa un código alfanumérico directo (ej. RUTA-TEST-01)
        if (trimmed.length >= 4) {
            return InviteQrPayload(
                groupId = "grp-active",
                inviteCode = trimmed,
                groupName = "Grupo Biker",
                countryCode = "MX"
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
