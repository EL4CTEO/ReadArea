package com.readarea.reader.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import com.readarea.data.db.BookEntity
import com.readarea.ui.components.BookCover
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.currentWindowDpSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readarea.data.ReaderSettings
import com.readarea.reader.Panel
import com.readarea.reader.ReaderActivity
import com.readarea.reader.ReaderUi
import com.readarea.reader.ReaderViewModel
import com.readarea.reader.ReadingTheme
import com.readarea.reader.ReadingThemes
import com.readarea.reader.ViewCommand
import com.readarea.reader.Viewport
import com.readarea.reader.engine.FixedEngine
import com.readarea.reader.view.FlipMode
import com.readarea.reader.view.PageFlipView
import com.readarea.reader.view.ScrollPageView
import com.readarea.ui.theme.ReaderChromeTheme
import com.readarea.ui.theme.highlightPalette
import com.readarea.ui.components.percent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.readarea.R

@Composable
fun ReaderScreen(
    vm: ReaderViewModel,
    onBack: () -> Unit,
    applyWindow: (ReaderSettings, Float?, Boolean) -> Unit,
    openExternal: (Uri) -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    val sysDark = isSystemInDarkTheme()
    LaunchedEffect(sysDark) { vm.setSystemDark(sysDark) }
    val theme = remember(s, sysDark) { ReadingThemes.resolve(s, sysDark) }
    val chromeOpen = ui.menu || ui.panel != Panel.NONE
    LaunchedEffect(s, ui.brightnessPreview, chromeOpen) { applyWindow(s, ui.brightnessPreview, chromeOpen) }
    BackHandler(enabled = chromeOpen || ui.selection != null || ui.footnote != null || ui.speaking || ui.autoTurn) {
        when {
            ui.selection != null -> vm.clearSelection()
            ui.footnote != null -> vm.dismissFootnote()
            ui.panel != Panel.NONE -> vm.closePanel()
            ui.menu -> vm.toggleMenu(false)
            ui.speaking -> vm.stopTts()
            ui.autoTurn -> vm.stopAutoTurn()
        }
    }

    ReaderChromeTheme(theme) {
        val adaptive = currentWindowAdaptiveInfo()
        val size = currentWindowDpSize()
        val density = LocalDensity.current
        val posture = adaptive.windowPosture
        val hinge = posture.hingeList.firstOrNull()
        val verticalHinge = hinge?.takeIf { it.isVertical }
        val tabletop = posture.isTabletop && hinge != null && !hinge.isVertical
        val wide = size.width >= 600.dp && size.width > size.height * 1.05f
        val columns = when (s.spread) {
            "off" -> 1
            "on" -> if (size.width >= 480.dp) 2 else 1
            else -> if (wide || verticalHinge != null) 2 else 1
        }.let { if (s.pageAnim == "scroll" && !ui.vertical) 1 else it }
        val hingeGap = verticalHinge?.let { if (it.isOccluding || it.isSeparating) it.bounds.width else 0f } ?: 0f

        Box(Modifier.fillMaxSize().background(Color(theme.background))) {
            if (tabletop) {
                val topDp = with(density) { hinge.bounds.top.toDp() }
                val hingeDp = with(density) { hinge.bounds.height.toDp() }
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxWidth().height(topDp)) {
                        PageSurface(vm, ui, s, theme, 1, 0f, false, Modifier.fillMaxSize())
                        LightOverlay(s, ui.brightnessPreview)
                    }
                    Spacer(Modifier.height(hingeDp))
                    TabletopDeck(vm, ui, Modifier.fillMaxWidth().weight(1f))
                }
            } else {
                PageSurface(vm, ui, s, theme, columns, hingeGap, s.fullscreen, Modifier.fillMaxSize())
                LightOverlay(s, ui.brightnessPreview)
            }

            if (ui.loading) LoadingState(ui.title, ui.book)
            ui.error?.let { ErrorState(it, onBack) }

            AnimatedVisibility(ui.menu && !tabletop, enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
                TopBar(vm, ui, onBack)
            }
            AnimatedVisibility(ui.menu && !tabletop, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
                BottomBar(vm, ui)
            }

            ui.brightnessPreview?.let { BrightnessBadge(it, Modifier.align(Alignment.Center)) }

            AnimatedVisibility(ui.speaking && !ui.menu, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
                SpeechBar(vm, ui, s)
            }
            AnimatedVisibility(ui.autoTurn && !ui.menu, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
                AutoTurnChip(vm, s, ui)
            }
            AnimatedVisibility(ui.jumpBack != null && !ui.menu, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut(), modifier = Modifier.align(Alignment.BottomStart)) {
                Surface(
                    onClick = vm::goBack,
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shadowElevation = 4.dp,
                    modifier = Modifier.navigationBarsPadding().padding(16.dp),
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Rounded.Undo, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.back), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            ui.selection?.let { sel -> SelectionToolbar(vm, sel, ui) }
            ui.footnote?.let { FootnoteCard(it.text, onGo = vm::followFootnote, onClose = vm::dismissFootnote, modifier = Modifier.align(Alignment.BottomCenter)) }
            if (ui.endReached) EndOfBook(vm, ui, onBack, Modifier.align(Alignment.Center))

            PanelHost(vm, ui, s, theme)

            ui.message?.let { msg ->
                if (msg.startsWith("link:")) {
                    val url = msg.removePrefix("link:")
                    AlertDialog(
                        onDismissRequest = vm::dismissMessage,
                        title = { Text(stringResource(R.string.open_link)) },
                        text = { Text(url, maxLines = 4, overflow = TextOverflow.Ellipsis) },
                        confirmButton = { TextButton({ vm.dismissMessage(); openExternal(url.toUri()) }) { Text(stringResource(R.string.open)) } },
                        dismissButton = { TextButton(vm::dismissMessage) { Text(stringResource(R.string.cancel)) } },
                    )
                } else {
                    AlertDialog(
                        onDismissRequest = vm::dismissMessage,
                        text = { Text(msg) },
                        confirmButton = { TextButton(vm::dismissMessage) { Text(stringResource(R.string.ok)) } },
                    )
                }
            }
        }
    }
}

