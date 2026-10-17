package com.rutabikersync.app.ui.components.skeleton

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rutabikersync.app.ui.theme.BikerColors
import com.rutabikersync.app.ui.theme.BikerDimens

/**
 * Compuestos reutilizables de Skeleton Screens (SKELETONS.md §4 y §7).
 */
@Composable
fun SkeletonListItem(
    showAvatar: Boolean = true,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showAvatar) {
            SkeletonCircle(size = BikerDimens.AvatarListSize)
            Spacer(modifier = Modifier.width(12.dp))
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(SkeletonTokens.lineGap)
        ) {
            SkeletonLine(widthFraction = 0.6f, height = SkeletonTokens.lineHeightTitle)
            SkeletonLine(widthFraction = 0.4f, height = SkeletonTokens.lineHeightBody)
        }
        Spacer(modifier = Modifier.width(8.dp))
        SkeletonChip()
    }
}

@Composable
fun SkeletonCard(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(BikerColors.SurfaceDark, RoundedCornerShape(SkeletonTokens.radiusMd))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SkeletonCircle(size = 40.dp)
            Spacer(modifier = Modifier.width(12.dp))
            SkeletonLine(widthFraction = 0.5f, height = SkeletonTokens.lineHeightTitle)
        }
        SkeletonLine(widthFraction = 0.9f)
        SkeletonLine(widthFraction = 0.7f)
    }
}

@Composable
fun SkeletonMemberRow(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SkeletonCircle(size = BikerDimens.AvatarListSize)
        Spacer(modifier = Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SkeletonLine(widthFraction = 0.55f, height = SkeletonTokens.lineHeightTitle)
            SkeletonLine(widthFraction = 0.35f, height = SkeletonTokens.lineHeightBody)
        }
        SkeletonChip()
        Spacer(modifier = Modifier.width(8.dp))
        SkeletonCircle(size = 16.dp)
    }
}

@Composable
fun SkeletonRideCard(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(BikerColors.SurfaceDark, RoundedCornerShape(SkeletonTokens.radiusMd))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SkeletonBox(height = 140.dp, radius = SkeletonTokens.radiusMd)
        SkeletonLine(widthFraction = 0.65f, height = SkeletonTokens.lineHeightTitle)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SkeletonChip()
            SkeletonChip()
        }
        SkeletonLine(widthFraction = 0.45f)
    }
}

@Composable
fun SkeletonProfileHeader(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SkeletonCircle(size = 96.dp)
        SkeletonLine(widthFraction = 0.5f, height = SkeletonTokens.lineHeightTitle)
        SkeletonChip()
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(4) {
                SkeletonCircle(size = 40.dp)
            }
        }
    }
}

@Composable
fun SkeletonForm(fields: Int = 5, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        repeat(fields) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SkeletonLine(widthFraction = 0.3f)
                SkeletonBox(height = 56.dp, radius = SkeletonTokens.radiusMd)
            }
        }
    }
}

@Composable
fun SkeletonStatsCard(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(BikerColors.SurfaceDark, RoundedCornerShape(SkeletonTokens.radiusMd))
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        repeat(3) {
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SkeletonLine(widthFraction = 0.6f, height = SkeletonTokens.lineHeightTitle)
                SkeletonLine(widthFraction = 0.8f)
            }
        }
    }
}

@Composable
fun SkeletonVoteCard(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(BikerColors.SurfaceDark, RoundedCornerShape(SkeletonTokens.radiusMd))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SkeletonLine(widthFraction = 0.7f, height = SkeletonTokens.lineHeightTitle)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(3) {
                SkeletonBox(
                    height = 10.dp,
                    radius = SkeletonTokens.radiusSm,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SkeletonBox(height = 44.dp, modifier = Modifier.weight(1f))
            SkeletonBox(height = 44.dp, modifier = Modifier.weight(1f))
        }
    }
}
