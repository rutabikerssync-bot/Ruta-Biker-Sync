package com.rutabikersync.app.feature.vault

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Utilidades para guardar fotos/scans de documentos de manera segura en almacenamiento privado
 * y cargarlos en Compose con escalado de memoria eficiente (prevención de OOM).
 */
object VaultImageUtils {

    fun saveImageToVault(context: Context, sourceUri: Uri): String? {
        return try {
            val vaultDir = File(context.filesDir, "vault_documents").apply {
                if (!exists()) mkdirs()
            }
            val destinationFile = File(vaultDir, "doc_${UUID.randomUUID()}.jpg")

            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(destinationFile).use { output ->
                    input.copyTo(output)
                }
            }
            destinationFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun saveAvatarImage(context: Context, sourceUri: Uri): String? {
        return try {
            val avatarsDir = File(context.filesDir, "user_avatars").apply {
                if (!exists()) mkdirs()
            }
            val destinationFile = File(avatarsDir, "avatar_${UUID.randomUUID().toString().take(8)}.webp")
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                val originalBitmap = BitmapFactory.decodeStream(input)
                if (originalBitmap != null) {
                    val scaled = android.graphics.Bitmap.createScaledBitmap(originalBitmap, 256, 256, true)
                    FileOutputStream(destinationFile).use { out ->
                        scaled.compress(android.graphics.Bitmap.CompressFormat.WEBP, 85, out)
                    }
                    destinationFile.absolutePath
                } else null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun loadBitmap(path: String, reqWidth: Int = 900, reqHeight: Int = 900): ImageBitmap? {
        return try {
            val file = File(path)
            if (!file.exists()) return null

            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, options)

            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
            options.inJustDecodeBounds = false

            val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
            bitmap?.asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2

            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
