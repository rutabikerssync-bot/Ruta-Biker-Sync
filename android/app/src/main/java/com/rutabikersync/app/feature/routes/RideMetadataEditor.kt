package com.rutabikersync.app.feature.routes

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rutabikersync.app.ui.theme.HeritageBorder
import com.rutabikersync.app.ui.theme.HeritageCardWarm
import com.rutabikersync.app.ui.theme.HeritageCopper
import com.rutabikersync.app.ui.theme.HeritageCopperDark
import com.rutabikersync.app.ui.theme.HeritageLeatherBrown
import com.rutabikersync.app.ui.theme.HeritageLeatherMuted
import com.rutabikersync.app.ui.theme.RescueRed
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Metadatos de la rodada dentro del resumen del mapa existente.
 * 60% mapa/fondo, 30% superficies cálidas y 10% cobre para acciones/acentos.
 */
@Composable
fun RideMetadataEditor(
    title: String,
    onTitleChange: (String) -> Unit,
    startsAt: LocalDateTime,
    onStartsAtChange: (LocalDateTime) -> Unit,
    description: String,
    onDescriptionChange: (String) -> Unit,
    errorMessage: String?,
    onClearError: () -> Unit
) {
    val context = LocalContext.current
    val dateFormatter = remember { DateTimeFormatter.ofPattern("EEE dd/MM/yyyy", Locale.getDefault()) }
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()) }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = HeritageCopper,
        unfocusedBorderColor = HeritageBorder,
        focusedLabelColor = HeritageCopperDark,
        unfocusedLabelColor = HeritageLeatherMuted,
        focusedTextColor = HeritageLeatherBrown,
        unfocusedTextColor = HeritageLeatherBrown,
        cursorColor = HeritageCopper
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(HeritageCardWarm.copy(alpha = 0.72f))
            .border(1.dp, HeritageBorder.copy(alpha = 0.65f), RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "DATOS DE LA RODADA",
            color = HeritageCopperDark,
            fontSize = 11.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.5.sp
        )
        OutlinedTextField(
            value = title,
            onValueChange = {
                onTitleChange(it)
                onClearError()
            },
            label = { Text("Nombre de la rodada *") },
            singleLine = true,
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    onClearError()
                    DatePickerDialog(
                        context,
                        { _, year, month, dayOfMonth ->
                            onStartsAtChange(
                                startsAt.withYear(year).withMonth(month + 1).withDayOfMonth(dayOfMonth)
                            )
                        },
                        startsAt.year,
                        startsAt.monthValue - 1,
                        startsAt.dayOfMonth
                    ).show()
                },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = HeritageLeatherBrown),
                border = BorderStroke(1.dp, HeritageCopper.copy(alpha = 0.65f)),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                modifier = Modifier.weight(1f).height(42.dp)
            ) {
                Icon(
                    Icons.Default.CalendarToday,
                    contentDescription = null,
                    tint = HeritageCopper,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = startsAt.format(dateFormatter),
                    fontSize = 11.sp,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 5.dp)
                )
            }

            OutlinedButton(
                onClick = {
                    onClearError()
                    TimePickerDialog(
                        context,
                        { _, hourOfDay, minute ->
                            onStartsAtChange(
                                startsAt.withHour(hourOfDay).withMinute(minute).withSecond(0).withNano(0)
                            )
                        },
                        startsAt.hour,
                        startsAt.minute,
                        android.text.format.DateFormat.is24HourFormat(context)
                    ).show()
                },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = HeritageLeatherBrown),
                border = BorderStroke(1.dp, HeritageCopper.copy(alpha = 0.65f)),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                modifier = Modifier.weight(0.65f).height(42.dp)
            ) {
                Icon(
                    Icons.Default.Schedule,
                    contentDescription = null,
                    tint = HeritageCopper,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = startsAt.format(timeFormatter),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 5.dp)
                )
            }
        }

        OutlinedTextField(
            value = description,
            onValueChange = {
                onDescriptionChange(it)
                onClearError()
            },
            label = { Text("Descripción (opcional)") },
            minLines = 2,
            maxLines = 4,
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth()
        )

        errorMessage?.let { message ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = message,
                    color = RescueRed,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
