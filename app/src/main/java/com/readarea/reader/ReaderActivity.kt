package com.readarea.reader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.readarea.app
import com.readarea.data.ReaderSettings
import com.readarea.reader.ui.ReaderScreen
import kotlinx.coroutines.launch
import java.util.Date

class ReaderActivity : ComponentActivity() {
    private val vm: ReaderViewModel by viewModels()
    private val handler = Handler(Looper.getMainLooper())
    private val releaseScreen = Runnable { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    private var timeoutMin = 5
    private var lastAwake = 0L

    private val tickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateClock()
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateBattery(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        vm.activityListener = { keepAwake() }
        handleIntent(intent, savedInstanceState == null)
        setContent {
            ReaderScreen(
                vm = vm,
                onBack = { finish() },
                applyWindow = { s, preview, menu -> applyWindow(s, preview, menu) },
                openExternal = { uri -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) } },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent, true)
    }

    private fun handleIntent(intent: Intent, fresh: Boolean) {
        val id = intent.getLongExtra(EXTRA_BOOK_ID, -1L)
        if (id > 0) {
            vm.open(id)
            return
        }
        val data: Uri? = intent.data
        if (data != null && fresh) {
            lifecycleScope.launch {
                val newId = app.library.openExternal(data)
                if (newId != null) {
                    getIntent().putExtra(EXTRA_BOOK_ID, newId)
                    vm.open(newId)
                } else {
                    finish()
                }
            }
        } else if (data == null && id <= 0) {
            finish()
        }
    }

    private fun applyWindow(s: ReaderSettings, preview: Float?, menu: Boolean) {
        val level = preview ?: s.brightness
        val attrs = window.attributes
        attrs.screenBrightness = if (preview == null && s.brightnessSystem) {
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        } else {
            if (level >= DIM_THRESHOLD) ((level - DIM_THRESHOLD) / (1f - DIM_THRESHOLD)).coerceIn(0.01f, 1f) else 0.01f
        }
        window.attributes = attrs
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (s.fullscreen && !menu) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        val theme = ReadingThemes.resolve(s, (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES)
        controller.isAppearanceLightStatusBars = !theme.dark
        controller.isAppearanceLightNavigationBars = !theme.dark
        requestedOrientation = when (s.orientation) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        if (timeoutMin != s.screenTimeoutMin) {
            timeoutMin = s.screenTimeoutMin
            lastAwake = 0L
            keepAwake()
        }
    }

    fun keepAwake() {
        val now = System.currentTimeMillis()
        if (now - lastAwake < 2000) return
        lastAwake = now
        handler.removeCallbacks(releaseScreen)
        val ui = vm.ui.value
        val holding = ui.speaking && !ui.ttsPaused || ui.autoTurn
        when {
            timeoutMin == 0 && !holding -> window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            timeoutMin < 0 || holding -> window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else -> {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                handler.postDelayed(releaseScreen, timeoutMin * 60_000L)
            }
        }
        if (holding) handler.postDelayed({ lastAwake = 0L; keepAwake() }, 60_000L)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        keepAwake()
    }

    override fun onResume() {
        super.onResume()
        vm.onSessionStart()
        ContextCompat.registerReceiver(this, tickReceiver, IntentFilter(Intent.ACTION_TIME_TICK), ContextCompat.RECEIVER_NOT_EXPORTED)
        val sticky = ContextCompat.registerReceiver(this, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        sticky?.let { updateBattery(it) }
        updateClock()
        lastAwake = 0L
        keepAwake()
    }

    override fun onPause() {
        super.onPause()
        vm.onSessionEnd()
        runCatching { unregisterReceiver(tickReceiver) }
        runCatching { unregisterReceiver(batteryReceiver) }
        handler.removeCallbacksAndMessages(null)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun updateClock() {
        vm.deco.clock = DateFormat.getTimeFormat(this).format(Date())
        vm.refreshChrome()
    }

    private fun updateBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        if (pct != vm.deco.battery || charging != vm.deco.charging) {
            vm.deco.battery = pct
            vm.deco.charging = charging
            vm.refreshChrome()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_UP) {
            if (vm.settings.value.volumeKeys && !vm.ui.value.menu && vm.ui.value.panel == Panel.NONE && !vm.ui.value.speaking) {
                if (event.action == KeyEvent.ACTION_DOWN) vm.volumeFlip(code == KeyEvent.KEYCODE_VOLUME_DOWN)
                return true
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (code) {
                KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_SPACE -> if (!vm.ui.value.menu) { vm.keyFlip(true); return true }
                KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_LEFT -> if (!vm.ui.value.menu) { vm.keyFlip(false); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        const val EXTRA_BOOK_ID = "book_id"
        const val DIM_THRESHOLD = 0.15f

        fun intent(context: Context, bookId: Long): Intent = Intent(context, ReaderActivity::class.java).putExtra(EXTRA_BOOK_ID, bookId)
    }
}
