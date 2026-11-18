package com.rutabikersync.app.feature.routes

import android.content.Context
import android.graphics.*
import kotlin.math.*

/**
 * Utilidades para renderizar píldoras flotantes de tiempo (chips de ruta estilo Google Maps)
 * y detección táctil precisa de rutas viales en MapLibre.
 */
object RouteMapOverlayHelper {

    /**
     * Formatea los minutos en formato amigable estilo Google Maps: "1 h 52 min" o "45 min".
     */
    fun formatRouteDuration(minutes: Int): String {
        return if (minutes >= 60) {
            val hours = minutes / 60
            val remMin = minutes % 60
            if (remMin == 0) "${hours} h" else "${hours} h ${remMin} min"
        } else {
            "${minutes} min"
        }
    }

    /**
     * Crea un Bitmap nítido con forma de píldora flotante y flecha inferior hacia la carretera.
     * - isSelected = true: Fondo azul vibrante Google Maps (#2563EB) con tipografía blanca en negrita.
     * - isSelected = false: Fondo slate oscuro (#1E293B) con borde (#64748B) y texto gris claro (#E2E8F0).
     */
    fun createTimeChipBitmap(
        context: Context,
        timeText: String,
        subtitleText: String? = null,
        isSelected: Boolean
    ): Bitmap {
        val density = context.resources.displayMetrics.density

        // Configuración de tamaños según densidad
        val horizontalPad = 12f * density
        val verticalPad = 6f * density
        val titleTextSize = (if (isSelected) 13.5f else 12f) * density
        val subtitleTextSize = 10f * density
        val arrowHeight = 6f * density
        val arrowWidth = 10f * density
        val cornerRadius = 14f * density

        // Pintura para el texto principal
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isSelected) Color.WHITE else Color.parseColor("#E2E8F0")
            textSize = titleTextSize
            typeface = Typeface.create(Typeface.DEFAULT, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }

