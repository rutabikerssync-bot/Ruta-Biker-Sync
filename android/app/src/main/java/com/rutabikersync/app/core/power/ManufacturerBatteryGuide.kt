package com.rutabikersync.app.core.power

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rutabikersync.app.ui.theme.BikerColors
import java.util.Locale

enum class OemBatteryVendor(
    val displayTitle: String,
    val skinName: String,
    val steps: List<String>
) {
    XIAOMI_HYPEROS(
        displayTitle = "Xiaomi / Redmi / POCO",
        skinName = "MIUI / HyperOS",
        steps = listOf(
            "1. En Información de la aplicación -> Batería, selecciona 'Sin restricciones'.",
            "2. Activa 'Inicio automático en segundo plano' (Autostart) para mantener viva la mesh BLE.",
            "3. En Recientes, fija Ruta Biker Sync con el candado para evitar que HyperOS cierre RideService."
        )
    ),
    TECNO_HIOS(
        displayTitle = "Tecno / Infinix / Itel",
        skinName = "HiOS / XOS",
        steps = listOf(
            "1. En Phone Master -> Gestión de inicio automático, autoriza Ruta Biker Sync.",
            "2. En Ajustes -> Batería -> Optimización de batería, selecciona 'No optimizar'.",
            "3. Desactiva el bloqueo de Bluetooth en segundo plano en Ahorro inteligente."
        )
    ),
    HUAWEI_EMUI(
        displayTitle = "Huawei / Honor",
        skinName = "EMUI / HarmonyOS",
        steps = listOf(
            "1. En Ajustes -> Batería -> Inicio de aplicaciones, desactiva 'Gestionar automáticamente'.",
            "2. En la ventana emergente, activa 'Inicio automático', 'Inicio secundario' y 'Ejecutar en segundo plano'.",
            "3. Permite mantener la conexión Bluetooth activa con pantalla apagada."
        )
    ),
    SAMSUNG_ONEUI(
        displayTitle = "Samsung Galaxy",
        skinName = "One UI",
        steps = listOf(
            "1. En Ajustes -> Aplicaciones -> Ruta Biker Sync -> Batería, selecciona 'No restringido'.",
            "2. En Cuidado del dispositivo -> Batería -> Límites de uso en segundo plano, añade la app a 'Aplicaciones que nunca se suspenden'."
        )
    ),
    OPPO_COLOROS(
        displayTitle = "Oppo / Realme / OnePlus",
        skinName = "ColorOS / Realme UI / OxygenOS",
        steps = listOf(
            "1. En Información de la app -> Uso de batería, activa 'Permitir actividad en segundo plano' y 'Permitir inicio automático'.",
            "2. Desactiva la optimización de espera en segundo plano."
        )
    ),
    VIVO_FUNTOUCH(
        displayTitle = "Vivo / iQOO",
        skinName = "FuntouchOS / OriginOS",
        steps = listOf(
            "1. En Ajustes -> Batería -> Consumo de energía en segundo plano, activa 'Alto consumo en segundo plano' para Ruta Biker Sync.",
            "2. Activa el permiso de Autoinicio en iManager."
        )
    ),
    GENERIC_ANDROID(
        displayTitle = "Android Estándar",
        skinName = "AOSP",
        steps = listOf(
            "1. En Ajustes -> Aplicaciones -> Ruta Biker Sync -> Batería, selecciona 'Sin restricciones'.",
            "2. Permite que la aplicación se ejecute en segundo plano sin optimización de batería."
        )
    )
}

data class BatteryGuideSnapshot(
    val vendor: OemBatteryVendor,
    val shouldPromptBeforeRide: Boolean,
    val isMandatory: Boolean = false, // Decisión 3.2 (B): Recomendada, no obligatoria
    val skippedForCurrentSession: Boolean = false,
    val permanentlyDismissed: Boolean = false
)

/**
 * Guía de batería por fabricante (`SCREENS.md` A6, Bloque 3.5):
 * - **Decisión 3.2 (B):** Recomendada, no obligatoria. Se muestra antes de la primera rodada según
 *   `Build.MANUFACTURER`, con botones `"Abrir ajustes del sistema"`, `"Saltar"` y `"No volver a mostrar"`.
 */
