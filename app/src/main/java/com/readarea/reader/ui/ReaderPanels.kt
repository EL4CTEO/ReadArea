package com.readarea.reader.ui

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatAlignLeft
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FormatAlignJustify
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readarea.app
import com.readarea.data.ReaderSettings
import com.readarea.reader.Panel
import com.readarea.reader.ReaderFonts
import com.readarea.reader.ReaderUi
import com.readarea.reader.ReaderViewModel
import com.readarea.reader.ReadingTheme
import com.readarea.reader.ReadingThemes
import com.readarea.ui.theme.highlightPalette
import com.readarea.ui.components.percent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.readarea.R

@Composable
fun PanelHost(vm: ReaderViewModel, ui: ReaderUi, s: ReaderSettings, theme: ReadingTheme, night: Boolean) {
    if (ui.panel == Panel.NONE) return
    val tall = ui.panel == Panel.CONTENTS || ui.panel == Panel.SEARCH
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = vm::closePanel,
        sheetState = state,
        sheetMaxWidth = 720.dp,
        scrimColor = Color.Black.copy(alpha = if (tall) 0.32f else 0.08f),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        val mod = if (tall) Modifier.fillMaxHeight(0.88f) else Modifier
        Column(mod.fillMaxWidth()) {
            when (ui.panel) {
                Panel.CONTENTS -> ContentsPanel(vm, ui)
                Panel.TYPOGRAPHY -> TypographyPanel(vm, s, ui)
                Panel.THEME -> ThemePanel(vm, s, theme, night)
                Panel.LIGHT -> LightPanel(vm, s)
                Panel.PAGING -> PagingPanel(vm, s, ui)
                Panel.SEARCH -> SearchPanel(vm, ui)
                Panel.SPEECH -> SpeechPanel(vm, s, ui)
                Panel.MORE -> MorePanel(vm, ui, s)
                Panel.NONE -> {}
            }
        }
    }
}

fun themeLabel(id: String): Int = when (id) {
    "day" -> R.string.theme_day
    "paper" -> R.string.theme_paper
    "sepia" -> R.string.theme_sepia
    "mint" -> R.string.theme_mint
    "sky" -> R.string.theme_sky
    "dusk" -> R.string.theme_dusk
    "night" -> R.string.theme_night
    "amoled" -> R.string.theme_amoled
    else -> R.string.theme_custom
}

fun fontLabel(key: String): Int = when (key) {
    "serif" -> R.string.font_serif
    "sans" -> R.string.font_sans
    "light" -> R.string.font_light
    "condensed" -> R.string.font_condensed
    "medium" -> R.string.font_medium
    "serif-mono" -> R.string.font_typewriter
    "mono" -> R.string.font_mono
    "casual" -> R.string.font_casual
    else -> R.string.font_cursive
}

@Composable
private fun PanelBody(content: @Composable () -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 24.dp).navigationBarsPadding()) { content() }
}

@Composable
private fun ContentsPanel(vm: ReaderViewModel, ui: ReaderUi) {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf(stringResource(R.string.tool_contents), stringResource(R.string.tab_bookmarks, ui.bookmarks.size), stringResource(R.string.tab_notes, ui.highlights.size))
    PrimaryTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.surface) {
        tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, maxLines = 1) }) }
    }
    when (tab) {
        0 -> if (ui.fixed || ui.toc.isEmpty()) GoToPage(vm, ui) else TocList(vm, ui)
        1 -> BookmarkList(vm, ui)
        else -> NotesList(vm, ui)
    }
}

@Composable
private fun TocList(vm: ReaderViewModel, ui: ReaderUi) {
    val current = ui.toc.indexOfLast { it.chapter <= ui.chapter && it.title == ui.chapterTitle }.let { if (it < 0) ui.toc.indexOfLast { t -> t.chapter <= ui.chapter } else it }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (current - 3).coerceAtLeast(0))
    LazyColumn(state = listState, contentPadding = PaddingValues(vertical = 8.dp)) {
        itemsIndexed(ui.toc) { i, item ->
            val selected = i == current
            Row(
                Modifier.fillMaxWidth()
                    .clickable {
                        vm.closePanel()
                        vm.toggleMenu(false)
                        val te = vm.engine as? com.readarea.reader.engine.TextEngine
                        vm.goTo(item.chapter, item.anchor?.let { te?.anchors(item.chapter)?.get(it) } ?: 0)
                    }
                    .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent)
                    .padding(start = (22 + item.depth * 18).dp, end = 22.dp, top = 13.dp, bottom = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    item.title,
                    style = if (item.depth == 0) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (item.depth == 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (selected) Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
            }
        }
    }
}

