package com.rutabikersync.app.feature.livemap

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Proyección Segura del roster enriquecido devuelto por
 * `GET /v1/rides/:id/participants`. El backend NO conoce el `shortId` de malla:
 * solo se puede asociar un participante con el peer local mediante su `userId`.
 * Los peers remotos conservan el fallback honrado "Piloto #X".
 */
data class RideRosterUiEntry(
    val userId: String,
    val displayName: String,
    val avatarUrl: String?,
    val roleInConvoy: String,
    val mapColorHex: Long?
)

/** Parsea `#RRGGBB` (o `RRGGBB`) a `0xFFRRGGBB`; devuelve null si no es válido. */
fun parseHexColor(value: String?): Long? {
    val clean = value?.trim()?.removePrefix("#") ?: return null
    if (clean.length != 6) return null
    val rgb = clean.toLongOrNull(16) ?: return null
    return 0xFF000000L or rgb
}

fun parseRideRosterUi(root: JsonElement): List<RideRosterUiEntry> {
    val array = runCatching { root.jsonObject["participants"]?.jsonArray }.getOrNull()
        ?: return emptyList()
    return array.mapNotNull { element ->
        val obj = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
        fun field(key: String): String? =
            runCatching { obj[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

        val userId = field("userId")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val name = field("displayName")?.trim().takeUnless { it.isNullOrBlank() }
            ?: "Desconocido"
        val role = field("roleInConvoy")?.trim().takeUnless { it.isNullOrBlank() }
            ?: "Desconocido"
        RideRosterUiEntry(
            userId = userId,
            displayName = name,
            avatarUrl = field("avatarUrl")?.takeIf { it.isNotBlank() },
            roleInConvoy = role,
            mapColorHex = parseHexColor(field("mapColor"))
        )
    }
}
