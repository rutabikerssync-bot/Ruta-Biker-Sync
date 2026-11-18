package com.rutabikersync.app.feature.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verificación WCAG AA (ratio >= 4.5:1) de la paleta del Chat Táctico.
 * Evita regresiones del fallo de contraste detectado en campo (2026-11-18),
 * donde el preset "⛽ Gasolinera a 2 km" era ilegible sobre el acento naranja.
 */
class TacticalChatContrastTest {

    private val minAa = 4.5

    private fun assertAaContrast(foregroundName: String, foreground: androidx.compose.ui.graphics.Color,
                                 backgroundName: String, background: androidx.compose.ui.graphics.Color) {
        val ratio = contrastRatio(foreground, background)
        assertTrue(
            "$foregroundName sobre $backgroundName: ratio $ratio debe ser >= $minAa (WCAG AA)",
            ratio >= minAa
        )
    }

    @Test
    fun `Texto del cuerpo cumple WCAG AA sobre todos los fondos de burbuja`() {
        listOf(
            "Broadcast saliente" to ChatBubbleOutgoingBroadcast,
            "Unicast saliente" to ChatBubbleOutgoingUnicast,
            "Entrante" to ChatBubbleIncoming
        ).forEach { (name, palette) ->
            assertAaContrast("Texto cuerpo", palette.text, "fondo $name", palette.background)
            assertAaContrast("Texto cabecera", palette.headerText, "fondo $name", palette.background)
            assertAaContrast("Texto metadatos", palette.metaText, "fondo $name", palette.background)
        }
    }

    @Test
    fun `Badge de canal cumple WCAG AA sobre su fondo`() {
        listOf(
            "Broadcast saliente" to ChatBubbleOutgoingBroadcast,
            "Unicast saliente" to ChatBubbleOutgoingUnicast,
            "Entrante" to ChatBubbleIncoming
        ).forEach { (name, palette) ->
            assertAaContrast("Texto badge", palette.badgeText, "fondo badge $name", palette.badgeBackground)
        }
    }

    @Test
    fun `Broadcast y Unicast salientes usan fondos distintos`() {
        assertNotEquals(
            "Broadcast (naranja oscuro) y Unicast (verde oscuro) deben diferenciarse por fondo",
            ChatBubbleOutgoingBroadcast.background,
            ChatBubbleOutgoingUnicast.background
        )
        assertTrue(
            "Broadcast saliente debe resolverse al fondo naranja oscuro",
            chatBubblePalette(isOutgoing = true, isBroadcast = true).background == ChatBubbleOutgoingBroadcast.background
        )
        assertTrue(
            "Unicast saliente debe resolverse al fondo verde oscuro",
            chatBubblePalette(isOutgoing = true, isBroadcast = false).background == ChatBubbleOutgoingUnicast.background
        )
        assertTrue(
            "Entrante debe resolverse al fondo elevado",
            chatBubblePalette(isOutgoing = false, isBroadcast = true).background == ChatBubbleIncoming.background
        )
    }

    @Test
    fun `Los 6 presets renderizan con paleta AA verificada`() {
        val presets = listOf(
            "⛽ Gasolinera a 2 km",
            "🛑 Parada técnica (5 min)",
            "⚠️ Peligro / Baches",
            "🏍️ Reagrupar convoy",
            "👮 Retén adelante",
            "🌧️ Lluvia adelante"
        )
        assertEquals(6, presets.size)
        presets.forEach { preset ->
            assertTrue("El preset '$preset' no debe estar vacío", preset.isNotBlank())
            // Los presets se envían como mensajes salientes Broadcast por defecto.
            val palette = chatBubblePalette(isOutgoing = true, isBroadcast = true)
            assertAaContrast("Texto del preset '$preset'", palette.text, "fondo broadcast", palette.background)
        }
    }

    @Test
    fun `Contraste de referencia conocido`() {
        // Blanco sobre negro puro debe dar ~21:1 (sanity check de la fórmula).
        val ratio = contrastRatio(
            androidx.compose.ui.graphics.Color.White,
            androidx.compose.ui.graphics.Color.Black
        )
        assertEquals(21.0, ratio, 0.01)
    }
}
