package com.rutabikersync.app.feature.promo

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun PromoBannerModal(
    banners: List<PromoBanner>,
    onDismiss: () -> Unit,
    onBannerAction: (PromoBanner) -> Unit
) {
    if (banners.isEmpty()) return
    val pagerState = rememberPagerState(pageCount = { banners.size })

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color(0xFF0A0D14))
                    .border(1.2.dp, Color(0xFF334155), RoundedCornerShape(22.dp)),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxWidth()
                ) { page ->
                    val banner = banners[page]
                    val accent = runCatching {
                        Color(android.graphics.Color.parseColor(banner.accentColorHex))
                    }.getOrDefault(Color(0xFFE5B869))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color(0xFF090C12),
                                        Color(0xFF101521),
                                        accent.copy(alpha = 0.42f)
                                    )
                                )
                            )
                            .padding(horizontal = 24.dp, vertical = 28.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Spacer(modifier = Modifier.height(10.dp))

                            if (banner.badgeText.isNotBlank()) {
                                Surface(
                                    shape = RoundedCornerShape(999.dp),
                                    color = accent.copy(alpha = 0.22f),
                                    border = BorderStroke(1.dp, accent)
                                ) {
                                    Text(
                                        text = banner.badgeText.uppercase(),
                                        color = accent,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        letterSpacing = 1.sp,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            Text(
                                text = banner.title,
                                color = Color.White,
                                fontSize = 23.sp,
                                lineHeight = 28.sp,
                                fontWeight = FontWeight.ExtraBold,
                                textAlign = TextAlign.Center
                            )

                            if (banner.subtitle.isNotBlank()) {
                                Text(
                                    text = banner.subtitle,
                                    color = Color(0xFFE2E8F0),
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp,
                                    textAlign = TextAlign.Center
                                )
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Surface(
                                onClick = { onBannerAction(banner) },
                                shape = RoundedCornerShape(999.dp),
                                color = Color(0xFF0F172A).copy(alpha = 0.85f),
                                border = BorderStroke(1.2.dp, accent)
                            ) {
                                Text(
                                    text = "${banner.ctaLabel}  →",
                                    color = accent,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                                )
                            }
                        }
                    }
                }

                if (banners.size > 1) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = Color(0xFF1E293B).copy(alpha = 0.9f),
                        modifier = Modifier.padding(bottom = 14.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            repeat(banners.size) { index ->
                                val active = pagerState.currentPage == index
                                Box(
                                    modifier = Modifier
                                        .size(if (active) 8.dp else 6.dp)
                                        .clip(CircleShape)
                                        .background(if (active) Color.White else Color(0xFF64748B))
                                )
                            }
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }

            // Botón cerrar (X) en la esquina superior derecha
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF0F172A).copy(alpha = 0.72f))
                    .clickable(onClick = onDismiss)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Cerrar anuncio",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
