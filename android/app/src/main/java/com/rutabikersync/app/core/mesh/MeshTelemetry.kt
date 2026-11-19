package com.rutabikersync.app.core.mesh

import kotlin.math.abs

/**
 * Validación compartida de coordenadas de telemetry antes de emitirlas por BLE o de
 * consumirlas en el mapa. Un punto (0,0) o un valor no finito es un centinela, no una
 * posición real: dibujarlo generaba distancias del orden de miles de kilómetros.
 *
 * No modifica el formato del paquete, la firma HMAC ni el transporte GATT/BLE.
 */
object MeshTelemetry {
    fun isValidPosition(latitude: Double, longitude: Double): Boolean =
        latitude.isFinite() && longitude.isFinite() &&
            abs(latitude) <= 90.0 && abs(longitude) <= 180.0 &&
            !(latitude == 0.0 && longitude == 0.0)
}
