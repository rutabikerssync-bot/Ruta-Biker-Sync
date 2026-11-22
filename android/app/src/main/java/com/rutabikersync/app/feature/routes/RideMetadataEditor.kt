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
import androidx.compose.ui.graphics.Color
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
        focusedBorderColor = Color(0xFFF59E0B),
        unfocusedBorderColor = Color(0xFFC86D44),
        focusedLabelColor = Color(0xFFFCD34D),
        unfocusedLabelColor = Color(0xFFE5B869),
        focusedTextColor = Color(0xFFF8FAFC),
        unfocusedTextColor = Color(0xFFE2E8F0),
        focusedContainerColor = Color(0xFF161A23),
        unfocusedContainerColor = Color(0xFF12151C),
        cursorColor = Color(0xFFF59E0B)
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF161A23))
            .border(1.2.dp, Color(0xFFE58B2A), RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "DATOS DE LA CONVOCATORIA",
            color = Color(0xFFF6CE74),
            fontSize = 12.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.5.sp
        )
        OutlinedTextField(
            value = title,
            onValueChange = {
                onTitleChange(it)
                onClearError()
            },
            label = { Text("Nombre de la rodada *", fontWeight = FontWeight.SemiBold) },
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
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color(0xFF1E2430),
                    contentColor = Color.White
                ),
                border = BorderStroke(1.2.dp, Color(0xFFE58B2A)),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                modifier = Modifier.weight(1f).height(44.dp)
            ) {
                Icon(
                    Icons.Default.CalendarToday,
                    contentDescription = null,
                    tint = Color(0xFFF59E0B),
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = startsAt.format(dateFormatter),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 6.dp)
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
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color(0xFF1E2430),
                    contentColor = Color.White
                ),
                border = BorderStroke(1.2.dp, Color(0xFFE58B2A)),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                modifier = Modifier.weight(0.7f).height(44.dp)
            ) {
                Icon(
                    Icons.Default.Schedule,
                    contentDescription = null,
                    tint = Color(0xFFF59E0B),
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = startsAt.format(timeFormatter),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(start = 6.dp)
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