private class ViewHolder {
    var view: android.view.View? = null
}

@Composable
private fun PageSurface(vm: ReaderViewModel, ui: ReaderUi, s: ReaderSettings, theme: ReadingTheme, columns: Int, hingeGap: Float, fullscreen: Boolean, modifier: Modifier) {
    val scroll = s.pageAnim == "scroll" && !ui.vertical
    val density = LocalDensity.current
    val insets = if (fullscreen) WindowInsets.displayCutout else WindowInsets.systemBarsIgnoringVisibility
    val top = insets.getTop(density)
    val bottom = insets.getBottom(density)
    var size by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(size, columns, hingeGap, top, bottom, density) {
        if (size.width > 0 && size.height > 0) vm.onViewport(Viewport(size.width, size.height, top, bottom, columns, hingeGap, density.density, density.fontScale))
    }
    key(scroll) {
        val holder = remember { ViewHolder() }
        AndroidView(
            modifier = modifier.onSizeChanged { size = it },
            factory = { ctx ->
                if (scroll) {
                    ScrollPageView(ctx).apply {
                        callback = vm.scrollCallback
                        setPosition(vm.pos, vm.currentScrollFraction)
                    }
                } else {
                    PageFlipView(ctx).apply { callback = vm.flipCallback }
                }.also { holder.view = it }
            },
            update = { v ->
                when (v) {
                    is PageFlipView -> {
                        v.mode = when (s.pageAnim) {
                            "slide" -> FlipMode.SLIDE
                            "cover" -> FlipMode.COVER
                            "fade" -> FlipMode.FADE
                            "none" -> FlipMode.NONE
                            else -> FlipMode.CURL
                        }
                        v.animSpeed = s.animSpeed
                        v.brightnessGesture = s.brightnessGesture
                        v.pageBackground = theme.background
                        v.selectionColor = (theme.accent and 0x00FFFFFF) or 0x44000000
                        v.handleColor = theme.accent
                        v.spread = columns == 2
                        v.rtl = ui.rtl
                    }
                    is ScrollPageView -> {
                        v.brightnessGesture = s.brightnessGesture
                        v.zoomEnabled = ui.fixed
                        v.setDetailFilter((vm.engine as? FixedEngine)?.drawPaint?.colorFilter)
                    }
                }
            },
        )
        LaunchedEffect(holder) {
            vm.commands.collect { cmd ->
                val v = holder.view
                when (cmd) {
                    ViewCommand.Refresh -> when (v) {
                        is PageFlipView -> v.invalidatePages()
                        else -> v?.invalidate()
                    }
                    ViewCommand.RefreshCurrent -> when (v) {
                        is PageFlipView -> {
                            v.invalidateNeighbors()
                            v.refreshCurrent()
                        }
                        else -> v?.invalidate()
                    }
                    ViewCommand.RefreshNeighbors -> when (v) {
                        is PageFlipView -> {
                            v.invalidateNeighbors()
                            v.invalidate()
                        }
                        else -> v?.invalidate()
                    }
                    is ViewCommand.Flip -> (v as? PageFlipView)?.flip(cmd.forward)
                    is ViewCommand.Selection -> (v as? PageFlipView)?.setSelection(cmd.path, cmd.start, cmd.end)
                    is ViewCommand.ScrollTo -> (v as? ScrollPageView)?.setPosition(cmd.pos, cmd.fraction)
                    is ViewCommand.AutoScroll -> (v as? ScrollPageView)?.setAutoScroll(cmd.speed)
                    is ViewCommand.ScrollBy -> (v as? ScrollPageView)?.smoothScrollBy(cmd.dy)
                }
            }
        }
    }
}