class ManufacturerBatteryGuide(
    initialManufacturer: String = Build.MANUFACTURER ?: "Generic",
    initialBrand: String = Build.BRAND ?: initialManufacturer
) {
    companion object {
        fun resolveVendor(manufacturer: String, brand: String = manufacturer): OemBatteryVendor {
            val combined = "${manufacturer.lowercase(Locale.ROOT)} ${brand.lowercase(Locale.ROOT)}"
            return when {
                combined.contains("xiaomi") || combined.contains("redmi") || combined.contains("poco") ->
                    OemBatteryVendor.XIAOMI_HYPEROS
                combined.contains("tecno") || combined.contains("infinix") || combined.contains("itel") || combined.contains("transsion") ->
                    OemBatteryVendor.TECNO_HIOS
                combined.contains("huawei") || combined.contains("honor") ->
                    OemBatteryVendor.HUAWEI_EMUI
                combined.contains("samsung") ->
                    OemBatteryVendor.SAMSUNG_ONEUI
                combined.contains("oppo") || combined.contains("realme") || combined.contains("oneplus") ->
                    OemBatteryVendor.OPPO_COLOROS
                combined.contains("vivo") || combined.contains("iqoo") ->
                    OemBatteryVendor.VIVO_FUNTOUCH
                else -> OemBatteryVendor.GENERIC_ANDROID
            }
        }
    }

    private var vendor: OemBatteryVendor = resolveVendor(initialManufacturer, initialBrand)
    private var skippedThisSession: Boolean = false
    private var dontShowAgain: Boolean = false

    @Synchronized
    fun updateDetectedDevice(manufacturer: String, brand: String = manufacturer): BatteryGuideSnapshot {
        vendor = resolveVendor(manufacturer, brand)
        return snapshot()
    }

    @Synchronized
    fun skipForNow(): BatteryGuideSnapshot {
        skippedThisSession = true
        return snapshot()
    }

    @Synchronized
    fun markCompletedOrDontShowAgain(): BatteryGuideSnapshot {
        dontShowAgain = true
        return snapshot()
    }

    @Synchronized
    fun snapshot(): BatteryGuideSnapshot {
        return BatteryGuideSnapshot(
            vendor = vendor,
            shouldPromptBeforeRide = !skippedThisSession && !dontShowAgain,
            isMandatory = false, // Decisión 3.2 (B): nunca bloquea el inicio de rodada
            skippedForCurrentSession = skippedThisSession,
            permanentlyDismissed = dontShowAgain
        )
    }

    fun openSystemBatterySettings(context: Context) {
        runCatching {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }.onFailure {
            runCatching {
                val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(fallback)
            }
        }
    }
}

@Composable
fun ManufacturerBatteryGuideCard(
    snapshot: BatteryGuideSnapshot,
    onOpenSettings: () -> Unit,
    onSkip: () -> Unit,
    onDontShowAgain: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .fillMaxWidth()
            .background(BikerColors.SurfaceDark, RoundedCornerShape(12.dp))
            .border(1.dp, BikerColors.PrimaryAmber, RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Text(
            text = "A6 · Guía recomendada de batería (${snapshot.vendor.displayTitle} · ${snapshot.vendor.skinName})",
            color = BikerColors.PrimaryAmber,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
        snapshot.vendor.steps.forEach { step ->
            Text(
                text = step,
                color = BikerColors.TextPrimary,
                fontSize = 13.sp
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(
                onClick = onOpenSettings,
                colors = ButtonDefaults.buttonColors(containerColor = BikerColors.PrimaryAmber),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
            ) {
                Text(
                    text = "Abrir ajustes",
                    color = BikerColors.BackgroundDark,
                    fontWeight = FontWeight.Bold
                )
            }
            OutlinedButton(
                onClick = onSkip,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(text = "Saltar", color = BikerColors.TextPrimary)
            }
            OutlinedButton(
                onClick = onDontShowAgain,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(text = "No mostrar más", color = BikerColors.TextSecondary)
            }
        }
    }
}
