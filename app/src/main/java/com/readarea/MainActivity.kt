package com.readarea

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.readarea.reader.ReaderActivity
import kotlinx.coroutines.launch
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readarea.ui.LibraryViewModel
import com.readarea.ui.ReadAreaRoot
import com.readarea.ui.theme.ReadAreaTheme

class MainActivity : ComponentActivity() {
    private val vm: LibraryViewModel by viewModels()

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        var decided = savedInstanceState != null || intent?.action != Intent.ACTION_MAIN
        splash.setKeepOnScreenCondition { vm.allBooks.value == null || !decided }
        if (!decided) {
            lifecycleScope.launch {
                vm.resumeTarget()?.let { id -> startActivity(ReaderActivity.intent(this@MainActivity, id)) }
                decided = true
            }
        }
        enableEdgeToEdge()
        setContent {
            val s by vm.settings.collectAsStateWithLifecycle()
            val phoneDark = isSystemInDarkTheme()
            val dark = when (s.themeMode) {
                "light" -> false
                "dark" -> true
                else -> phoneDark
            }
            LaunchedEffect(dark) {
                val clear = android.graphics.Color.TRANSPARENT
                val style = if (dark) SystemBarStyle.dark(clear) else SystemBarStyle.light(clear, clear)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            ReadAreaTheme(themeMode = s.themeMode, dynamic = s.dynamicColor, accent = s.accent) {
                ReadAreaRoot(vm)
            }
        }
    }
}
