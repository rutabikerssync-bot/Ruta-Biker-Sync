package com.rutabikersync.app.ui.navigation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rutabikersync.app.R
import com.rutabikersync.app.core.uxtest.UxTestTracker

/**
 * Cabecera decorativa de la tarjeta de PARTICIPANTES: imagen de la antigua
 * card 3 convertida desde card3.2.png. No tiene ninguna acción asociada.
 */
@Composable
fun ParticipantsHeaderBanner() {
    Box(modifier = Modifier.fillMaxWidth().height(195.dp)) {
        Image(
            painter = painterResource(id = R.drawable.card_participantes),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            alignment = Alignment.TopCenter,
            contentScale = ContentScale.Crop
        )
    }
}

/** Etiqueta negra de sección, coherente con el resto de tarjetas de Rodadas. */
@Composable
private fun SectionBadge(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black)
            .border(1.dp, Color(0xFF3E4455), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text = text, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * Ruta Libre: rodada solitaria disponible para TODOS (con o sin club).
 * No toca el ciclo de la rodada de club: solo abre el planner o la cabina local.
 */
@Composable
fun RutaLibreCard(onPlan: () -> Unit, onStart: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().heightIn(min = 230.dp)) {
        Image(
            painter = painterResource(id = R.drawable.card_rodada_solitaria),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            alignment = Alignment.TopCenter,
            contentScale = ContentScale.Crop
        )
        Row(
            modifier = Modifier.fillMaxWidth().align(Alignment.TopStart).padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = "Ruta Libre",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            SectionBadge(text = "Modo Libre")
        }
        Column(modifier = Modifier.fillMaxWidth().align(Alignment.BottomStart).padding(14.dp)) {
            Text(
                text = "Modo Libre (sin registro de club, sin gamificación)",
                color = Color.White,
                fontSize = 11.sp
            )
        }
    }

    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(
            onClick = {
                UxTestTracker.logEvent("RODADAS_HOME", "TAP_PLAN_ROUTE", "btn_crear_ruta_solitaria")
                onPlan()
            },
            modifier = Modifier.weight(1f),
            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                contentColor = Color.White
            ),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF38BDF8))
        ) {
            Text("🗺️ Ruta Libre", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }

        Button(
            onClick = {
                UxTestTracker.logEvent("RODADAS_HOME", "TAP_START_RIDE", "btn_iniciar_ruta_libre")
                onStart()
            },
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFDF6E32),
                contentColor = Color.White
            )
        ) {
            Text(
                text = stringResource(id = R.string.rides_btn_start_free_ride),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}
