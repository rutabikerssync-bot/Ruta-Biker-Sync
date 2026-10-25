package com.rutabikersync.app.ui.navigation

import androidx.compose.runtime.Composable
import com.rutabikersync.app.feature.fieldtest.FieldTestScreen

@Composable
fun FieldTestHost(onOpenLiveMap: () -> Unit) {
    FieldTestScreen(onOpenLiveMap = onOpenLiveMap)
}
