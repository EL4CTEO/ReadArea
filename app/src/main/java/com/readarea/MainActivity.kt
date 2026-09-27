package com.readarea

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readarea.ui.LibraryViewModel
import com.readarea.ui.ReadAreaRoot
import com.readarea.ui.theme.ReadAreaTheme

class MainActivity : ComponentActivity() {
    private val vm: LibraryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { vm.allBooks.value == null }
        enableEdgeToEdge()
        setContent {
            val s by vm.settings.collectAsStateWithLifecycle()
            ReadAreaTheme(themeMode = s.themeMode, dynamic = s.dynamicColor, accent = s.accent) {
                ReadAreaRoot(vm)
            }
        }
    }
}
