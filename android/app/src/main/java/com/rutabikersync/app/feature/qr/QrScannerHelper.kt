package com.rutabikersync.app.feature.qr

import android.app.Activity
import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.common.InputImage

/**
 * Gestor oficial de escaneo de códigos QR para Ruta Biker Sync (Bloque E).
 *
 * Elimina completamente los crashes de Camera1 y la distorsión horizontal en Xiaomi / HyperOS.
 * - Prioridad 1: Google Code Scanner (GmsBarcodeScanning): orientación vertical fija,
 *   auto-focus, auto-zoom, sin necesidad de permisos runtime peligrosos.
 * - Prioridad 2: Escaneo desde imagen en galería (InputImage + ML Kit Barcode).
 */
object QrScannerHelper {

    /**
     * Inicia el escáner oficial de Google en orientación vertical nativa.
     */
    fun startNativeQrScan(
        activity: Activity,
        onSuccess: (rawCode: String) -> Unit,
        onCancelled: () -> Unit,
        onError: (message: String) -> Unit
    ) {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()

        val scanner = GmsBarcodeScanning.getClient(activity, options)

        scanner.startScan()
            .addOnSuccessListener { barcode ->
                val rawValue = barcode.rawValue
                if (!rawValue.isNullOrBlank()) {
                    onSuccess(rawValue)
                } else {
                    onError("No se detectó contenido en el código QR")
                }
            }
            .addOnCanceledListener {
                onCancelled()
            }
            .addOnFailureListener { e ->
                onError(e.localizedMessage ?: "Error al iniciar escáner de cámara")
            }
    }

    /**
     * Decodifica un código QR a partir de una foto de la galería del teléfono.
     */
    fun decodeQrFromGalleryUri(
        context: Context,
        uri: Uri,
        onSuccess: (rawCode: String) -> Unit,
        onError: (message: String) -> Unit
    ) {
        try {
            val image = InputImage.fromFilePath(context, uri)
            val scanner = BarcodeScanning.getClient()

            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    val qrCode = barcodes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }
                    val raw = qrCode?.rawValue
                    if (!raw.isNullOrBlank()) {
                        onSuccess(raw)
                    } else {
                        onError("La imagen seleccionada no contiene un código QR válido")
                    }
                }
                .addOnFailureListener { e ->
                    onError(e.localizedMessage ?: "Error al procesar la imagen")
                }
        } catch (e: Exception) {
            onError("No se pudo abrir la imagen seleccionada: ${e.message}")
        }
    }
}

