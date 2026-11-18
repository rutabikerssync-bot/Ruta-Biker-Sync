package com.rutabikersync.app.core.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Utilidad de compresión y redimensionado de imágenes en el cliente (D98).
 * Garantiza payloads livianos (< 2 MB) en formato WebP o JPEG antes del envío al backend.
 */
object ImageCompressor {

    const val MAX_IMAGE_SIZE_BYTES = 2L * 1024 * 1024 // 2 MB
    const val DEFAULT_MAX_DIMENSION = 1080

    /**
     * Escala proporcionalmente un Bitmap para que ni su ancho ni su alto superen [maxDimension].
     */
    fun scaleBitmap(bitmap: Bitmap, maxDimension: Int = DEFAULT_MAX_DIMENSION): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxDimension && height <= maxDimension) {
            return bitmap
        }
        val ratio = min(maxDimension.toFloat() / width.toFloat(), maxDimension.toFloat() / height.toFloat())
        val targetWidth = max(1, (width * ratio).toInt())
        val targetHeight = max(1, (height * ratio).toInt())
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    /**
     * Comprime un Bitmap a WebP (con fallback a JPEG si el formato no está disponible).
     */
    fun compressBitmap(
        bitmap: Bitmap,
        maxDimension: Int = DEFAULT_MAX_DIMENSION,
        initialQuality: Int = 80,
        maxSizeBytes: Long = MAX_IMAGE_SIZE_BYTES
    ): ByteArray {
        val scaled = scaleBitmap(bitmap, maxDimension)
        var quality = initialQuality
        var stream = ByteArrayOutputStream()

        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }

        scaled.compress(format, quality, stream)

        // Si supera el límite de bytes, reducir calidad iterativamente
        while (stream.size() > maxSizeBytes && quality > 20) {
            quality -= 15
            stream = ByteArrayOutputStream()
            scaled.compress(format, quality, stream)
        }

        // Si aún superase (poco probable en 1080p), reintentar en JPEG
        if (stream.size() > maxSizeBytes) {
            quality = 60
            stream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        }

        return stream.toByteArray()
    }

    /**
     * Convierte un arreglo de bytes en representación Base64 estándar (sin saltos de línea).
     */
    fun toBase64(bytes: ByteArray): String {
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /**
     * Procesa y comprime una imagen a partir de un [Uri] del sistema o galería.
     * Retorna el arreglo de bytes comprimido y su representación Base64.
     */
    fun compressFromUri(
        context: Context,
        uri: Uri,
        maxDimension: Int = DEFAULT_MAX_DIMENSION
    ): Result<Pair<ByteArray, String>> = runCatching {
        var input: InputStream? = null
        val bitmap = try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            var inSampleSize = 1
            if (options.outHeight > maxDimension || options.outWidth > maxDimension) {
                val halfHeight = options.outHeight / 2
                val halfWidth = options.outWidth / 2
                while ((halfHeight / inSampleSize) >= maxDimension && (halfWidth / inSampleSize) >= maxDimension) {
                    inSampleSize *= 2
                }
            }

            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
            }
            input = context.contentResolver.openInputStream(uri)
            BitmapFactory.decodeStream(input, null, decodeOptions)
                ?: throw IllegalArgumentException("No se pudo decodificar la imagen desde Uri: $uri")
        } finally {
            input?.close()
        }

        val compressedBytes = compressBitmap(bitmap, maxDimension)
        val base64 = toBase64(compressedBytes)
        Pair(compressedBytes, base64)
    }
}