@Composable
private fun LightOverlay(s: ReaderSettings, preview: Float?) {
    val level = preview ?: if (s.brightnessSystem) 1f else s.brightness
    val dim = if (level < ReaderActivity.DIM_THRESHOLD) (ReaderActivity.DIM_THRESHOLD - level) / ReaderActivity.DIM_THRESHOLD * 0.6f else 0f
    val warmth = s.warmth
    if (dim <= 0f && warmth <= 0f) return
    Canvas(Modifier.fillMaxSize()) {
        if (warmth > 0f) drawRect(Color(0xFFFF9B42), alpha = warmth * 0.55f, blendMode = BlendMode.Multiply)
        if (dim > 0f) drawRect(Color.Black, alpha = dim)
    }
}

@Composable
private fun TopBar(vm: ReaderViewModel, ui: ReaderUi, onBack: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.statusBarsPadding().height(60.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(ui.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (ui.chapterTitle.isNotBlank() && ui.chapterTitle != ui.title) {
                    Text(ui.chapterTitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (!ui.fixed) IconButton(onClick = { vm.openPanel(Panel.SEARCH) }) { Icon(Icons.Rounded.Search, stringResource(R.string.search)) }
            IconButton(onClick = vm::toggleBookmark) {
                Icon(
                    if (ui.bookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                    stringResource(R.string.bookmark),
                    tint = if (ui.bookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(onClick = { vm.openPanel(Panel.MORE) }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.more)) }
        }
    }
}

@Composable
private fun BottomBar(vm: ReaderViewModel, ui: ReaderUi) {
    var dragging by remember { mutableStateOf(false) }
    var slider by remember { mutableFloatStateOf(ui.progress) }
    LaunchedEffect(ui.progress) { if (!dragging) slider = ui.progress }
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp).widthIn(max = 720.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (dragging) vm.titleAt(slider) else ui.pageLabel,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (dragging) percent(slider) else if (!ui.fixed && ui.pagesLeftInChapter > 0) pluralStringResource(R.plurals.pages_left_chapter, ui.pagesLeftInChapter, ui.pagesLeftInChapter) else percent(ui.progress),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.chapterStep(false) }) { Icon(Icons.Rounded.SkipPrevious, stringResource(R.string.previous_chapter)) }
                    Slider(
                        value = slider,
                        onValueChange = {
                            dragging = true
                            slider = it
                        },
                        onValueChangeFinished = {
                            dragging = false
                            vm.goToProgress(slider)
                        },
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { vm.chapterStep(true) }) { Icon(Icons.Rounded.SkipNext, stringResource(R.string.next_chapter)) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    ToolButton(Icons.Rounded.FormatListBulleted, stringResource(R.string.tool_contents)) { vm.openPanel(Panel.CONTENTS) }
                    if (!ui.fixed) ToolButton(Icons.Rounded.TextFields, stringResource(R.string.tool_text)) { vm.openPanel(Panel.TYPOGRAPHY) }
                    ToolButton(Icons.Rounded.Palette, stringResource(R.string.theme)) { vm.openPanel(Panel.THEME) }
                    ToolButton(Icons.Rounded.LightMode, stringResource(R.string.tool_light)) { vm.openPanel(Panel.LIGHT) }
                    ToolButton(Icons.Rounded.AutoStories, stringResource(R.string.tool_paging)) { vm.openPanel(Panel.PAGING) }
                    if (ui.ttsAvailable) ToolButton(Icons.Rounded.RecordVoiceOver, stringResource(R.string.tool_listen)) { vm.openPanel(Panel.SPEECH) }
                }
            }
        }
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, Modifier.size(22.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BrightnessBadge(level: Float, modifier: Modifier) {
    Surface(shape = RoundedCornerShape(24.dp), color = Color.Black.copy(alpha = 0.72f), contentColor = Color.White, modifier = modifier) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.WbSunny, null)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.width(120.dp).height(6.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.25f))) {
                Box(Modifier.fillMaxWidth(level.coerceIn(0f, 1f)).height(6.dp).clip(CircleShape).background(Color.White))
            }
            Spacer(Modifier.width(12.dp))
            Text(if (level < ReaderActivity.DIM_THRESHOLD) stringResource(R.string.dim) else percent(level), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun SpeechBar(vm: ReaderViewModel, ui: ReaderUi, s: ReaderSettings) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 8.dp,
        modifier = Modifier.navigationBarsPadding().padding(16.dp).widthIn(max = 480.dp),
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Rounded.VolumeUp, null, Modifier.padding(start = 10.dp).size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Text(
                stringResource(R.string.reading_aloud, s.ttsRate),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 12.dp).weight(1f, fill = false),
            )
            IconButton(onClick = { vm.updateSettings { it.copy(ttsRate = (it.ttsRate - 0.1f).coerceAtLeast(0.5f)) } }) { Text("−", style = MaterialTheme.typography.titleLarge) }
            IconButton(onClick = { vm.updateSettings { it.copy(ttsRate = (it.ttsRate + 0.1f).coerceAtMost(3f)) } }) { Text("+", style = MaterialTheme.typography.titleLarge) }
            IconButton(onClick = vm::toggleSpeech) { Icon(if (ui.ttsPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, stringResource(R.string.play_pause)) }
            IconButton(onClick = vm::stopTts) { Icon(Icons.Rounded.Stop, stringResource(R.string.stop)) }
        }
    }
}

