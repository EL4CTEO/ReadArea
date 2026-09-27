package com.readarea.reader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.net.Uri
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Date

class ReaderActivity : ComponentActivity() {
    internal val vm: ReaderViewModel by viewModels()
    private val handler = Handler(Looper.getMainLooper())
    private val releaseScreen = Runnable { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    private var timeoutMin = 5
    private var lastAwake = 0L
    private var openedId = 0L

    private val tickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateClock()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        vm.activityListener = { keepAwake() }
        val restored = savedInstanceState?.getLong(STATE_BOOK, 0L) ?: 0L
        if (restored > 0) {
            openedId = restored
            vm.open(restored)
        } else {
            handleIntent(intent, savedInstanceState == null)
        }
        lifecycleScope.launch {
            vm.ui.map { (it.speaking && !it.ttsPaused) || it.autoTurn }.distinctUntilChanged().collect {
                lastAwake = 0L
                keepAwake()
            }
        }
        setContent {
            ReaderScreen(
                vm = vm,
                onBack = { finish() },
                applyWindow = { s, preview, menu -> applyWindow(s, preview, menu) },
                openExternal = { uri -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) } },
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (openedId > 0) outState.putLong(STATE_BOOK, openedId)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent, true)
    }

    private fun handleIntent(intent: Intent, fresh: Boolean) {
        val id = intent.getLongExtra(EXTRA_BOOK_ID, -1L)
        if (id > 0) {
            openedId = id
            val chapter = intent.getIntExtra(EXTRA_CHAPTER, -1)
            val offset = intent.getIntExtra(EXTRA_OFFSET, 0)
            vm.open(id, if (chapter >= 0 && fresh) chapter to offset else null)
            if (fresh) intent.removeExtra(EXTRA_CHAPTER)
            return
        }
        val data: Uri? = intent.data
        if (data != null && fresh) {
            lifecycleScope.launch {
                val newId = app.library.openExternal(data)
                if (newId != null) {
                    openedId = newId
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
        updateClock()
        lastAwake = 0L
        keepAwake()
    }

    override fun onPause() {
        super.onPause()
        vm.onSessionEnd()
        runCatching { unregisterReceiver(tickReceiver) }
        handler.removeCallbacksAndMessages(null)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun updateClock() {
        vm.deco.clock = DateFormat.getTimeFormat(this).format(Date())
        vm.refreshChrome()
    }

    private fun volumeHandled(code: Int): Boolean {
        val ui = vm.ui.value
        return (code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_UP) &&
            vm.settings.value.volumeKeys && !ui.menu && ui.panel == Panel.NONE && !ui.speaking
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (volumeHandled(keyCode)) {
            vm.volumeFlip(keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
            return true
        }
        if (!vm.ui.value.menu) {
            when (keyCode) {
                KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_SPACE -> {
                    vm.keyFlip(true)
                    return true
                }
                KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_LEFT -> {
                    vm.keyFlip(false)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (volumeHandled(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }

    companion object {
        const val EXTRA_BOOK_ID = "book_id"
        const val EXTRA_CHAPTER = "chapter"
        const val EXTRA_OFFSET = "offset"
        const val DIM_THRESHOLD = 0.15f
        private const val STATE_BOOK = "opened_book"

        fun intent(context: Context, bookId: Long): Intent = Intent(context, ReaderActivity::class.java).putExtra(EXTRA_BOOK_ID, bookId)

        fun intent(context: Context, bookId: Long, chapter: Int, offset: Int): Intent =
            intent(context, bookId).putExtra(EXTRA_CHAPTER, chapter).putExtra(EXTRA_OFFSET, offset)
    }
}
