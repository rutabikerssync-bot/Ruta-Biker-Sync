package com.rutabikersync.app.feature.routes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Paso 3 del plan maestro: regresiones geométricas del limpiador de vías.
 *
 * Cubre dos defectos reales detectados al analizar el trazado de El Jarillo:
 *  1. El suavizado Chaikin mezclaba `p1.latitude` en la longitud del punto `r`.
 *  2. La deduplicación usaba 8e-8 grados² (~31 m) aunque el contrato es 2.5 m.
 *
 *  Sin Android, sin MapLibre y sin malla BLE.
 */
class RoadGeometryCleanerTest {

    /** La longitud suavizada debe permanecer en el corredor real, no saltar a la latitud. */
    @Test
    fun `suavizado Chaikin conserva la longitud del corredor de El Jarillo`() {
        val puntos = listOf(
            LatLngPoint(10.3500, -67.1000),
            LatLngPoint(10.3502, -67.1001),
            LatLngPoint(10.3504, -67.1002),
            LatLngPoint(10.3506, -67.1003),
            LatLngPoint(10.3508, -67.1004)
        )

        val resultado = RoadGeometryCleaner.cleanAndSnap(puntos)

        assertEquals(10, resultado.size)
        assertEquals(puntos.first(), resultado.first())
        assertEquals(puntos.last(), resultado.last())
        assertTrue(
            "Ningún punto suavizado debe salir del corredor de longitud",
            resultado.all { it.longitude in -67.11..-67.09 }
        )
    }

    /** El umbral documentado de 2.5 m debe conservar vértices que el antiguo umbral de 31 m descartaba. */
    @Test
    fun `deduplicacion respeta el espaciado minimo de 2_5 metros`() {
        assertEquals(2.5, RoadGeometryCleaner.MIN_VERTEX_SPACING_METERS, 0.0)

        val puntos = listOf(
            LatLngPoint(0.0, 0.00000), // conservado
            LatLngPoint(0.0, 0.00001), // ~1.1 m -> descartado
            LatLngPoint(0.0, 0.00003), // ~3.3 m -> conservado
            LatLngPoint(0.0, 0.000035), // ~0.6 m -> descartado
            LatLngPoint(0.0, 0.00006), // ~3.3 m -> conservado
            LatLngPoint(0.0, 0.000062), // ~0.2 m -> descartado
            LatLngPoint(0.0, 0.00009) // ~3.3 m -> conservado
        )

        val resultado = RoadGeometryCleaner.cleanAndSnap(puntos)

        // 4 vértices tras deduplicar -> Chaikin(1) genera 1 + 3*2 + 1 = 8 puntos.
        // Con el umbral antiguo quedaba un solo vértice y la función devolvía 1 punto.
        assertEquals(8, resultado.size)
        assertTrue(resultado.all { it.latitude == 0.0 })
    }

    /** Una salida corta y vuelta sobre el mismo eje se elimina sin alterar los extremos. */
    @Test
    fun `filtro de espigas elimina el vertice spurio`() {
        val origen = LatLngPoint(10.0, -67.0)
        val espiga = LatLngPoint(10.0009, -67.0) // ~100 m al norte y regreso
        val final = LatLngPoint(10.00054, -67.0)
        val puntos = listOf(
            origen,
            espiga,
            LatLngPoint(10.00018, -67.0),
            LatLngPoint(10.00036, -67.0),
            final
        )

        val resultado = RoadGeometryCleaner.cleanAndSnap(puntos)

        assertEquals(8, resultado.size)
        assertEquals(origen, resultado.first())
        assertEquals(final, resultado.last())
        assertTrue(
            "La espiga de ~100 m no debe sobrevivir al filtrado",
            resultado.maxOf { it.latitude } < 10.0007
        )
    }

    /** Las horquillas de montaña conservan el vértice exacto del asfalto (El Jarillo / Colonia Tovar). */
    @Test
    fun `horquilla de montana conserva su vertice exacto`() {
        val verticeHorquilla = LatLngPoint(10.00105, -67.00005)
        val puntos = listOf(
            LatLngPoint(10.0, -67.0),
            LatLngPoint(10.001, -67.0),
            verticeHorquilla,
            LatLngPoint(10.00005, -67.0001),
            LatLngPoint(10.0, -67.0002)
        )

        val resultado = RoadGeometryCleaner.cleanAndSnap(puntos)

        assertTrue(
            "El vértice de la horquilla no debe ser cortado por Chaikin",
            resultado.any { it == verticeHorquilla }
        )
    }
}
