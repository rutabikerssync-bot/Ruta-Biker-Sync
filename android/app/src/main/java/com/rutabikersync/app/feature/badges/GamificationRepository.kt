package com.rutabikersync.app.feature.badges

import android.content.Context
import com.rutabikersync.app.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Repositorio del motor local de gamificación y progresión de Ruta Biker Sync (Fase 6).
 * Almacena métricas agregadas en SharedPreferences (RULES.md R8, GAMIFICATION.md).
 */
class GamificationRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("ruta_biker_gamification", Context.MODE_PRIVATE)

    private val _profileFlow = MutableStateFlow(loadProfile())
    val profileFlow: StateFlow<GamificationProfile> = _profileFlow.asStateFlow()

    init {
        // Inicializar con valores predeterminados de demostración si es primera ejecución
        if (!prefs.contains(KEY_INITIALIZED)) {
            seedDefaultInitialData()
        }
        refreshProfile()
    }

    private fun seedDefaultInitialData() {
        prefs.edit()
            .putBoolean(KEY_INITIALIZED, true)
            .putFloat(KEY_TOTAL_KM, 1250f)
            .putInt(KEY_XP, 320) // Nivel Rider
            .putInt(KEY_VALID_RIDES, 12)
            .putInt(KEY_STREAK_MONTHS, 4)
            .putString(KEY_LAST_RIDE_MONTH, currentMonthString())
            .putStringSet(
                KEY_UNLOCKED_BADGES, setOf(
                    "primeros_pasos",
                    "rodador",
                    "caminante",
                    "viajero",
                    "explorador",
                    "primera_rodada",
                    "rodada_nocturna",
                    "sin_cobertura",
                    "racha_3"
                )
            )
            .putStringSet(
                KEY_HONOR_BADGES, setOf(
                    "honor_hermandad|Hermandad del Asfalto|Águilas del Valle MC|1698624000000",
                    "honor_invitado_oro|Rider Invitado Distinguido|Centauros del Norte RC|1696032000000"
                )
            )
            .apply()
    }

    private fun currentMonthString(): String {
        return SimpleDateFormat("yyyy-MM", Locale.US).format(Date())
    }

    private fun isConsecutiveMonth(prevMonth: String, currentMonth: String): Boolean {
        if (prevMonth.isBlank()) return true
        return try {
            val format = SimpleDateFormat("yyyy-MM", Locale.US)
            val dPrev = format.parse(prevMonth) ?: return false
            val dCurr = format.parse(currentMonth) ?: return false

            val calPrev = java.util.Calendar.getInstance().apply { time = dPrev }
            val calCurr = java.util.Calendar.getInstance().apply { time = dCurr }

            val yearDiff = calCurr.get(java.util.Calendar.YEAR) - calPrev.get(java.util.Calendar.YEAR)
            val monthDiff = calCurr.get(java.util.Calendar.MONTH) - calPrev.get(java.util.Calendar.MONTH)
            (yearDiff * 12 + monthDiff) == 1
        } catch (_: Exception) {
            false
        }
    }

    fun refreshProfile() {
        _profileFlow.value = loadProfile()
    }

    private fun loadProfile(): GamificationProfile {
        val totalKm = prefs.getFloat(KEY_TOTAL_KM, 0f).toDouble()
        val xp = prefs.getInt(KEY_XP, 0)
        val validRides = prefs.getInt(KEY_VALID_RIDES, 0)
        val streakMonths = prefs.getInt(KEY_STREAK_MONTHS, 0)
        val unlockedCodes = prefs.getStringSet(KEY_UNLOCKED_BADGES, emptySet()) ?: emptySet()
        val honorRawSet = prefs.getStringSet(KEY_HONOR_BADGES, emptySet()) ?: emptySet()

        val rank = BikerRank.fromXp(xp)
        val nextRank = BikerRank.nextRank(rank)

        val xpInTier: Int
        val xpRequiredForNext: Int
        val xpPercent: Float

        if (nextRank != null) {
            xpInTier = xp - rank.minXp
            xpRequiredForNext = nextRank.minXp - rank.minXp
            xpPercent = (xpInTier.toFloat() / xpRequiredForNext.toFloat()).coerceIn(0f, 1f)
        } else {
            xpInTier = xp - rank.minXp
            xpRequiredForNext = rank.minXp
            xpPercent = 1.0f
        }

        val (nextKmName, nextKmTarget, nextKmRemaining) = GamificationCalculator.getNextMileageMilestone(totalKm)
        val kmPercent = if (nextKmTarget > 0) {
            (totalKm / nextKmTarget).toFloat().coerceIn(0f, 1f)
        } else 1.0f

        val crossClubHonorBadges = honorRawSet.mapNotNull { raw ->
            val parts = raw.split("|")
            if (parts.size >= 4) {
                BadgeItem(
                    id = parts[0],
                    code = parts[0],
                    name = parts[1],
                    description = "Insignia de honor cruzable conferida por ${parts[2]} tras compartir rodada.",
                    family = BadgeFamily.HONOR,
                    iconRes = R.drawable.ic_rank_honor,
                    unlocked = true,
                    unlockedAtMillis = parts[3].toLongOrNull(),
                    issuerName = parts[2]
                )
            } else null
        }

        val allBadges = buildAllBadgesCatalog(totalKm, xp, validRides, streakMonths, unlockedCodes)

        return GamificationProfile(
            rank = rank,
            xp = xp,
            nextRank = nextRank,
            xpInCurrentTier = xpInTier,
            xpRequiredForNextTier = xpRequiredForNext,
            xpProgressPercent = xpPercent,
            totalKm = totalKm,
            validRidesCount = validRides,
            streakMonths = streakMonths,
            nextKmBadgeName = nextKmName,
            nextKmTarget = nextKmTarget,
            nextKmRemaining = nextKmRemaining,
            nextKmProgressPercent = kmPercent,
            badges = allBadges,
            crossClubHonorBadges = crossClubHonorBadges
        )
    }

    private fun buildAllBadgesCatalog(
        totalKm: Double,
        xp: Int,
        validRides: Int,
        streakMonths: Int,
        unlockedCodes: Set<String>
    ): List<BadgeItem> {
        val list = mutableListOf<BadgeItem>()

        // 1. RANGOS
        for (r in BikerRank.values()) {
            val isUnlocked = xp >= r.minXp || unlockedCodes.contains(r.name.lowercase())
            list.add(
                BadgeItem(
                    id = "rank_${r.name.lowercase()}",
                    code = r.name.lowercase(),
                    name = r.title,
                    description = "Rango oficial alcanzado al acumular ${r.minXp} XP de trayectoria.",
                    family = BadgeFamily.RANGO,
                    iconRes = r.badgeRes,
                    unlocked = isUnlocked,
                    currentProgress = xp.toDouble(),
                    maxProgress = r.minXp.toDouble(),
                    unit = "XP"
                )
            )
        }

        // 2. KILOMETRAJE (10 km a 100.000 km)
        val kmBadges = listOf(
            Triple("primeros_pasos", "Primeros Pasos", 10.0) to R.drawable.ic_achieve_primera_rodada,
            Triple("rodador", "Rodador", 50.0) to R.drawable.ic_achieve_primera_rodada,
            Triple("caminante", "Caminante", 100.0) to R.drawable.ic_achieve_primera_rodada,
            Triple("viajero", "Viajero", 500.0) to R.drawable.ic_achieve_500km,
            Triple("explorador", "Explorador", 1000.0) to R.drawable.ic_achieve_explorador,
            Triple("aventurero", "Aventurero", 5000.0) to R.drawable.ic_achieve_aventurero,
            Triple("trotamundos", "Trotamundos", 10000.0) to R.drawable.ic_achieve_trotamundos,
            Triple("cartografo", "Cartógrafo", 25000.0) to R.drawable.ic_achieve_cartografo,
            Triple("nomada", "Nómada", 50000.0) to R.drawable.ic_achieve_trotamundos,
            Triple("legendario", "Legendario", 100000.0) to R.drawable.ic_achieve_legendario
        )

        for ((meta, iconRes) in kmBadges) {
            val (code, name, kmTarget) = meta
            val isUnlocked = totalKm >= kmTarget || unlockedCodes.contains(code)
            list.add(
                BadgeItem(
                    id = "km_$code",
                    code = code,
                    name = name,
                    description = "Recorrer $kmTarget km acumulados en rodadas oficiales verificadas.",
                    family = BadgeFamily.KILOMETRAJE,
                    iconRes = iconRes,
                    unlocked = isUnlocked,
                    currentProgress = totalKm.coerceAtMost(kmTarget),
                    maxProgress = kmTarget,
                    unit = "km"
                )
            )
        }

        // 3. RACHA (3, 6, 12, 24, 36 meses)
        val streakBadges = listOf(
            Triple("racha_3", "Racha de 3", 3) to R.drawable.ic_founder_flame,
            Triple("racha_6", "Racha de 6", 6) to R.drawable.ic_founder_flame,
            Triple("racha_12", "Racha de 12", 12) to R.drawable.ic_founder_flame,
            Triple("imparable", "Imparable (24)", 24) to R.drawable.ic_founder_flame,
            Triple("eterno", "Eterno (36)", 36) to R.drawable.ic_founder_flame
        )

        for ((meta, iconRes) in streakBadges) {
            val (code, name, targetMonths) = meta
            val isUnlocked = streakMonths >= targetMonths || unlockedCodes.contains(code)
            list.add(
                BadgeItem(
                    id = "streak_$code",
                    code = code,
                    name = name,
                    description = "Rodar al menos 1 vez por mes durante $targetMonths meses consecutivos sin falta.",
                    family = BadgeFamily.RACHA,
                    iconRes = iconRes,
                    unlocked = isUnlocked,
                    currentProgress = streakMonths.coerceAtMost(targetMonths).toDouble(),
                    maxProgress = targetMonths.toDouble(),
                    unit = "meses"
                )
            )
        }

        // 4. EXPERIENCIA
        val expBadges = listOf(
            BadgeItem("exp_1st_ride", "primera_rodada", "Primera Rodada", "1ª rodada oficial completada en grupo.", BadgeFamily.EXPERIENCIA, R.drawable.ic_achieve_primera_rodada, validRides >= 1 || unlockedCodes.contains("primera_rodada")),
            BadgeItem("exp_nocturna", "rodada_nocturna", "Rodada Nocturna", "Rodada finalizada después de las 22:00 o iniciada de noche.", BadgeFamily.EXPERIENCIA, R.drawable.ic_achieve_rodada_nocturna, unlockedCodes.contains("rodada_nocturna")),
            BadgeItem("exp_lluvia", "bajo_lluvia", "Bajo la Lluvia", "Rodada con condiciones climáticas de lluvia intensa verificadas.", BadgeFamily.EXPERIENCIA, R.drawable.ic_achieve_aventurero, unlockedCodes.contains("bajo_lluvia")),
            BadgeItem("exp_mesh", "sin_cobertura", "Sin Cobertura", "Mesh activa por >30 minutos sin red celular en ruta inhóspita.", BadgeFamily.EXPERIENCIA, R.drawable.ic_achieve_sin_cobertura, unlockedCodes.contains("sin_cobertura")),
            BadgeItem("exp_montana", "montana", "Rodada de Montaña", "Rodada con más de 500 m de desnivel positivo acumulado.", BadgeFamily.EXPERIENCIA, R.drawable.ic_achieve_explorador, unlockedCodes.contains("montana")),
            BadgeItem("exp_3_grupos", "tres_grupos", "Hermandad", "Rodar con 3 grupos distintos reconocidos en la comunidad.", BadgeFamily.EXPERIENCIA, R.drawable.ic_achieve_rodada_3_grupos, unlockedCodes.contains("tres_grupos")),
            BadgeItem("exp_mecanico", "mecanico_ruta", "Mecánico de Ruta", "Auxilio técnico y mecánico a un compañero averiado.", BadgeFamily.EXPERIENCIA, R.drawable.ic_achieve_mecanico_ruta, unlockedCodes.contains("mecanico_ruta")),
            BadgeItem("exp_sos", "primer_sos", "Primer SOS", "Responder y acudir a un llamado de auxilio vial de emergencia.", BadgeFamily.EXPERIENCIA, R.drawable.ic_achieve_primer_sos, unlockedCodes.contains("primer_sos")),
            BadgeItem("exp_piloto_protegido", "piloto_protegido", "Piloto Protegido", "Bóveda biométrica y perfil clínico configurados.", BadgeFamily.EXPERIENCIA, R.drawable.ic_rank_veterano, unlockedCodes.contains("piloto_protegido")),
            BadgeItem("exp_convoy_seguro", "convoy_seguro", "Convoy Seguro", "Ficha médica cifrada compartida por mesh durante rodada.", BadgeFamily.EXPERIENCIA, R.drawable.ic_rank_elite, unlockedCodes.contains("convoy_seguro")),
            BadgeItem("exp_sweep", "sweep_honor", "Sweep de Honor", "Cerrar el grupo como barredor oficial (5 veces).", BadgeFamily.EXPERIENCIA, R.drawable.ic_achieve_sweep_honor, unlockedCodes.contains("sweep_honor")),
            BadgeItem("exp_capitan", "capitan_rodada", "Capitán de Rodada", "Comandar el convoy como capitán de ruta (10 veces).", BadgeFamily.EXPERIENCIA, R.drawable.ic_officer_capitan_ruta, unlockedCodes.contains("capitan_rodada")),
            BadgeItem("exp_scout", "scout_confiable", "Scout Confiable", "Explorar y asegurar cruces peligrosos (15 veces).", BadgeFamily.EXPERIENCIA, R.drawable.ic_road_marshal_cone, unlockedCodes.contains("scout_confiable"))
        )
        list.addAll(expBadges)

        // 5. DIRECTIVA DE CLUB
        val clubBadges = listOf(
            BadgeItem("officer_pres", "presidente", "Presidente", "Líder supremo y representante legal del club.", BadgeFamily.CLUB, R.drawable.ic_officer_presidente, unlockedCodes.contains("presidente")),
            BadgeItem("officer_vp", "vicepresidente", "Vicepresidente", "Segundo al mando y coordinador del club.", BadgeFamily.CLUB, R.drawable.ic_officer_vicepresidente, unlockedCodes.contains("vicepresidente")),
            BadgeItem("officer_sgt", "sargento_armas", "Sargento de Armas", "Custodio del orden, disciplina y seguridad.", BadgeFamily.CLUB, R.drawable.ic_officer_sargento_armas, unlockedCodes.contains("sargento_armas")),
            BadgeItem("officer_rc", "road_captain", "Capitán de Ruta", "Comandante de convoy, trayectorias y paradas.", BadgeFamily.CLUB, R.drawable.ic_officer_capitan_ruta, unlockedCodes.contains("road_captain")),
            BadgeItem("officer_sec", "secretario", "Secretario", "Administrador de actas, convocatorias y registros.", BadgeFamily.CLUB, R.drawable.ic_officer_secretario, unlockedCodes.contains("secretario")),
            BadgeItem("officer_treas", "tesorero", "Tesorero", "Custodio financiero y semáforo de cuotas del club.", BadgeFamily.CLUB, R.drawable.ic_officer_tesorero, unlockedCodes.contains("tesorero"))
        )
        list.addAll(clubBadges)

        return list
    }

    /**
     * Registra el cierre de una rodada válida y recalcula XP, kilometraje, racha e insignias.
     */
    fun recordCompletedRide(
        distanceKm: Double,
        elevationGainM: Double = 0.0,
        startedAtNight: Boolean = false,
        rained: Boolean = false,
        offlineMinutes: Int = 0,
        role: String = "rider",
        answeredSos: Boolean = false
    ): RideResultGamification {
        val currentKm = prefs.getFloat(KEY_TOTAL_KM, 0f).toDouble()
        val currentXp = prefs.getInt(KEY_XP, 0)
        val validRides = prefs.getInt(KEY_VALID_RIDES, 0) + 1
        val lastMonth = prefs.getString(KEY_LAST_RIDE_MONTH, "") ?: ""
        var streak = prefs.getInt(KEY_STREAK_MONTHS, 0)
        val nowMonth = currentMonthString()

        val unlockedCodes = (prefs.getStringSet(KEY_UNLOCKED_BADGES, emptySet()) ?: emptySet()).toMutableSet()
        val newlyUnlocked = mutableListOf<BadgeItem>()

        // 1. Racha mensual
        var streakUpdated = false
        var streak3Unlocked = false
        if (nowMonth != lastMonth) {
            if (isConsecutiveMonth(lastMonth, nowMonth)) {
                streak += 1
            } else {
                streak = 1 // Racha rota, reinicia a 1
            }
            streakUpdated = true
            if (streak == 3) {
                streak3Unlocked = true
                if (unlockedCodes.add("racha_3")) {
                    newlyUnlocked.add(BadgeItem("streak_racha_3", "racha_3", "Racha de 3", "3 meses consecutivos rodando", BadgeFamily.RACHA, R.drawable.ic_founder_flame, true))
                }
            }
            if (streak == 6 && unlockedCodes.add("racha_6")) {
                newlyUnlocked.add(BadgeItem("streak_racha_6", "racha_6", "Racha de 6", "6 meses consecutivos rodando", BadgeFamily.RACHA, R.drawable.ic_founder_flame, true))
            }
            if (streak == 12 && unlockedCodes.add("racha_12")) {
                newlyUnlocked.add(BadgeItem("streak_racha_12", "racha_12", "Racha de 12", "12 meses consecutivos rodando", BadgeFamily.RACHA, R.drawable.ic_founder_flame, true))
            }
        }

        // 2. XP ganado
        val xpGained = GamificationCalculator.calculateRideXp(
            distanceKm = distanceKm,
            role = role,
            answeredSos = answeredSos,
            streak3MonthsUnlocked = streak3Unlocked
        )
        val newXp = currentXp + xpGained
        val newTotalKm = currentKm + distanceKm

        // 3. Evaluar insignias de kilometraje
        val kmThresholds = listOf(
            "primeros_pasos" to 10.0,
            "rodador" to 50.0,
            "caminante" to 100.0,
            "viajero" to 500.0,
            "explorador" to 1000.0,
            "aventurero" to 5000.0,
            "trotamundos" to 10000.0,
            "cartografo" to 25000.0,
            "nomada" to 50000.0,
            "legendario" to 100000.0
        )
        for ((code, target) in kmThresholds) {
            if (newTotalKm >= target && unlockedCodes.add(code)) {
                val displayName = code.replace("_", " ").replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                newlyUnlocked.add(BadgeItem("km_$code", code, displayName, "$target km recorridos", BadgeFamily.KILOMETRAJE, R.drawable.ic_achieve_aventurero, true))
            }
        }

        // 4. Evaluar insignias de experiencia
        if (validRides >= 1 && unlockedCodes.add("primera_rodada")) {
            newlyUnlocked.add(BadgeItem("exp_1st_ride", "primera_rodada", "Primera Rodada", "1ª rodada oficial completada", BadgeFamily.EXPERIENCIA, R.drawable.ic_achieve_primera_rodada, true))
        }
        if (startedAtNight && unlockedCodes.add("rodada_nocturna")) {
            newlyUnlocked.add(BadgeItem("exp_nocturna", "rodada_nocturna", "Rodada Nocturna", "Finalizada en noche", BadgeFamily.EXPERIENCIA, R.drawable.ic_achieve_rodada_nocturna, true))
        }
        if (rained && unlockedCodes.add("bajo_lluvia")) {
            newlyUnlocked.add(BadgeItem("exp_lluvia", "bajo_lluvia", "Bajo la Lluvia", "Rodada con lluvia", BadgeFamily.EXPERIENCIA, R.drawable.ic_achieve_aventurero, true))
        }
        if (offlineMinutes >= 30 && unlockedCodes.add("sin_cobertura")) {
            newlyUnlocked.add(BadgeItem("exp_mesh", "sin_cobertura", "Sin Cobertura", "Mesh >30 min sin internet", BadgeFamily.EXPERIENCIA, R.drawable.ic_achieve_sin_cobertura, true))
        }
        if (elevationGainM >= 500 && unlockedCodes.add("montana")) {
            newlyUnlocked.add(BadgeItem("exp_montana", "montana", "Rodada de Montaña", ">500 m de desnivel acumulado", BadgeFamily.EXPERIENCIA, R.drawable.ic_achieve_explorador, true))
        }
        if (answeredSos && unlockedCodes.add("primer_sos")) {
            newlyUnlocked.add(BadgeItem("exp_sos", "primer_sos", "Primer SOS", "Acudir a auxilio vial", BadgeFamily.EXPERIENCIA, R.drawable.ic_achieve_primer_sos, true))
        }

        // Guardar cambios en SharedPreferences
        prefs.edit()
            .putFloat(KEY_TOTAL_KM, newTotalKm.toFloat())
            .putInt(KEY_XP, newXp)
            .putInt(KEY_VALID_RIDES, validRides)
            .putInt(KEY_STREAK_MONTHS, streak)
            .putString(KEY_LAST_RIDE_MONTH, nowMonth)
            .putStringSet(KEY_UNLOCKED_BADGES, unlockedCodes)
            .apply()

        refreshProfile()

        return RideResultGamification(
            xpGained = xpGained,
            distanceKm = distanceKm,
            previousTotalKm = currentKm,
            newTotalKm = newTotalKm,
            newlyUnlockedBadges = newlyUnlocked,
            streakUpdated = streakUpdated,
            currentStreakMonths = streak
        )
    }

    /**
     * Otorga una insignia de honor cruzable de otro club.
     */
    fun awardCrossClubHonorBadge(code: String, title: String, issuerClub: String) {
        val currentHonor = (prefs.getStringSet(KEY_HONOR_BADGES, emptySet()) ?: emptySet()).toMutableSet()
        val entry = "$code|$title|$issuerClub|${System.currentTimeMillis()}"
        currentHonor.add(entry)

        // Bonificación de XP por insignia de honor (+15 XP)
        val currentXp = prefs.getInt(KEY_XP, 0) + 15

        prefs.edit()
            .putStringSet(KEY_HONOR_BADGES, currentHonor)
            .putInt(KEY_XP, currentXp)
            .apply()

        refreshProfile()
    }

    /**
     * Retorna el rango actual del motociclista.
     */
    fun getCurrentRank(userId: String = ""): BikerRank {
        val xp = prefs.getInt(KEY_XP, 0)
        return BikerRank.fromXp(xp)
    }

    /**
     * Retorna el próximo rango a alcanzar a partir de un valor de XP.
     */
    fun getNextRank(currentXp: Int): BikerRank? {
        val rank = BikerRank.fromXp(currentXp)
        return BikerRank.nextRank(rank)
    }

    /**
     * Otorga una insignia directamente al usuario.
     */
    fun awardBadge(badgeCode: String): Boolean {
        val unlockedCodes = (prefs.getStringSet(KEY_UNLOCKED_BADGES, emptySet()) ?: emptySet()).toMutableSet()
        val added = unlockedCodes.add(badgeCode)
        if (added) {
            prefs.edit().putStringSet(KEY_UNLOCKED_BADGES, unlockedCodes).apply()
            refreshProfile()
        }
        return added
    }

    /**
     * Retorna el catálogo completo de insignias con su estado actual de desbloqueo.
     */
    fun getBadges(): List<BadgeItem> {
        return profileFlow.value.badges
    }

    /**
     * Calcula la ganancia de XP de una rodada con soporte de bandera de auditoría.
     */
    fun calculateRideXp(
        summary: com.rutabikersync.app.core.gamification.RideSummary,
        isAudited: Boolean = false
    ): Int {
        return GamificationCalculator.calculateRideXp(summary, isAudited)
    }

    /**
     * Procesa un [RideSummary], aplicando validaciones anti-cheat.
     * Si la rodada es válida, acumula XP, kilometraje y desbloquea insignias ganadas.
     * Si la rodada es inválida, retorna `null` y no se acredita puntuación alguna.
     */
    fun processRideSummary(
        summary: com.rutabikersync.app.core.gamification.RideSummary,
        validator: com.rutabikersync.app.core.gamification.AntiCheatValidator = com.rutabikersync.app.core.gamification.AntiCheatValidator()
    ): RideResultGamification? {
        val validation = validator.validateRideSummary(summary)
        if (!validation.isValid) {
            return null
        }

        val xpGained = calculateRideXp(summary, isAudited = validation.isAudited)
        val currentKm = prefs.getFloat(KEY_TOTAL_KM, 0f).toDouble()
        val currentXp = prefs.getInt(KEY_XP, 0)
        val newTotalKm = currentKm + summary.meshKm
        val newXp = currentXp + xpGained
        val validRides = prefs.getInt(KEY_VALID_RIDES, 0) + 1

        val unlockedCodes = (prefs.getStringSet(KEY_UNLOCKED_BADGES, emptySet()) ?: emptySet()).toMutableSet()
        val newlyUnlocked = mutableListOf<BadgeItem>()

        val candidateBadges = GamificationCalculator.evaluateRideBadges(summary, isAudited = validation.isAudited)
        for (b in candidateBadges) {
            if (unlockedCodes.add(b)) {
                newlyUnlocked.add(
                    BadgeItem(
                        id = "badge_$b",
                        code = b,
                        name = b.replace('_', ' ').replaceFirstChar { it.uppercase() },
                        description = "Desbloqueada en rodada oficial de convoy",
                        family = BadgeFamily.EXPERIENCIA,
                        iconRes = R.drawable.ic_achieve_primera_rodada,
                        unlocked = true
                    )
                )
            }
        }

        prefs.edit()
            .putFloat(KEY_TOTAL_KM, newTotalKm.toFloat())
            .putInt(KEY_XP, newXp)
            .putInt(KEY_VALID_RIDES, validRides)
            .putStringSet(KEY_UNLOCKED_BADGES, unlockedCodes)
            .apply()

        refreshProfile()

        return RideResultGamification(
            xpGained = xpGained,
            distanceKm = summary.meshKm,
            previousTotalKm = currentKm,
            newTotalKm = newTotalKm,
            newlyUnlockedBadges = newlyUnlocked,
            streakUpdated = false,
            currentStreakMonths = prefs.getInt(KEY_STREAK_MONTHS, 0)
        )
    }

    companion object {
        private const val KEY_INITIALIZED = "initialized"
        private const val KEY_TOTAL_KM = "total_km"
        private const val KEY_XP = "xp"
        private const val KEY_VALID_RIDES = "valid_rides"
        private const val KEY_STREAK_MONTHS = "streak_months"
        private const val KEY_LAST_RIDE_MONTH = "last_ride_month"
        private const val KEY_UNLOCKED_BADGES = "unlocked_badges"
        private const val KEY_HONOR_BADGES = "honor_badges"
    }
}