@Composable
private fun AutoTurnChip(vm: ReaderViewModel, s: ReaderSettings, ui: ReaderUi) {
    Surface(
        onClick = vm::stopAutoTurn,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
        modifier = Modifier.navigationBarsPadding().padding(16.dp),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Timer, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(if (s.pageAnim == "scroll" && !ui.vertical) stringResource(R.string.auto_scrolling) else stringResource(R.string.auto_turning, s.autoTurnSeconds), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun SelectionToolbar(vm: ReaderViewModel, sel: com.readarea.reader.SelectionUi, ui: ReaderUi) {
    val context = LocalContext.current
    val resources = LocalResources.current
    var noteDialog by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val margin = with(density) { 12.dp.roundToPx() }
    Layout(
        content = {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, shadowElevation = 10.dp) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(horizontal = 6.dp)) {
                        highlightPalette.forEachIndexed { i, c ->
                            Box(
                                Modifier.size(28.dp).clip(CircleShape).background(c)
                                    .then(if (sel.color == i) Modifier.border(2.5.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                                    .clickable { vm.highlightSelection(i) },
                            )
                        }
                        if (sel.highlightId != null) {
                            IconButton(onClick = { vm.deleteHighlight(sel.highlightId) }) { Icon(Icons.Rounded.Delete, stringResource(R.string.delete_highlight)) }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                        SmallAction(Icons.Rounded.ContentCopy, stringResource(R.string.copy)) {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText(resources.getString(R.string.quote), sel.text))
                            vm.clearSelection()
                        }
                        SmallAction(Icons.Rounded.EditNote, stringResource(R.string.note)) { noteDialog = true }
                        SmallAction(Icons.Rounded.Share, stringResource(R.string.share)) {
                            val quote = "“${sel.text}”\n— ${ui.title}${if (ui.author.isNotBlank()) ", ${ui.author}" else ""}"
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, quote), resources.getString(R.string.share_quote)))
                            vm.clearSelection()
                        }
                        SmallAction(Icons.Rounded.Translate, stringResource(R.string.look_up)) {
                            val intent = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain").putExtra(Intent.EXTRA_PROCESS_TEXT, sel.text).putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
                            runCatching { context.startActivity(Intent.createChooser(intent, resources.getString(R.string.look_up))) }.onFailure {
                                runCatching { context.startActivity(Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, sel.text)) }
                            }
                            vm.clearSelection()
                        }
                        if (ui.ttsAvailable) SmallAction(Icons.AutoMirrored.Rounded.VolumeUp, stringResource(R.string.speak)) { vm.speakFromSelection() }
                        SmallAction(Icons.Rounded.Search, stringResource(R.string.find)) {
                            val q = sel.text.take(80)
                            vm.clearSelection()
                            vm.openPanel(Panel.SEARCH)
                            vm.search(q)
                        }
                    }
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val p = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
        layout(constraints.maxWidth, constraints.maxHeight) {
            val a = sel.anchor
            val x = (a.centerX() - p.width / 2f).toInt().coerceIn(margin, (constraints.maxWidth - p.width - margin).coerceAtLeast(margin))
            val above = a.top.toInt() - p.height - margin
            val below = a.bottom.toInt() + margin * 3
            val y = when {
                above > margin * 4 -> above
                below + p.height < constraints.maxHeight - margin -> below
                else -> (constraints.maxHeight - p.height) / 2
            }
            p.place(x, y)
        }
    }
    if (noteDialog) {
        var text by remember { mutableStateOf(sel.note ?: "") }
        AlertDialog(
            onDismissRequest = { noteDialog = false },
            title = { Text(stringResource(R.string.add_note)) },
            text = {
                Column {
                    Text("“${sel.text.take(160)}${if (sel.text.length > 160) "…" else ""}”", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Serif, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(text, { text = it }, placeholder = { Text(stringResource(R.string.your_thoughts)) }, minLines = 3, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton({
                    noteDialog = false
                    vm.highlightSelection(if (sel.color >= 0) sel.color else 0, text)
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton({ noteDialog = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun SmallAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 9.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun FootnoteCard(text: String, onGo: () -> Unit, onClose: () -> Unit, modifier: Modifier) {
    Surface(
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 16.dp,
        modifier = modifier.fillMaxWidth().widthIn(max = 720.dp),
    ) {
        Column(Modifier.navigationBarsPadding().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.note), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, stringResource(R.string.close)) }
            }
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Serif,
                modifier = Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onGo) { Text(stringResource(R.string.go_to_note)) }
            }
        }
    }
}

@Composable
private fun EndOfBook(vm: ReaderViewModel, ui: ReaderUi, onBack: () -> Unit, modifier: Modifier) {
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 16.dp, modifier = modifier.padding(24.dp).widthIn(max = 420.dp)) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(72.dp)) {
                val c = center
                for (i in 0 until 12) {
                    val ang = Math.toRadians(i * 30.0)
                    val r1 = size.minDimension * 0.28f
                    val r2 = size.minDimension * 0.48f
                    drawLine(
                        Color(0xFFE0A84C),
                        c + androidx.compose.ui.geometry.Offset((r1 * kotlin.math.cos(ang)).toFloat(), (r1 * kotlin.math.sin(ang)).toFloat()),
                        c + androidx.compose.ui.geometry.Offset((r2 * kotlin.math.cos(ang)).toFloat(), (r2 * kotlin.math.sin(ang)).toFloat()),
                        strokeWidth = 5f,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    )
                }
                drawCircle(Color(0xFFE0A84C), radius = size.minDimension * 0.18f)
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.the_end), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.you_finished, ui.title), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            Button(onClick = {
                vm.markFinished()
                onBack()
            }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.mark_finished)) }
            FilledTonalButton(onClick = vm::dismissEnd, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.keep_reading)) }
        }
    }
}