        // Pintura para subtítulo (km o peajes)
        val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isSelected) Color.parseColor("#93C5FD") else Color.parseColor("#94A3B8")
            textSize = subtitleTextSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }

        // Medir ancho de textos
        val titleWidth = titlePaint.measureText(timeText)
        val subWidth = if (!subtitleText.isNullOrBlank()) subtitlePaint.measureText(subtitleText) else 0f
        val maxContentWidth = max(titleWidth, subWidth)

        val pillWidth = maxContentWidth + (horizontalPad * 2f)
        val pillHeight = if (!subtitleText.isNullOrBlank()) {
            verticalPad * 2f + titleTextSize + subtitleTextSize + (2f * density)
        } else {
            verticalPad * 2f + titleTextSize
        }

        val totalWidth = ceil(pillWidth + (8f * density)).toInt() // Margen para sombra
        val totalHeight = ceil(pillHeight + arrowHeight + (8f * density)).toInt()

        val bitmap = Bitmap.createBitmap(totalWidth, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val left = (totalWidth - pillWidth) / 2f
        val top = 2f * density
        val right = left + pillWidth
        val bottom = top + pillHeight
        val rectF = RectF(left, top, right, bottom)

        // Sombra sutil
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#40000000")
            style = Paint.Style.FILL
            maskFilter = BlurMaskFilter(3f * density, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawRoundRect(RectF(left, top + (2f * density), right, bottom + (2f * density)), cornerRadius, cornerRadius, shadowPaint)

        // Fondo del chip
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isSelected) Color.parseColor("#2563EB") else Color.parseColor("#1E293B")
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, bgPaint)

        // Borde
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isSelected) Color.parseColor("#3B82F6") else Color.parseColor("#64748B")
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
        }
        canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, borderPaint)

        // Flecha triangular inferior que apunta hacia la carretera
        val midX = totalWidth / 2f
        val arrowPath = Path().apply {
            moveTo(midX - (arrowWidth / 2f), bottom - (1f * density))
            lineTo(midX + (arrowWidth / 2f), bottom - (1f * density))
            lineTo(midX, bottom + arrowHeight)
            close()
        }
        canvas.drawPath(arrowPath, bgPaint)

        // Dibujar textos centrados
        if (!subtitleText.isNullOrBlank()) {
            val titleY = top + verticalPad + titleTextSize - (2f * density)
            canvas.drawText(timeText, midX, titleY, titlePaint)
            val subY = titleY + subtitleTextSize + (3f * density)
            canvas.drawText(subtitleText, midX, subY, subtitlePaint)
        } else {
            val fontMetrics = titlePaint.fontMetrics
            val titleY = top + (pillHeight / 2f) - ((fontMetrics.ascent + fontMetrics.descent) / 2f)
            canvas.drawText(timeText, midX, titleY, titlePaint)
        }

        return bitmap
    }

    /**
     * Calcula el punto a lo largo de la ruta que se encuentra al porcentaje acumulado dado (0.0 a 1.0).
     * Por ejemplo, 0.5 representa el 50% de la distancia total de la ruta.
     */
    fun calculateRoutePointAtFraction(waypoints: List<LatLngPoint>, fraction: Double = 0.5): LatLngPoint {
        if (waypoints.isEmpty()) return LatLngPoint(10.3444, -67.0422)
        if (waypoints.size < 3) return waypoints[waypoints.size / 2]

        var totalDist = 0.0
        val dists = DoubleArray(waypoints.size)
        for (i in 0 until waypoints.size - 1) {
            val d = distanceMeters(waypoints[i], waypoints[i + 1])
            totalDist += d
            dists[i + 1] = totalDist
        }

        val targetDist = totalDist * fraction.coerceIn(0.1, 0.9)
        var bestIdx = 0
        var minDiff = Double.MAX_VALUE
        for (i in waypoints.indices) {
            val diff = abs(dists[i] - targetDist)
            if (diff < minDiff) {
                minDiff = diff
                bestIdx = i
            }
        }
        return waypoints[bestIdx]
    }

    /**
     * Encuentra la ruta más cercana a un toque táctil del usuario en el mapa.
     * Devuelve el par (Ruta, distanciaEnMetros).
     */
    fun findClosestRoute(touchPoint: LatLngPoint, routes: List<RouteAlternative>): Pair<RouteAlternative, Double>? {
        if (routes.isEmpty()) return null

        var bestRoute: RouteAlternative? = null
        var minDistance = Double.MAX_VALUE

        for (route in routes) {
            val pts = route.waypoints
            if (pts.size < 2) continue

            var routeMinDist = Double.MAX_VALUE
            for (i in 0 until pts.size - 1) {
                val d = distancePointToSegmentMeters(touchPoint, pts[i], pts[i + 1])
                if (d < routeMinDist) {
                    routeMinDist = d
                }
            }

            if (routeMinDist < minDistance) {
                minDistance = routeMinDist
                bestRoute = route
            }
        }

        return if (bestRoute != null) Pair(bestRoute, minDistance) else null
    }

    /**
     * Distancia en metros entre dos puntos geográficos (fórmula esférica aproximada).
     */
    fun distanceMeters(p1: LatLngPoint, p2: LatLngPoint): Double {
        val latRad = Math.toRadians((p1.latitude + p2.latitude) / 2.0)
        val mPerLat = 111132.954 - 559.822 * cos(2.0 * latRad)
        val mPerLon = 111412.84 * cos(latRad)

        val dLat = (p2.latitude - p1.latitude) * mPerLat
        val dLon = (p2.longitude - p1.longitude) * mPerLon
        return sqrt(dLat * dLat + dLon * dLon)
    }

    /**
     * Distancia perpendicular de un punto a un segmento de línea [a -> b] en metros.
     */
    private fun distancePointToSegmentMeters(p: LatLngPoint, a: LatLngPoint, b: LatLngPoint): Double {
        val latRad = Math.toRadians((a.latitude + b.latitude) / 2.0)
        val mPerLat = 111132.954 - 559.822 * cos(2.0 * latRad)
        val mPerLon = 111412.84 * cos(latRad)

        val px = p.longitude * mPerLon
        val py = p.latitude * mPerLat
        val ax = a.longitude * mPerLon
        val ay = a.latitude * mPerLat
        val bx = b.longitude * mPerLon
        val by = b.latitude * mPerLat

        val dx = bx - ax
        val dy = by - ay
        val lengthSq = dx * dx + dy * dy

        if (lengthSq <= 0.000001) {
            val dpx = px - ax
            val dpy = py - ay
            return sqrt(dpx * dpx + dpy * dpy)
        }

        val t = ((px - ax) * dx + (py - ay) * dy) / lengthSq
        val clampedT = max(0.0, min(1.0, t))
        val projX = ax + clampedT * dx
        val projY = ay + clampedT * dy

        val finalDx = px - projX
        val finalDy = py - projY
        return sqrt(finalDx * finalDx + finalDy * finalDy)
    }
}
