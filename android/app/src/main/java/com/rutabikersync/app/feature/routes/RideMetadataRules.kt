package com.rutabikersync.app.feature.routes

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Metadatos normalizados de una rodada creada desde el planificador de mapa. */
data class RideMetadataInput(
    val title: String,
    val startsAt: Instant,
    val description: String?
)

/** Validación pura de nombre, fecha/hora y descripción de la rodada. */
object RideMetadataRules {
    const val MIN_TITLE_LENGTH = 3
    const val MAX_TITLE_LENGTH = 140
    const val MAX_DESCRIPTION_LENGTH = 2000

    fun normalizeDescription(description: String?): String? =
        description?.trim()?.takeIf { it.isNotEmpty() }

    fun validate(
        title: String,
        description: String?,
        localDateTime: LocalDateTime,
        zoneId: ZoneId = ZoneId.systemDefault(),
        now: Instant = Instant.now()
    ): Result<RideMetadataInput> {
        val normalizedTitle = title.trim()
        if (normalizedTitle.length < MIN_TITLE_LENGTH) {
            return Result.failure(IllegalArgumentException("El título debe tener al menos $MIN_TITLE_LENGTH caracteres"))
        }
        if (normalizedTitle.length > MAX_TITLE_LENGTH) {
            return Result.failure(IllegalArgumentException("El título no puede superar $MAX_TITLE_LENGTH caracteres"))
        }

        val normalizedDescription = normalizeDescription(description)
        if ((normalizedDescription?.length ?: 0) > MAX_DESCRIPTION_LENGTH) {
            return Result.failure(IllegalArgumentException("La descripción no puede superar $MAX_DESCRIPTION_LENGTH caracteres"))
        }

        val startsAt = localDateTime.atZone(zoneId).toInstant()
        if (!startsAt.isAfter(now)) {
            return Result.failure(IllegalArgumentException("La salida debe ser posterior a la fecha y hora actual"))
        }

        return Result.success(
            RideMetadataInput(
                title = normalizedTitle,
                startsAt = startsAt,
                description = normalizedDescription
            )
        )
    }
}