@Composable
private fun LoadingState(title: String, book: BookEntity?) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        if (book != null) {
            BookCover(book, Modifier.width(150.dp), elevation = 14.dp)
            Spacer(Modifier.height(28.dp))
        } else {
            CircularProgressIndicator(strokeWidth = 3.dp)
            Spacer(Modifier.height(16.dp))
        }
        Text(title.ifBlank { stringResource(R.string.opening) }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 32.dp), textAlign = TextAlign.Center)
        Text(stringResource(R.string.preparing_pages), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (book != null) LinearProgressIndicator(Modifier.padding(top = 16.dp).width(120.dp))
    }
}

@Composable
private fun ErrorState(message: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.error_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onBack) { Text(stringResource(R.string.back_to_library)) }
    }
}

@Composable
private fun TabletopDeck(vm: ReaderViewModel, ui: ReaderUi, modifier: Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier) {
        Column(Modifier.navigationBarsPadding().padding(20.dp), verticalArrangement = Arrangement.SpaceEvenly) {
            Text(ui.chapterTitle.ifBlank { ui.title }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(ui.pageLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(value = ui.progress, onValueChange = {}, onValueChangeFinished = null, enabled = false)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                LargeDeckButton(Icons.Rounded.SkipPrevious, stringResource(R.string.previous)) { vm.keyFlip(false) }
                if (ui.ttsAvailable) LargeDeckButton(if (ui.speaking && !ui.ttsPaused) Icons.Rounded.Pause else Icons.Rounded.RecordVoiceOver, stringResource(R.string.tool_listen)) { vm.toggleSpeech() }
                LargeDeckButton(Icons.Rounded.Timer, stringResource(R.string.auto)) { vm.toggleAutoTurn() }
                LargeDeckButton(Icons.Rounded.LightMode, stringResource(R.string.tool_light)) { vm.openPanel(Panel.LIGHT) }
                LargeDeckButton(Icons.Rounded.MoreVert, stringResource(R.string.menu)) { vm.openPanel(Panel.MORE) }
                LargeDeckButton(Icons.Rounded.SkipNext, stringResource(R.string.next)) { vm.keyFlip(true) }
            }
        }
    }
}

@Composable
private fun LargeDeckButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(onClick = onClick, shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(56.dp).shadow(0.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, label) }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
}

@Composable
internal fun Divider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
