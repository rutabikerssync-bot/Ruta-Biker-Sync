package com.rutabikersync.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Paleta Oficial "Dark Elite + Heritage" de Ruta Biker Sync.
 * Estética biker de alto rendimiento: Slate-950, Cobre Heritage, Oro Fundador y Esmeralda Malla.
 */

// Fondos y Superficies Dark Elite
val DarkSlate950 = Color(0xFF080D12)        // Fondo general (Slate-950 puro / noche profunda)
val DarkSlate900 = Color(0xFF0E131A)        // Superficie base de cabina / live map
val DarkCardBg = Color(0xFF161B22)          // Tarjetas y paneles Dark Elite
val DarkCardElevated = Color(0xFF1F2630)    // Contenedores elevados y modales
val DarkBorderSubtle = Color(0xFF28303C)    // Bordes sutiles de delimitación
val DarkBorderProminent = Color(0xFF384354) // Bordes de enfoque y relieve
val DarkBorder = DarkBorderSubtle           // Alias canónico DarkBorder

// Acentos Metálicos Heritage
val HeritageCopper = Color(0xFFC86D44)       // Acento principal interactivo (Cobre Heritage refinado)
val HeritageCopperDark = Color(0xFF8B3A1A)   // Terracota quemado / cobre oscuro
val HeritageCopperGlow = Color(0xFFE07A4D)   // Cobre encendido para feedback táctil
val HeritageBrassGold = Color(0xFFD4AF37)    // Oro Fundador / insignias y rangos de mérito
val HeritageGoldGlow = Color(0xFFF3C644)     // Oro brillante para logros y coronas

// Estados Semánticos (Alto Contraste Táctico)
val EmeraldMesh = Color(0xFF10B981)          // Verde Esmeralda: Malla enlazada / online / seguro
val EmeraldMeshGlow = Color(0xFF34D399)      // Pulso de radiofrecuencia activo
val RescueRed = Color(0xFFEF4444)            // Rojo Rescate: Alerta SOS / emergencia crítica
val RescueRedDark = Color(0xFFB91C1C)        // Fondo de alerta SOS / peligro
val WarningAmber = Color(0xFFF59E0B)         // Ámbar advertencia / desvío de ruta / semáforo

// Textos y Contenido
val TextWhite = Color(0xFFF0F6FC)            // Texto principal de alto contraste (Slate-50)
val TextMuted = Color(0xFF94A3B8)            // Texto secundario (Slate-400)
val TextTertiary = Color(0xFF64748B)         // Texto terciario y metadatos (Slate-500)
val TextSilver = TextMuted

// Mapeos canónicos para adopción instantánea en toda la base de código (304+ referencias)
val HeritageSand = DarkSlate950              // Fondos de pantalla migran a Slate-950
val HeritageCard = DarkCardBg                // Tarjetas migran a Card Dark Elite
val HeritageCardWarm = DarkCardElevated      // Tarjetas destacadas migran a superficie elevada
val HeritageBorder = DarkBorderSubtle        // Bordes migran a delimitación oscura nítida
val HeritageLeatherBrown = TextWhite         // Textos principales migran a blanco Slate-50
val HeritageLeatherMuted = TextMuted         // Textos secundarios migran a gris Slate-400
val HeritageRustRed = RescueRed              // Alertas migran a rojo rescate puro
val HeritageGreen = EmeraldMesh              // Estados activos migran a verde esmeralda
val HeritageGreenLight = Color(0xFF064E3B)   // Contenedor verde esmeralda translúcido
val HeritageAmber = WarningAmber             // Advertencias migran a ámbar táctico

// Alias de retrocompatibilidad unificados a Dark Elite
val AsphaltBlack = DarkSlate950
val CarbonSurface = DarkCardBg
val TitaniumBorder = DarkBorderSubtle
val NeonOrange = HeritageCopper
val RaceRed = RescueRed
val HonorGold = HeritageBrassGold