@Composable
private fun GoToPage(vm: ReaderViewModel, ui: ReaderUi) {
    val total = vm.engine?.let { e -> if (e.fixed) e.pageCount(0) else e.totalPages() } ?: 0
    var text by remember { mutableStateOf("") }
    PanelBody {
        SectionTitle(stringResource(R.string.go_to))
        OutlinedTextField(
            value = text,
            onValueChange = { v -> text = v.filter { it.isDigit() }.take(6) },
            label = { Text(if (ui.fixed && total > 0) stringResource(R.string.page_range, total) else stringResource(R.string.percent_range)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        FilledTonalButton(onClick = {
            val n = text.toIntOrNull() ?: return@FilledTonalButton
            vm.closePanel()
            vm.toggleMenu(false)
            if (ui.fixed && total > 0) vm.goTo(0, (n - 1).coerceIn(0, total - 1)) else vm.goToProgress(n.coerceIn(0, 100) / 100f)
        }) { Text(stringResource(R.string.go)) }
        SectionTitle(stringResource(R.string.quick_jump))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0, 10, 25, 50, 75, 90).forEach { p ->
                FilterChip(selected = false, onClick = {
                    vm.closePanel()
                    vm.toggleMenu(false)
                    vm.goToProgress(p / 100f)
                }, label = { Text("$p%") })
            }
        }
    }
}

@Composable
private fun BookmarkList(vm: ReaderViewModel, ui: ReaderUi) {
    if (ui.bookmarks.isEmpty()) {
        EmptyNote(stringResource(R.string.no_bookmarks), stringResource(R.string.no_bookmarks_body))
        return
    }
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        items(ui.bookmarks, key = { it.id }) { b ->
            ListItem(
                leadingContent = { Icon(Icons.Rounded.Bookmark, null, tint = MaterialTheme.colorScheme.primary) },
                headlineContent = { Text(b.snippet.ifBlank { stringResource(R.string.bookmark) }, maxLines = 2, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Serif) },
                supportingContent = { Text("${b.chapterTitle.ifBlank { stringResource(R.string.chapter_number, b.chapter + 1) }} · ${percent(b.progress)}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingContent = { IconButton(onClick = { vm.deleteBookmark(b.id) }) { Icon(Icons.Rounded.Delete, stringResource(R.string.delete)) } },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable {
                    vm.closePanel()
                    vm.toggleMenu(false)
                    vm.goTo(b.chapter, b.offset)
                },
            )
        }
    }
}

@Composable
private fun NotesList(vm: ReaderViewModel, ui: ReaderUi) {
    val context = LocalContext.current
    val resources = LocalResources.current
    if (ui.highlights.isEmpty()) {
        EmptyNote(stringResource(R.string.no_highlights), stringResource(R.string.no_highlights_short))
        return
    }
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = {
                    val md = buildString {
                        append("# ").append(ui.title).append('\n')
                        if (ui.author.isNotBlank()) append("*").append(ui.author).append("*\n")
                        append('\n')
                        ui.highlights.forEach { h ->
                            append("> ").append(h.text.replace("\n", "\n> ")).append("\n\n")
                            h.note?.let { append(it).append("\n\n") }
                            append("— ").append(h.chapterTitle).append(", ").append((h.progress * 100).toInt()).append("%\n\n")
                        }
                    }
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/markdown").putExtra(Intent.EXTRA_SUBJECT, resources.getString(R.string.notes_subject, ui.title)).putExtra(Intent.EXTRA_TEXT, md), resources.getString(R.string.export_notes)))
                }) {
                    Icon(Icons.Rounded.Share, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.export))
                }
            }
        }
        items(ui.highlights, key = { it.id }) { h ->
            Row(
                Modifier.fillMaxWidth().clickable {
                    vm.closePanel()
                    vm.toggleMenu(false)
                    vm.goTo(h.chapter, h.start)
                }.padding(horizontal = 22.dp, vertical = 12.dp),
            ) {
                Box(Modifier.width(4.dp).height(44.dp).clip(RoundedCornerShape(2.dp)).background(highlightPalette.getOrElse(h.color) { highlightPalette[0] }))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(h.text, maxLines = 4, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Serif, style = MaterialTheme.typography.bodyMedium)
                    h.note?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    Text("${h.chapterTitle} · ${percent(h.progress)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                IconButton(onClick = { vm.deleteHighlight(h.id) }) { Icon(Icons.Rounded.Delete, stringResource(R.string.delete), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun EmptyNote(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    format: (Float) -> String,
    onCommit: (Float) -> Unit,
) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Column(Modifier.padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(format(local), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = local, onValueChange = { local = it }, onValueChangeFinished = { onCommit(local) }, valueRange = range, steps = steps)
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onChange(!checked) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (v, label) ->
            SegmentedButton(selected = v == selected, onClick = { onSelect(v) }, shape = SegmentedButtonDefaults.itemShape(i, options.size), label = { Text(label, maxLines = 1, fontSize = 13.sp) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TypographyPanel(vm: ReaderViewModel, s: ReaderSettings, ui: ReaderUi) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val appSettings by context.app.settings.app.collectAsStateWithLifecycle(com.readarea.data.AppSettings())
    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val path = withContext(Dispatchers.IO) {
                runCatching {
                    val name = com.readarea.data.SafeFiles.fileName(uri.lastPathSegment?.substringAfterLast(':')?.replace(Regex("[^A-Za-z0-9._ -]"), "_"), "font.ttf")
                    val dir = File(context.filesDir, "fonts").apply { mkdirs() }
                    val out = File(dir, if (name.contains('.')) name else "$name.ttf")
                    check(com.readarea.data.SafeFiles.inside(dir, out))
                    context.contentResolver.openInputStream(uri)?.use { i -> out.outputStream().use { i.copyTo(it) } }
                    Typeface.createFromFile(out)
                    "file:${out.absolutePath}"
                }.getOrNull()
            }
            if (path != null) {
                context.app.settings.updateApp { it.copy(customFonts = (it.customFonts + path).distinct()) }
                vm.updateSettings { it.copy(fontFamily = path) }
            }
        }
    }
    PanelBody {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            FilledTonalIconButton(onClick = { vm.updateSettings { it.copy(fontSize = (it.fontSize - 1f).coerceAtLeast(10f)) } }) { Icon(Icons.Rounded.Remove, stringResource(R.string.smaller)) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${s.fontSize.toInt()}", style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.font_size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilledTonalIconButton(onClick = { vm.updateSettings { it.copy(fontSize = (it.fontSize + 1f).coerceAtMost(48f)) } }) { Icon(Icons.Rounded.Add, stringResource(R.string.larger)) }
        }
        SectionTitle(stringResource(R.string.typeface))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val all = ReaderFonts.builtIn.map { it.key to stringResource(fontLabel(it.key)) } + appSettings.customFonts.map { it to ReaderFonts.label(it) }
            all.forEach { (key, label) ->
                val selected = s.fontFamily == key
                val family = remember(key) { FontFamily(ReaderFonts.base(key)) }
                Surface(
                    onClick = { vm.updateSettings { it.copy(fontFamily = key) } },
                    shape = RoundedCornerShape(14.dp),
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Aa", fontFamily = family, fontSize = 22.sp)
                        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
            }
            OutlinedButton(onClick = { fontPicker.launch(arrayOf("font/*", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream", "*/*")) }, modifier = Modifier.height(64.dp)) {
                Icon(Icons.Rounded.Add, null)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.import_font))
            }
        }
        if (Build.VERSION.SDK_INT >= 28) {
            LabeledSlider(stringResource(R.string.weight), s.fontWeight.toFloat(), 200f..800f, 11, { it.toInt().toString() }) { v -> vm.updateSettings { it.copy(fontWeight = (v / 50).toInt() * 50) } }
        }
        SectionTitle(stringResource(R.string.spacing))
        LabeledSlider(stringResource(R.string.line_spacing), s.lineSpacing, 1.0f..2.4f, 13, { "%.1f".format(it) }) { v -> vm.updateSettings { it.copy(lineSpacing = (v * 10).toInt() / 10f) } }
        LabeledSlider(stringResource(R.string.paragraph_spacing), s.paragraphSpacing, 0f..1.5f, 14, { "%.1f".format(it) }) { v -> vm.updateSettings { it.copy(paragraphSpacing = (v * 10).toInt() / 10f) } }
        LabeledSlider(stringResource(R.string.first_line_indent), s.indent, 0f..3f, 11, { "%.1f".format(it) }) { v -> vm.updateSettings { it.copy(indent = (v * 4).toInt() / 4f) } }
        LabeledSlider(stringResource(R.string.letter_spacing), s.letterSpacing, -0.05f..0.15f, 19, { "%.2f".format(it) }) { v -> vm.updateSettings { it.copy(letterSpacing = (v * 100).toInt() / 100f) } }
        LabeledSlider(stringResource(R.string.side_margins), s.marginH.toFloat(), 4f..64f, 14, { "${it.toInt()}" }) { v -> vm.updateSettings { it.copy(marginH = v.toInt()) } }
        LabeledSlider(stringResource(R.string.vertical_margins), s.marginV.toFloat(), 4f..64f, 14, { "${it.toInt()}" }) { v -> vm.updateSettings { it.copy(marginV = v.toInt()) } }
        if (ui.bookCjk || s.writingMode != "auto") {
            SectionTitle(stringResource(R.string.writing_mode))
            Segmented(listOf("auto" to stringResource(R.string.auto), "horizontal" to stringResource(R.string.writing_horizontal), "vertical" to stringResource(R.string.writing_vertical)), s.writingMode) { v -> vm.updateSettings { it.copy(writingMode = v) } }
            Text(stringResource(R.string.writing_mode_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
        SectionTitle(stringResource(R.string.layout))
        Segmented(listOf(true to stringResource(R.string.justified), false to stringResource(if (ui.vertical) R.string.top_aligned else R.string.left_aligned)), s.justify) { v -> vm.updateSettings { it.copy(justify = v) } }
        Spacer(Modifier.height(8.dp))
        SwitchRow(stringResource(R.string.hyphenation), stringResource(R.string.hyphenation_hint), s.hyphenation) { v -> vm.updateSettings { it.copy(hyphenation = v) } }
        SwitchRow(stringResource(R.string.publisher_styles), stringResource(R.string.publisher_styles_hint), s.publisherStyles) { v -> vm.updateSettings { it.copy(publisherStyles = v) } }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = {
            vm.updateSettings { cur ->
                val d = ReaderSettings()
                cur.copy(fontFamily = d.fontFamily, fontSize = d.fontSize, fontWeight = d.fontWeight, lineSpacing = d.lineSpacing, paragraphSpacing = d.paragraphSpacing, indent = d.indent, marginH = d.marginH, marginV = d.marginV, justify = d.justify, hyphenation = d.hyphenation, letterSpacing = d.letterSpacing, publisherStyles = d.publisherStyles)
            }
        }) { Text(stringResource(R.string.reset_text_settings)) }
    }
}

private val paperTones = listOf(0xFFFFFFFF, 0xFFFAF6EE, 0xFFF4EEDC, 0xFFEEDFC2, 0xFFE3EDE4, 0xFFE4ECF4, 0xFFF3E6EC, 0xFF2B2B2E, 0xFF1D2330, 0xFF121212, 0xFF000000).map { it.toInt() }
private val inkTones = listOf(0xFF000000, 0xFF1F1F1F, 0xFF2D2A26, 0xFF4A3928, 0xFF22332A, 0xFF1E2A3A, 0xFF8A8A8A, 0xFFC4BDB2, 0xFFD8DEE9, 0xFFE8E2D4, 0xFFFFFFFF).map { it.toInt() }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemePanel(vm: ReaderViewModel, s: ReaderSettings, current: ReadingTheme, night: Boolean) {
    val nightActive = night && s.autoNight
    val pick: (String) -> Unit = { id ->
        vm.updateSettings { cur ->
            val dark = ReadingThemes.all.firstOrNull { it.id == id }?.dark == true
            when {
                nightActive && dark -> cur.copy(nightTheme = id)
                nightActive -> cur.copy(theme = id, autoNight = false)
                else -> cur.copy(theme = id)
            }
        }
    }
    PanelBody {
        SectionTitle(stringResource(R.string.reading_theme))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ReadingThemes.all.forEach { t -> ThemeSwatch(stringResource(themeLabel(t.id)), t.background, t.text, current.id == t.id) { pick(t.id) } }
            ThemeSwatch(stringResource(R.string.theme_custom), s.customBg, s.customFg, current.id == "custom") { pick("custom") }
        }
        if (current.id == "custom") {
            SectionTitle(stringResource(R.string.page_color))
            ColorRow(paperTones, s.customBg) { c -> vm.updateSettings { it.copy(customBg = c) } }
            SectionTitle(stringResource(R.string.text_color))
            ColorRow(inkTones, s.customFg) { c -> vm.updateSettings { it.copy(customFg = c) } }
        }
        Spacer(Modifier.height(8.dp))
        SwitchRow(stringResource(R.string.paper_texture), stringResource(R.string.paper_texture_hint), s.texture) { v -> vm.updateSettings { it.copy(texture = v) } }
        SwitchRow(stringResource(R.string.follow_system_dark), stringResource(R.string.follow_system_dark_hint), s.autoNight) { v -> vm.updateSettings { it.copy(autoNight = v) } }
        if (s.autoNight) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                ReadingThemes.all.filter { it.dark }.forEach { t ->
                    FilterChip(selected = s.nightTheme == t.id, onClick = { vm.updateSettings { it.copy(nightTheme = t.id) } }, label = { Text(stringResource(themeLabel(t.id))) })
                }
            }
        }
        if (current.dark) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.amoled_tip), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ThemeSwatch(name: String, bg: Int, fg: Int, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(4.dp)) {
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(Color(bg))
                .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("Aa", color = Color(fg), fontFamily = FontFamily.Serif, fontSize = 18.sp)
        }
        Spacer(Modifier.height(4.dp))
        Text(name, style = MaterialTheme.typography.labelSmall, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun ColorRow(colors: List<Int>, selected: Int, onPick: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        colors.forEach { c ->
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(Color(c))
                    .border(if (c == selected) 3.dp else 1.dp, if (c == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    .clickable { onPick(c) },
                contentAlignment = Alignment.Center,
            ) {
                if (c == selected) Icon(Icons.Rounded.Check, null, tint = if (ReadingThemes.luminance(c) > 0.5) Color.Black else Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LightPanel(vm: ReaderViewModel, s: ReaderSettings) {
    PanelBody {
        val belowMin = stringResource(R.string.below_min)
        val offLabel = stringResource(R.string.off)
        SwitchRow(stringResource(R.string.use_system_brightness), null, s.brightnessSystem) { v -> vm.updateSettings { it.copy(brightnessSystem = v) } }
        LabeledSlider(stringResource(R.string.brightness), s.brightness, 0f..1f, 0, { if (it < com.readarea.reader.ReaderActivity.DIM_THRESHOLD) belowMin else "${(it * 100).toInt()}%" }) { v ->
            vm.updateSettings { it.copy(brightness = v, brightnessSystem = false) }
        }
        Text(stringResource(R.string.dim_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LabeledSlider(stringResource(R.string.warm_light), s.warmth, 0f..1f, 0, { if (it == 0f) offLabel else "${(it * 100).toInt()}%" }) { v -> vm.updateSettings { it.copy(warmth = v) } }
        SwitchRow(stringResource(R.string.brightness_gesture), stringResource(R.string.brightness_gesture_hint), s.brightnessGesture) { v -> vm.updateSettings { it.copy(brightnessGesture = v) } }
        SectionTitle(stringResource(R.string.keep_awake))
        Text(stringResource(R.string.keep_awake_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to stringResource(R.string.theme_system), 1 to stringResource(R.string.duration_minutes, 1), 2 to stringResource(R.string.duration_minutes, 2), 5 to stringResource(R.string.duration_minutes, 5), 10 to stringResource(R.string.duration_minutes, 10), 15 to stringResource(R.string.duration_minutes, 15), 30 to stringResource(R.string.duration_minutes, 30), -1 to stringResource(R.string.always)).forEach { (v, l) ->
                FilterChip(selected = s.screenTimeoutMin == v, onClick = { vm.updateSettings { it.copy(screenTimeoutMin = v) } }, label = { Text(l) })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PagingPanel(vm: ReaderViewModel, s: ReaderSettings, ui: ReaderUi) {
    val scroll = s.pageAnim == "scroll" && !ui.vertical
    PanelBody {
        SectionTitle(stringResource(R.string.page_turn))
        SwitchRow(stringResource(R.string.realistic_curl), stringResource(R.string.realistic_curl_hint), s.pageAnim == "curl") { v -> vm.updateSettings { it.copy(pageAnim = if (v) "curl" else "slide") } }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("curl" to stringResource(R.string.anim_curl), "slide" to stringResource(R.string.anim_slide), "cover" to stringResource(R.string.anim_cover), "fade" to stringResource(R.string.anim_fade), "none" to stringResource(R.string.anim_instant), "scroll" to stringResource(R.string.anim_scroll)).filter { it.first != "scroll" || !ui.vertical }.forEach { (k, l) ->
                FilterChip(selected = s.pageAnim == k, onClick = { vm.updateSettings { it.copy(pageAnim = k) } }, label = { Text(l) })
            }
        }
        if (s.pageAnim != "none" && !scroll) {
            LabeledSlider(stringResource(R.string.animation_speed), s.animSpeed, 0.5f..2f, 5, { "%.2g×".format(it) }) { v -> vm.updateSettings { it.copy(animSpeed = v) } }
        }
        SectionTitle(stringResource(R.string.page_direction))
        Segmented(listOf("auto" to stringResource(R.string.auto), "ltr" to stringResource(R.string.left_to_right), "rtl" to stringResource(R.string.right_to_left)), s.pageDirection) { v -> vm.updateSettings { it.copy(pageDirection = v) } }
        Text(
            stringResource(if (ui.bookRtl) R.string.book_is_rtl else R.string.page_direction_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        SectionTitle(stringResource(R.string.tap_zones))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("sides" to stringResource(R.string.zones_sides), "forward" to stringResource(R.string.zones_forward), "rows" to stringResource(R.string.zones_rows), "off" to stringResource(R.string.zones_off)).forEach { (k, l) ->
                TapZoneCard(k, l, s.tapZones == k) { vm.updateSettings { it.copy(tapZones = k) } }
            }
        }
        Spacer(Modifier.height(8.dp))
        SwitchRow(stringResource(R.string.volume_keys), null, s.volumeKeys) { v -> vm.updateSettings { it.copy(volumeKeys = v) } }
        if (!scroll) {
            SectionTitle(stringResource(R.string.two_page_spread))
            Segmented(listOf("auto" to stringResource(R.string.auto), "on" to stringResource(R.string.always), "off" to stringResource(R.string.off)), s.spread) { v -> vm.updateSettings { it.copy(spread = v) } }
            Text(stringResource(R.string.two_page_spread_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
        SectionTitle(stringResource(R.string.auto_page_turn))
        val secondsFormat = stringResource(R.string.seconds_short)
        LabeledSlider(stringResource(if (scroll) R.string.scroll_every else R.string.turn_every), s.autoTurnSeconds.toFloat(), 5f..120f, 22, { secondsFormat.format(it.toInt()) }) { v -> vm.updateSettings { it.copy(autoTurnSeconds = v.toInt()) } }
        FilledTonalButton(onClick = { vm.closePanel(); vm.toggleAutoTurn() }) {
            Icon(Icons.Rounded.Timer, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (ui.autoTurn) R.string.stop else R.string.start))
        }
        SectionTitle(stringResource(R.string.screen))
        SwitchRow(stringResource(R.string.full_screen), stringResource(R.string.full_screen_hint), s.fullscreen) { v -> vm.updateSettings { it.copy(fullscreen = v) } }
        SwitchRow(stringResource(R.string.chapter_title_top), null, s.showHeader) { v -> vm.updateSettings { it.copy(showHeader = v) } }
        SwitchRow(stringResource(R.string.page_info_bottom), null, s.showFooter) { v -> vm.updateSettings { it.copy(showFooter = v) } }
        if (s.showFooter) {
            SwitchRow(stringResource(R.string.progress_line), null, s.showProgressLine) { v -> vm.updateSettings { it.copy(showProgressLine = v) } }
        }
        SectionTitle(stringResource(R.string.orientation))
        Segmented(listOf("auto" to stringResource(R.string.auto), "portrait" to stringResource(R.string.portrait), "landscape" to stringResource(R.string.landscape)), s.orientation) { v -> vm.updateSettings { it.copy(orientation = v) } }
        if (ui.fixed) {
            SectionTitle(stringResource(R.string.pdf_comics))
            SwitchRow(stringResource(R.string.crop_margins), stringResource(R.string.crop_margins_hint), s.pdfCrop) { v -> vm.updateSettings { it.copy(pdfCrop = v) } }
            SwitchRow(stringResource(R.string.match_theme_colors), stringResource(R.string.match_theme_colors_hint), s.pdfInvert) { v -> vm.updateSettings { it.copy(pdfInvert = v) } }
        }
    }
}

@Composable
private fun TapZoneCard(key: String, label: String, selected: Boolean, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outlineVariant
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(4.dp)) {
        Canvas(Modifier.size(width = 52.dp, height = 78.dp)) {
            val w = size.width
            val h = size.height
            drawRoundRect(if (selected) primary else outline, style = Stroke(width = if (selected) 5f else 3f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(12f))
            val back = primary.copy(alpha = 0.18f)
            val next = primary.copy(alpha = 0.42f)
            val menu = muted.copy(alpha = 0.18f)
            when (key) {
                "sides" -> {
                    drawRect(back, size = androidx.compose.ui.geometry.Size(w * 0.3f, h))
                    drawRect(menu, topLeft = androidx.compose.ui.geometry.Offset(w * 0.3f, 0f), size = androidx.compose.ui.geometry.Size(w * 0.4f, h))
                    drawRect(next, topLeft = androidx.compose.ui.geometry.Offset(w * 0.7f, 0f), size = androidx.compose.ui.geometry.Size(w * 0.3f, h))
                }
                "forward" -> {
                    drawRect(next, size = size)
                    drawRect(back, size = androidx.compose.ui.geometry.Size(w * 0.25f, h))
                    drawRect(menu, topLeft = androidx.compose.ui.geometry.Offset(w * 0.33f, h * 0.3f), size = androidx.compose.ui.geometry.Size(w * 0.34f, h * 0.4f))
                }
                "rows" -> {
                    drawRect(back, size = androidx.compose.ui.geometry.Size(w, h * 0.3f))
                    drawRect(menu, topLeft = androidx.compose.ui.geometry.Offset(0f, h * 0.3f), size = androidx.compose.ui.geometry.Size(w, h * 0.4f))
                    drawRect(next, topLeft = androidx.compose.ui.geometry.Offset(0f, h * 0.7f), size = androidx.compose.ui.geometry.Size(w, h * 0.3f))
                }
                else -> drawRect(menu, size = size)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
    }
}

@Composable
private fun SearchPanel(vm: ReaderViewModel, ui: ReaderUi) {
    var query by remember { mutableStateOf(ui.searchQuery) }
    Column(Modifier.padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                vm.search(it)
            },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = ""; vm.search("") }) { Icon(Icons.Rounded.Close, stringResource(R.string.clear)) } },
            placeholder = { Text(stringResource(R.string.search_in_book)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            when {
                ui.searching -> stringResource(R.string.searching)
                query.length >= 2 -> if (ui.searchResults.size >= 500) stringResource(R.string.results_many, 500) else pluralStringResource(R.plurals.results, ui.searchResults.size, ui.searchResults.size)
                else -> stringResource(R.string.type_two_chars)
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
        )
    }
    val te = vm.engine as? com.readarea.reader.engine.TextEngine
    LazyColumn(Modifier.imePadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(ui.searchResults) { hit ->
            val primary = MaterialTheme.colorScheme.primary
            val snippet = remember(hit) {
                buildAnnotatedString {
                    val t = hit.snippet
                    val a = hit.matchStart.coerceIn(0, t.length)
                    val b = hit.matchEnd.coerceIn(a, t.length)
                    append(t.substring(0, a))
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = primary, background = primary.copy(alpha = 0.14f))) { append(t.substring(a, b)) }
                    append(t.substring(b))
                }
            }
            Column(Modifier.fillMaxWidth().clickable { vm.openSearchHit(hit) }.padding(horizontal = 20.dp, vertical = 10.dp)) {
                Text(te?.chapterTitle(hit.chapter) ?: "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(snippet, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Serif, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SpeechPanel(vm: ReaderViewModel, s: ReaderSettings, ui: ReaderUi) {
    val context = LocalContext.current
    PanelBody {
        SectionTitle(stringResource(R.string.read_aloud))
        Text(stringResource(R.string.read_aloud_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onClick = { vm.closePanel(); vm.startTts() }) {
                Icon(Icons.Rounded.PlayArrow, null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.from_this_page))
            }
            if (ui.speaking) OutlinedButton(onClick = vm::stopTts) {
                Icon(Icons.Rounded.Stop, null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.stop))
            }
        }
        LabeledSlider(stringResource(R.string.speed), s.ttsRate, 0.5f..3f, 24, { "%.1f×".format(it) }) { v -> vm.updateSettings { it.copy(ttsRate = (v * 10).toInt() / 10f) } }
        LabeledSlider(stringResource(R.string.pitch), s.ttsPitch, 0.5f..2f, 14, { "%.1f".format(it) }) { v -> vm.updateSettings { it.copy(ttsPitch = (v * 10).toInt() / 10f) } }
        TextButton(onClick = { runCatching { context.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text(stringResource(R.string.voice_settings)) }
    }
}

@Composable
private fun MorePanel(vm: ReaderViewModel, ui: ReaderUi, s: ReaderSettings) {
    val context = LocalContext.current
    val resources = LocalResources.current
    PanelBody {
        Text(ui.title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        if (ui.author.isNotBlank()) Text(ui.author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Text("${ui.format.label} · ${stringResource(R.string.percent_read, (ui.progress * 100).toInt())} · ${ui.pageLabel}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        MoreRow(Icons.Rounded.Timer, stringResource(if (ui.autoTurn) R.string.stop_auto_turn else R.string.auto_page_turn)) { vm.closePanel(); vm.toggleAutoTurn() }
        if (ui.ttsAvailable) MoreRow(Icons.Rounded.PlayArrow, stringResource(if (ui.speaking) R.string.stop_reading_aloud else R.string.read_aloud)) { vm.closePanel(); if (ui.speaking) vm.stopTts() else vm.startTts() }
        if (!ui.fixed) MoreRow(Icons.Rounded.Search, stringResource(R.string.search_in_book)) { vm.openPanel(Panel.SEARCH) }
        MoreRow(Icons.AutoMirrored.Rounded.FormatAlignLeft, stringResource(R.string.contents_notes)) { vm.openPanel(Panel.CONTENTS) }
        MoreRow(Icons.Rounded.FormatAlignJustify, stringResource(R.string.go_to_page_percent)) { vm.openPanel(Panel.CONTENTS) }
        MoreRow(Icons.Rounded.Share, stringResource(R.string.share_progress)) {
            val text = if (ui.author.isNotBlank()) resources.getString(R.string.share_progress_text_author, (ui.progress * 100).toInt(), ui.title, ui.author) else resources.getString(R.string.share_progress_text, (ui.progress * 100).toInt(), ui.title)
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), resources.getString(R.string.share)))
        }
    }
}

@Composable
private fun MoreRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 14.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(18.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
