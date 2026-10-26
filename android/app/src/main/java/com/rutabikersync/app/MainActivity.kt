package com.rutabikersync.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.rutabikersync.app.ui.navigation.MainAppScaffold
import com.rutabikersync.app.ui.theme.RutaBikerSyncTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var isDarkMode by remember { mutableStateOf(true) }
            RutaBikerSyncTheme(darkTheme = isDarkMode) {
                MainAppScaffold(
                    isDarkMode = isDarkMode,
                    onToggleDarkMode = { isDarkMode = !isDarkMode }
                )
            }
        }
    }
}
