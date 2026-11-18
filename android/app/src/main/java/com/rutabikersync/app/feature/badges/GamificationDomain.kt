package com.rutabikersync.app.feature.badges

import androidx.compose.ui.graphics.Color
import com.rutabikersync.app.R

/**
 * GAMIFICATION.md - Dominio del Sistema de Rangos, Logros y Kilometraje.
 * Define rangos, familias de insignias, niveles de XP y reglas de cálculo.
 */

enum class BikerRank(
    val title: String,
    val minXp: Int,
    val badgeRes: Int,
    val accentColor: Color
) {
    NOVATO("Novato", 0, R.drawable.ic_rank_novato, Color(0xFF94A3B8)),
    RIDER("Rider", 100, R.drawable.ic_rank_rider, Color(0xFF38BDF8)),
    VETERANO("Veterano", 500, R.drawable.ic_rank_veterano, Color(0xFFF59E0B)),
    ELITE("Élite", 2000, R.drawable.ic_rank_elite, Color(0xFF8B5CF6)),
    LEYENDA("Leyenda", 5000, R.drawable.ic_rank_leyenda, Color(0xFFE5B869));

    companion object {
        fun fromXp(xp: Int): BikerRank {
            return when {
                xp >= LEYENDA.minXp -> LEYENDA
                xp >= ELITE.minXp -> ELITE
                xp >= VETERANO.minXp -> VETERANO
                xp >= RIDER.minXp -> RIDER
                else -> NOVATO
            }
        }

        fun nextRank(current: BikerRank): BikerRank? {
            return when (current) {
                NOVATO -> RIDER
                RIDER -> VETERANO
                VETERANO -> ELITE
                ELITE -> LEYENDA
                LEYENDA -> null
            }
        }
    }
}

enum class BadgeFamily(val label: String) {
    TODAS("Todas"),
    RANGO("Rangos"),
    KILOMETRAJE("Kilometraje"),
    RACHA("Racha"),
    EXPERIENCIA("Experiencia"),
    HONOR("Honor"),
    CLUB("Directiva")
}

data class BadgeItem(
    val id: String,
    val code: String,
    val name: String,
    val description: String,
    val family: BadgeFamily,
    val iconRes: Int,
    val unlocked: Boolean,
    val unlockedAtMillis: Long? = null,
    val issuerName: String? = null,
    val currentProgress: Double = 0.0,
    val maxProgress: Double = 0.0,
    val unit: String = ""
)

data class GamificationProfile(
    val rank: BikerRank,
    val xp: Int,
    val nextRank: BikerRank?,
    val xpInCurrentTier: Int,
    val xpRequiredForNextTier: Int,
    val xpProgressPercent: Float,
    val totalKm: Double,
    val validRidesCount: Int,
    val streakMonths: Int,
    val nextKmBadgeName: String,
    val nextKmTarget: Double,
    val nextKmRemaining: Double,
    val nextKmProgressPercent: Float,
    val badges: List<BadgeItem>,
    val crossClubHonorBadges: List<BadgeItem>
)

data class RideResultGamification(
    val xpGained: Int,
    val distanceKm: Double,
    val previousTotalKm: Double,
    val newTotalKm: Double,
    val newlyUnlockedBadges: List<BadgeItem>,
    val streakUpdated: Boolean,
    val currentStreakMonths: Int
)

object GamificationCalculator {

    /**
     * Calcula la ganancia de XP de una rodada cerrada (GAMIFICATION.md §7).
     * - Base: 10 XP
     * - +1 XP por cada 50 km recorridos
     * - Rol convoy: Capitán +5, Sweep +3, Scout +2
     * - Auxilio vial: Responder a SOS +10 XP
     * - Racha: Mes 3 alcanzado +20 XP
     * - Honor: Insignia de honor recibida +15 XP
     */
    fun calculateRideXp(
        distanceKm: Double,
        role: String,
        answeredSos: Boolean = false,
        streak3MonthsUnlocked: Boolean = false,
        honorBadgeEarned: Boolean = false
    ): Int {
        var xp = 10 // Base
        xp += (distanceKm / 50.0).toInt() // +1 cada 50 km

        when (role.trim().lowercase()) {
            "capitan", "capitán", "captain", "road_captain" -> xp += 5
            "sweep", "barredor", "tail" -> xp += 3
            "scout", "guia", "guía" -> xp += 2
        }

        if (answeredSos) xp += 10
        if (streak3MonthsUnlocked) xp += 20
        if (honorBadgeEarned) xp += 15

        return xp
    }

    /**
     * Calcula la ganancia de XP de una rodada validada con reglas anti-cheat (Fase 6 · Hito 5 · Etapa 4).
     * - Si está bajo auditoría ([isAudited]): Solo otorga la puntuación base (10 XP).
     * - Base: 10 XP
     * - Kilometraje Mesh: +1 XP por cada 50 km de malla
     * - Retransmisiones: +1 XP por cada 10 relays
     * - Auxilio vial: +10 XP por cada asistencia SOS
     * - Racha: +20 XP si la racha mensual alcanza múltiplo de 3 meses
     */
    fun calculateRideXp(
        summary: com.rutabikersync.app.core.gamification.RideSummary,
        isAudited: Boolean = false,
        streak3MonthsUnlocked: Boolean = false
    ): Int {
        val base = 10
        if (isAudited) {
            return base // Solo puntuación base si está bajo auditoría por discrepancia de testigos
        }

        var xp = base
        xp += (summary.meshKm / 50.0).toInt() // +1 cada 50 km de mesh
        xp += summary.relayCount / 10         // +1 cada 10 relays
        xp += summary.sosAssistCount * 10     // +10 por cada auxilio SOS

        when (summary.role.trim().lowercase()) {
            "capitan", "capitán", "captain", "road_captain" -> xp += 5
            "sweep", "barredor", "tail" -> xp += 3
            "scout", "guia", "guía" -> xp += 2
        }

        if (streak3MonthsUnlocked) xp += 20
        return xp
    }

    /**
     * Evalúa las insignias de experiencia y especiales desbloqueadas en esta rodada.
     */
    fun evaluateRideBadges(
        summary: com.rutabikersync.app.core.gamification.RideSummary,
        isAudited: Boolean = false
    ): List<String> {
        val badges = mutableListOf<String>()
        if (isAudited) return badges

        badges.add("primera_rodada")
        if (summary.offlineMinutes >= 30) {
            badges.add("sin_cobertura")
        }
        if (summary.vaultCompleted) {
            badges.add("piloto_protegido")
        }
        if (summary.medicalSheetShared) {
            badges.add("convoy_seguro")
        }
        if (summary.sosAssistCount > 0) {
            badges.add("primer_sos")
        }
        return badges
    }

    /**
     * Retorna el próximo hito de kilometraje según los km acumulados (GAMIFICATION.md §3).
     */
    fun getNextMileageMilestone(totalKm: Double): Triple<String, Double, Double> {
        val milestones = listOf(
            "Primeros Pasos" to 10.0,
            "Rodador" to 50.0,
            "Caminante" to 100.0,
            "Viajero" to 500.0,
            "Explorador" to 1000.0,
            "Aventurero" to 5000.0,
            "Trotamundos" to 10000.0,
            "Cartógrafo" to 25000.0,
            "Nómada" to 50000.0,
            "Legendario" to 100000.0
        )

        for ((name, target) in milestones) {
            if (totalKm < target) {
                val remaining = target - totalKm
                return Triple(name, target, remaining)
            }
        }
        return Triple("Legendario", 100000.0, 0.0)
    }
}
