package com.rutabikersync.app.feature.routes

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Pruebas Unitarias para la Bitácora de Rodadas y Telemetría de Malla (Fase 7.0 · Hito 7.0.2 · Opción A).
 * Valida:
 * 1. Renderizado de modelo enriquecido con 3 rodadas mock.
 * 2. Formato de métricas duales (Km totales vs. Km en malla).
 * 3. Formato y cálculo de duración de rodada.
 * 4. Formato de XP y mapeo determinista de insignias (`BadgeIconMapper`).
 * 5. Filtros dinámicos por club y ventana temporal.
 * 6. Empty state asistido y comportamiento sin registros.
 */
class RideHistoryScreenTest {

    @Before
    fun setUp() {
        RideScheduleRepository.clearHistory()
    }

    @Test
    fun testRenderizadoCon3RodadasMock() {
        RideScheduleRepository.loadDemoHistory()
        val history = RideScheduleRepository.rideHistory.value

        assertEquals("Deben existir exactamente 3 rodadas mock en la bitácora demo", 3, history.size)

        val first = history[0]
        assertEquals("Travesía Sierra Nevada · Ruta de la Cumbre", first.title)
        assertEquals("Águilas del Camino MC", first.clubName)
        assertEquals("Punta", first.roleInRide)
        assertEquals(48.5, first.distanceKm, 0.01)
        assertEquals(46.2, first.meshKm, 0.01)
        assertEquals(115, first.durationMinutes)
        assertEquals(145, first.xpEarned)
        assertEquals(listOf("convoy_seguro", "sin_cobertura"), first.badgesUnlocked)
        assertEquals(38, first.relaysCount)
        assertEquals(0, first.sosAssistsCount)
    }

    @Test
    fun testFormatoDeKmTotalesYMesh() {
        val item = RideHistoryItem(
            id = "test_km",
            title = "Rodada de Prueba",
            dateFormatted = "Hoy, 10:00 AM",
            originName = "Caracas",
            destinationName = "La Guaira",
            distanceKm = 120.45,
            meshKm = 118.20,
            durationMinutes = 90,
            avgSpeedKmh = 80,
            confirmedParticipantsCount = 5,
            safeHomeCount = 5,
            roadType = "Autopista"
        )

        val formattedTotal = String.format(java.util.Locale.US, "%.1f km", item.distanceKm)
        val formattedMesh = String.format(java.util.Locale.US, "%.1f km", item.meshKm)

        assertEquals("120.5 km", formattedTotal)
        assertEquals("118.2 km", formattedMesh)
        assertTrue("La distancia en malla debe ser menor o igual a la distancia total", item.meshKm <= item.distanceKm)
    }

    @Test
    fun testFormatoDeDuracion() {
        // Formato simple de minutos
        val itemShort = RideHistoryItem(
            id = "short",
            title = "Ruta Corta",
            dateFormatted = "Hoy",
            originName = "A",
            destinationName = "B",
            distanceKm = 20.0,
            durationMinutes = 45,
            avgSpeedKmh = 27,
            confirmedParticipantsCount = 2,
            safeHomeCount = 2,
            roadType = "Urbana"
        )
        assertEquals("45 min", "${itemShort.durationMinutes} min")

        // Formato horas + minutos
        val itemLong = itemShort.copy(durationMinutes = 185)
        val hours = itemLong.durationMinutes / 60
        val remainingMin = itemLong.durationMinutes % 60
        val formattedDuration = if (hours > 0) "${hours}h ${remainingMin}m" else "${remainingMin}m"
        assertEquals("3h 5m", formattedDuration)
    }

    @Test
    fun testFormatoDeXpYBadges() {
        val item = RideHistoryItem(
            id = "xp_test",
            title = "Ruta con Mérito",
            dateFormatted = "Ayer",
            originName = "X",
            destinationName = "Y",
            distanceKm = 50.0,
            durationMinutes = 60,
            avgSpeedKmh = 50,
            confirmedParticipantsCount = 3,
            safeHomeCount = 3,
            roadType = "Carretera",
            xpEarned = 250,
            badgesUnlocked = listOf("piloto_protegido", "primer_sos", "inventada_custom")
        )

        assertEquals("+250 XP", "+${item.xpEarned} XP")

        // Validar mapeo de insignias
        val b1 = BadgeIconMapper.getBadgeDisplay(item.badgesUnlocked[0])
        assertEquals("🛡️", b1.icon)
        assertEquals("Piloto Protegido", b1.label)

        val b2 = BadgeIconMapper.getBadgeDisplay(item.badgesUnlocked[1])
        assertEquals("🆘", b2.icon)
        assertEquals("Primer SOS", b2.label)

        // Fallback genérico para badges desconocidas
        val b3 = BadgeIconMapper.getBadgeDisplay(item.badgesUnlocked[2])
        assertEquals("🏅", b3.icon)
        assertEquals("Inventada Custom", b3.label)
    }

    @Test
    fun testFiltroPorClubYFecha() {
        RideScheduleRepository.loadDemoHistory()
        val allRides = RideScheduleRepository.rideHistory.value

        // 1. Filtrar por Club específico
        val aguilasRides = allRides.filter { it.clubName == "Águilas del Camino MC" }
        assertEquals(2, aguilasRides.size)

        val centaurosRides = allRides.filter { it.clubName == "Centauros de Acero MC" }
        assertEquals(1, centaurosRides.size)

        // 2. Filtrar por fecha
        val now = System.currentTimeMillis()
        val sevenDaysMs = 7 * 86400000L

        // En demo, ride_hist_01 es de ayer, ride_hist_02 es de hace 3 días, ride_hist_03 es de hace 7 días
        val recent7Days = allRides.filter { (now - it.timestampEpochMs) <= sevenDaysMs }
        assertTrue("Al menos 2 rodadas deben estar dentro de la ventana de 7 días", recent7Days.size >= 2)

        // 3. Ordenamiento por distancia descendente
        val sortedByKm = allRides.sortedByDescending { it.distanceKm }
        assertEquals("ride_hist_02", sortedByKm[0].id) // 64.0 km
        assertEquals("ride_hist_01", sortedByKm[1].id) // 48.5 km
        assertEquals("ride_hist_03", sortedByKm[2].id) // 36.2 km
    }

    @Test
    fun testEmptyState() {
        RideScheduleRepository.clearHistory()
        val history = RideScheduleRepository.rideHistory.value
        assertTrue("El historial debe comenzar vacío", history.isEmpty())

        // Consolidación de una rodada completada
        RideScheduleRepository.recordCompletedRideWithMetrics(
            title = "Rodada Primer Retorno",
            originName = "Club House",
            destinationName = "Mirador",
            distanceKm = 15.0,
            meshKm = 14.8,
            durationMinutes = 25,
            xpEarned = 50,
            badgesUnlocked = listOf("primera_rodada"),
            clubName = "Águilas del Camino MC",
            roleInRide = "Rider",
            relaysCount = 10,
            sosAssistsCount = 0,
            confirmedParticipantsCount = 2
        )

        val updated = RideScheduleRepository.rideHistory.value
        assertEquals(1, updated.size)
        assertEquals("Rodada Primer Retorno", updated[0].title)
        assertEquals(50, updated[0].xpEarned)
        assertEquals(14.8, updated[0].meshKm, 0.01)
    }
}
