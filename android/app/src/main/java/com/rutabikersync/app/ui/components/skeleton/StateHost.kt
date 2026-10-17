package com.rutabikersync.app.ui.components.skeleton

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rutabikersync.app.R
import com.rutabikersync.app.ui.theme.BikerColors

/**
 * Host único de estado de pantalla según SKELETONS.md §5 y §8.
 * Gestiona retraso de 150 ms, mínimo visible de 400 ms, aviso de 3 s y timeout de 8 s,
 * además de estados Content (con banda Stale si aplica), Empty, Error y Offline.
 */
@Composable
fun <T> StateHost(
    state: UiState<T>,
    skeleton: @Composable () -> Unit,
    empty: @Composable () -> Unit,
    onRetry: () -> Unit,
    onRenewSubscription: (() -> Unit)? = null,
    content: @Composable (T, stale: Boolean) -> Unit
) {
    val delayedState by rememberDelayedSkeletonState(isLoading = state is UiState.Loading)

    if (state is UiState.Loading && delayedState.timedOut) {
        ErrorView(
            kind = ErrorKind.NETWORK,
            detail = null,
            onRetry = onRetry,
            onRenewSubscription = onRenewSubscription
        )
        return
    }

    when (state) {
        is UiState.Loading -> {
            if (delayedState.showSkeleton) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SkeletonScope {
                        skeleton()
                    }
                    if (delayedState.showSlowHint) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(id = R.string.skeleton_slow_hint),
                            color = BikerColors.TextSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                }
            }
        }
        is UiState.Content -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { liveRegion = LiveRegionMode.Polite }
            ) {
                if (state.stale) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                BikerColors.SurfaceDark,
                                RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = null,
                            tint = BikerColors.StatusYellow,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(id = R.string.state_stale_banner),
                            color = BikerColors.StatusYellow,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                content(state.data, state.stale)
            }
        }
        UiState.Empty -> empty()
        is UiState.Error -> ErrorView(
            kind = state.kind,
            detail = state.detail,
            onRetry = onRetry,
            onRenewSubscription = onRenewSubscription
        )
        UiState.Offline -> OfflineView(onRetry = onRetry)
    }
}

@Composable
fun ErrorView(
    kind: ErrorKind,
    detail: String?,
    onRetry: () -> Unit,
    onRenewSubscription: (() -> Unit)? = null
) {
    val (icon, titleRes, subtitleRes) = when (kind) {
        ErrorKind.NETWORK -> Triple(
            Icons.Default.CloudOff,
            R.string.state_error_network_title,
            R.string.state_error_network_desc
        )
        ErrorKind.FORBIDDEN, ErrorKind.UNAUTHORIZED -> Triple(
            Icons.Default.Block,
            R.string.state_error_forbidden_title,
            R.string.state_error_forbidden_desc
        )
        ErrorKind.SUBSCRIPTION_EXPIRED -> Triple(
            Icons.Default.Warning,
            R.string.state_error_sub_expired_title,
            R.string.state_error_sub_expired_desc
        )
        else -> Triple(
            Icons.Default.ErrorOutline,
            R.string.state_error_server_title,
            R.string.state_error_server_desc
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BikerColors.SurfaceDark, RoundedCornerShape(12.dp))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (kind == ErrorKind.SUBSCRIPTION_EXPIRED) BikerColors.StatusYellow else BikerColors.StatusRed,
            modifier = Modifier.size(36.dp)
        )
        Text(
            text = stringResource(id = titleRes),
            color = BikerColors.TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = detail ?: stringResource(id = subtitleRes),
            color = BikerColors.TextSecondary,
            fontSize = 13.sp
        )
        Button(
            onClick = if (kind == ErrorKind.SUBSCRIPTION_EXPIRED && onRenewSubscription != null) {
                onRenewSubscription
            } else {
                onRetry
            },
            modifier = Modifier.heightIn(min = 48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = BikerColors.PrimaryAmber,
                contentColor = BikerColors.BackgroundDark
            )
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(
                    id = if (kind == ErrorKind.SUBSCRIPTION_EXPIRED) {
                        R.string.action_renew_subscription
                    } else {
                        R.string.action_retry
                    }
                ),
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun OfflineView(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BikerColors.SurfaceDark, RoundedCornerShape(12.dp))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = Icons.Default.CloudOff,
            contentDescription = null,
            tint = BikerColors.StatusYellow,
            modifier = Modifier.size(36.dp)
        )
        Text(
            text = stringResource(id = R.string.state_offline_title),
            color = BikerColors.TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = stringResource(id = R.string.state_offline_desc),
            color = BikerColors.TextSecondary,
            fontSize = 13.sp
        )
        Button(
            onClick = onRetry,
            modifier = Modifier.heightIn(min = 48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = BikerColors.PrimaryAmber,
                contentColor = BikerColors.BackgroundDark
            )
        ) {
            Icon(imageVector = Icons.Default.Refresh, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(id = R.string.action_retry),
                fontWeight = FontWeight.Bold
            )
        }
    }
}
