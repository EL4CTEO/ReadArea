package com.readarea.desktop.ui.settings

import com.readarea.desktop.App
import com.readarea.desktop.data.ReaderSettings
import com.readarea.desktop.i18n.I18n
import com.readarea.desktop.i18n.tr
import com.readarea.desktop.platform.AppDirs
import com.readarea.desktop.platform.SystemIntegration
import com.readarea.desktop.reader.engine.ReaderFonts
import com.readarea.desktop.ui.MainWindow
import com.readarea.desktop.ui.Screen
import com.readarea.desktop.ui.components.ButtonKind
import com.readarea.desktop.ui.components.Widget
import com.readarea.desktop.ui.components.Card
import com.readarea.desktop.ui.components.Dialogs
import com.readarea.desktop.ui.components.IconButton
import com.readarea.desktop.ui.components.PillButton
import com.readarea.desktop.ui.components.ScrollableColumn
import com.readarea.desktop.ui.components.Segmented
import com.readarea.desktop.ui.components.Switch
import com.readarea.desktop.ui.components.Transparent
import com.readarea.desktop.ui.components.Ui
import com.readarea.desktop.ui.components.pal
import com.readarea.desktop.ui.components.smooth
import com.readarea.desktop.ui.library.BookDetailsDialog
import com.readarea.desktop.ui.theme.Accents
import com.readarea.desktop.ui.theme.AppTheme
import com.readarea.desktop.ui.theme.VectorIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FileDialog
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Ellipse2D
import java.io.File
import javax.swing.BoxLayout
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JScrollPane
import javax.swing.border.EmptyBorder

class SettingsScreen(private val app: App, private val window: MainWindow) : Screen {
    private val column = ScrollableColumn(null).apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val scroll: JScrollPane = Ui.scroll(column)
    private var aboutCard: JComponent? = null
    override val component: JComponent = scroll
    private var building = false
    private val folderList = Transparent(null).apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val fontList = Transparent(null).apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

    init {
        column.border = EmptyBorder(22, 28, 36, 28)
        build()
        app.scope.launch { app.settings.app.collect { if (!building) rebuildFolders() } }
    }


    private fun build() {
        building = true
        column.removeAll()
        val s = app.settings.app.value
        column.add(Ui.headline(tr("nav_settings")).apply { alignmentX = JComponent.LEFT_ALIGNMENT })
        column.add(Ui.gap(18))

        column.add(section("palette", tr("appearance"),
            row(tr("theme"), null, Segmented(listOf(tr("theme_system"), tr("theme_light"), tr("theme_dark")), listOf("system", "light", "dark").indexOf(s.themeMode).coerceAtLeast(0)) { i ->
                app.settings.updateApp { it.copy(themeMode = listOf("system", "light", "dark")[i]) }
                if (i == 0) app.refreshSystemDark()
            }.apply { preferredSize = Dimension(300, 34) }),
            row(tr("accent"), null, accents(s.accent)),
            row(tr("cover_size"), null, Segmented(listOf(tr("covers_small"), tr("covers_medium"), tr("covers_large")), s.coverSize) { i -> app.settings.updateApp { it.copy(coverSize = i) } }.apply { preferredSize = Dimension(330, 34) }),
            row(tr("format_badges"), tr("format_badges_hint"), switch(s.showFormatBadges) { v -> app.settings.updateApp { it.copy(showFormatBadges = v) } }),
        ))

        val langs = listOf("system" to tr("system_default")) + I18n.languages
        val langBox = JComboBox(langs.map { it.second }.toTypedArray()).apply {
            selectedIndex = langs.indexOfFirst { it.first == s.language }.coerceAtLeast(0)
            addActionListener {
                val tag = langs[selectedIndex.coerceAtLeast(0)].first
                if (tag != app.settings.app.value.language) {
                    app.settings.updateApp { it.copy(language = tag) }
                    window.toast(tr("language_restart"))
                }
            }
        }
        column.add(section("language", tr("app_language"), row(tr("language"), tr("language_restart"), langBox)))

        rebuildFolders()
        column.add(section("folder", tr("library_folders"),
            Ui.wrapLabel(tr("library_folders_body_desktop"), 560, 12.5f) { pal.onSurfaceVariant },
            Ui.gap(10), folderList, Ui.gap(8),
            Ui.flow(
                PillButton(tr("add_folder"), "folder", ButtonKind.TONAL, compact = true).apply { addActionListener { app.chooseFolder(window) } },
                PillButton(tr("rescan_folders"), "refresh", ButtonKind.TEXT, compact = true).apply { addActionListener { app.library.rescan(); window.toast(tr("scanning")) } },
                gap = 6,
            ),
            row(tr("watch_folders"), tr("watch_folders_hint"), switch(s.watchFolders) { v -> app.settings.updateApp { it.copy(watchFolders = v) } }),
            row(tr("reopen_last"), tr("reopen_last_hint"), switch(s.reopenLastBook) { v -> app.settings.updateApp { it.copy(reopenLastBook = v) } }),
        ))

        rebuildFonts()
        column.add(section("text", tr("reading"),
            Ui.wrapLabel(tr("reading_settings_hint_desktop"), 560, 12.5f) { pal.onSurfaceVariant },
            Ui.gap(10),
            Ui.label(tr("custom_fonts"), 13f, Font.BOLD),
            Ui.gap(4), fontList, Ui.gap(6),
            Ui.flow(
                PillButton(tr("import_font"), "upload", ButtonKind.TONAL, compact = true).apply { addActionListener { importFont() } },
                PillButton(tr("reset_reading_settings"), "undo", ButtonKind.TEXT, compact = true).apply {
                    addActionListener {
                        val (ok, _) = Dialogs.confirm(window, tr("reset_reading_settings"), tr("reset_reading_body"), tr("reset"))
                        if (ok) app.settings.updateReader { ReaderSettings() }
                    }
                },
                gap = 6,
            ),
        ))

        column.add(speechSection())

        val cache = app.dirs.cache
        val covers = app.dirs.covers
        val usage = Ui.secondary("…", 12.5f)
        app.scope.launch {
            val size = withContext(Dispatchers.IO) { folderSize(covers) + folderSize(cache) + app.dirs.database.length() }
            usage.text = tr("storage_usage", BookDetailsDialog.humanSize(size))
        }
        column.add(section("storage", tr("storage"),
            row(tr("data_folder"), app.dirs.data.path, PillButton(tr("open"), "reveal", ButtonKind.TONAL, compact = true).apply { addActionListener { SystemIntegration.reveal(app.dirs.database) } }),
            Ui.padded(usage, 4, 0, 0, 0),
        ))

        column.add(section("lock", tr("privacy_title"),
            Ui.wrapLabel(tr("about_privacy_desktop"), 560, 13f) { pal.onSurfaceVariant },
        ))

        val about = section("info", tr("about"),
            Ui.hbox(JLabel(VectorIcon("logo", 44)), Ui.gap(12), Ui.vbox(Ui.label("ReadArea", 18f).apply { font = AppTheme.headline(20f) }, Ui.secondary(tr("version_label", app.version), 12.5f))),
            Ui.gap(10),
            Ui.wrapLabel(tr("about_body_desktop"), 560, 12.5f) { pal.onSurfaceVariant },
            Ui.gap(8),
            Ui.flow(
                PillButton(tr("project_website"), "external", ButtonKind.TEXT, compact = true).apply { addActionListener { confirmLink(PROJECT_URL) } },
                PillButton(tr("licenses"), "info", ButtonKind.TEXT, compact = true).apply { addActionListener { Dialogs.message(window, tr("licenses"), LICENSES) } },
                gap = 4,
            ),
        )
        aboutCard = about
        column.add(about)
        column.revalidate()
        column.repaint()
        building = false
    }

    private fun confirmLink(url: String) {
        val (ok, _) = Dialogs.confirm(window, tr("open_link"), url, tr("open"))
        if (ok) SystemIntegration.openLink(url)
    }

    private fun rebuildFolders() {
        folderList.removeAll()
        val folders = app.settings.app.value.folders
        if (folders.isEmpty()) folderList.add(Ui.secondary(tr("no_folders"), 12.5f))
        for (f in folders) {
            val row = Transparent(BorderLayout(10, 0))
            row.alignmentX = JComponent.LEFT_ALIGNMENT
            row.maximumSize = Dimension(Int.MAX_VALUE, 40)
            row.add(JLabel(VectorIcon("folder", 18) { pal.accent }), BorderLayout.WEST)
            row.add(Ui.label(f, 13f).apply { toolTipText = f }, BorderLayout.CENTER)
            row.add(IconButton("close", tr("remove_folder"), 16).apply {
                addActionListener {
                    val (ok, removeBooks) = Dialogs.confirm(window, tr("remove_folder_title"), tr("remove_folder_body", File(f).name), tr("remove_folder"), checkbox = tr("remove_books_too"))
                    if (ok) app.library.removeFolder(f, removeBooks)
                }
            }, BorderLayout.EAST)
            folderList.add(row)
        }
        folderList.revalidate()
        folderList.repaint()
    }

    private fun rebuildFonts() {
        fontList.removeAll()
        val fonts = app.settings.app.value.customFonts
        if (fonts.isEmpty()) fontList.add(Ui.secondary(tr("no_custom_fonts"), 12.5f))
        for (name in fonts) {
            val row = Transparent(BorderLayout(10, 0))
            row.alignmentX = JComponent.LEFT_ALIGNMENT
            row.maximumSize = Dimension(Int.MAX_VALUE, 36)
            val preview = ReaderFonts.loadCustom(File(app.dirs.fonts, name))?.deriveFont(15f)
            row.add(Ui.label(name.substringBeforeLast('.'), 14f).apply { if (preview != null) font = preview }, BorderLayout.CENTER)
            row.add(IconButton("trash", tr("remove"), 16).apply {
                addActionListener {
                    File(app.dirs.fonts, name).takeIf { AppDirs.isInside(app.dirs.fonts, it) }?.delete()
                    app.settings.updateApp { it.copy(customFonts = it.customFonts - name) }
                    app.settings.updateReader { r -> if (r.fontFamily == "file:$name") r.copy(fontFamily = "serif") else r }
                    rebuildFonts()
                }
            }, BorderLayout.EAST)
            fontList.add(row)
        }
        fontList.revalidate()
        fontList.repaint()
    }

    /** Copies a TrueType or OpenType font the reader picks into the app's fonts folder. */
    private fun importFont() {
        val d = FileDialog(window, tr("import_font"), FileDialog.LOAD)
        d.setFilenameFilter { _, n -> n.lowercase().let { it.endsWith(".ttf") || it.endsWith(".otf") } }
        d.isMultipleMode = true
        d.isVisible = true
        val files = d.files?.toList().orEmpty()
        var added = 0
        for (f in files) {
            val safe = f.name.replace(Regex("[^\\p{L}\\p{N} ._()-]"), "_").take(80)
            if (!safe.lowercase().endsWith(".ttf") && !safe.lowercase().endsWith(".otf")) continue
            if (f.length() > 40L * 1024 * 1024 || ReaderFonts.loadCustom(f) == null) continue
            val target = File(app.dirs.fonts, safe)
            if (!AppDirs.isInside(app.dirs.fonts, target)) continue
            runCatching { f.copyTo(target, overwrite = true) }.onSuccess {
                AppDirs.makePrivate(target)
                app.settings.updateApp { s -> s.copy(customFonts = (s.customFonts + safe).distinct()) }
                added++
            }
        }
        if (files.isNotEmpty() && added == 0) window.toast(tr("font_not_supported"))
        rebuildFonts()
    }

    private fun speechSection(): JComponent {
        val engine = app.speech
        if (engine == null) return section("speak", tr("read_aloud"), Ui.wrapLabel(tr("tts_unavailable_desktop"), 560, 12.5f) { pal.onSurfaceVariant })
        val voiceBox = JComboBox(arrayOf(tr("system_default")))
        voiceBox.isEnabled = false
        app.scope.launch {
            val voices = withContext(Dispatchers.IO) { runCatching { engine.voices() }.getOrDefault(emptyList()) }
            voiceBox.removeAllItems()
            voiceBox.addItem(tr("system_default"))
            voices.forEach { voiceBox.addItem(it) }
            val current = app.settings.reader.value.ttsVoice
            voiceBox.selectedIndex = (voices.indexOf(current) + 1).coerceAtLeast(0)
            voiceBox.isEnabled = voices.isNotEmpty()
            voiceBox.addActionListener {
                val v = if (voiceBox.selectedIndex <= 0) "" else voiceBox.selectedItem as String
                app.settings.updateReader { it.copy(ttsVoice = v) }
            }
        }
        val test = PillButton(tr("test_voice"), "speak", ButtonKind.TONAL, compact = true).apply {
            addActionListener {
                val r = app.settings.reader.value
                engine.stop()
                engine.speak(tr("voice_sample"), r.ttsRate, r.ttsVoice) {}
            }
        }
        return section("speak", tr("read_aloud"),
            Ui.wrapLabel(tr("read_aloud_hint_desktop", engine.name), 560, 12.5f) { pal.onSurfaceVariant },
            row(tr("voice_settings"), null, Ui.hbox(voiceBox.apply { preferredSize = Dimension(240, 32) }, Ui.gap(8), test)),
        )
    }

    fun scrollToAbout() {
        val c = aboutCard ?: return
        javax.swing.SwingUtilities.invokeLater { column.scrollRectToVisible(c.bounds) }
    }

    override fun onShow() {
        build()
    }

    private fun section(icon: String, title: String, vararg content: JComponent): JComponent {
        val card = Card(20, BorderLayout(), { pal.surfaceContainer })
        card.border = EmptyBorder(18, 22, 18, 22)
        val body = Transparent(null).apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        body.add(Ui.hbox(JLabel(VectorIcon(icon, 20) { pal.accent }), Ui.gap(10), Ui.label(title, 15f, Font.BOLD)).apply { alignmentX = JComponent.LEFT_ALIGNMENT })
        body.add(Ui.gap(12))
        for (c in content) {
            c.alignmentX = JComponent.LEFT_ALIGNMENT
            body.add(c)
        }
        card.add(body)
        val wrap = Transparent(BorderLayout())
        wrap.add(card)
        wrap.border = EmptyBorder(0, 0, 16, 0)
        wrap.alignmentX = JComponent.LEFT_ALIGNMENT
        wrap.maximumSize = Dimension(820, Int.MAX_VALUE)
        return wrap
    }

    private fun row(label: String, hint: String?, control: JComponent): JComponent {
        val left = Ui.vbox(Ui.label(label, 13.5f))
        if (hint != null) left.add(Ui.label("<html><div style='width:360px'>${Ui.escape(hint)}</div></html>", 12f) { pal.onSurfaceVariant })
        val p = Transparent(BorderLayout(16, 0))
        p.border = EmptyBorder(8, 0, 8, 0)
        p.add(left, BorderLayout.CENTER)
        p.add(Transparent(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply { add(control) }, BorderLayout.EAST)
        p.maximumSize = Dimension(Int.MAX_VALUE, p.preferredSize.height + 8)
        return p
    }

    private fun switch(on: Boolean, onChange: (Boolean) -> Unit): JComponent = Switch(on).apply { addActionListener { onChange(isSelected) } }

    private fun accents(selected: Int): JComponent {
        val p = Transparent(FlowLayout(FlowLayout.RIGHT, 6, 0))
        var sel = selected
        val swatches = ArrayList<JComponent>()
        Accents.colors.forEachIndexed { i, rgb ->
            val sw = object : Widget() {
                init {
                    preferredSize = Dimension(28, 28)
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    toolTipText = Accents.names[i]
                    getAccessibleContext().accessibleName = Accents.names[i]
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent) {
                            sel = i
                            swatches.forEach { it.repaint() }
                            app.settings.updateApp { it.copy(accent = i) }
                        }
                    })
                }

                override fun paintComponent(g0: Graphics) {
                    val g = g0.create().smooth()
                    g.color = Color(rgb)
                    g.fill(Ellipse2D.Float(3f, 3f, 22f, 22f))
                    if (i == sel) {
                        g.color = pal.onSurface
                        g.stroke = java.awt.BasicStroke(2f)
                        g.draw(Ellipse2D.Float(1f, 1f, 26f, 26f))
                    }
                    g.dispose()
                }
            }
            swatches.add(sw)
            p.add(sw)
        }
        return p
    }

    private fun folderSize(dir: File): Long = dir.walkTopDown().maxDepth(3).filter { it.isFile }.sumOf { it.length() }

    companion object {
        const val PROJECT_URL = "https://github.com/EL4CTEO/ReadArea"

        private val LICENSES = """
            ReadArea is open source under the MIT license.

            It is built with:
            • Kotlin and kotlinx (Apache License 2.0)
            • FlatLaf (Apache License 2.0)
            • Apache PDFBox (Apache License 2.0)
            • SQLite JDBC by Xerial (Apache License 2.0) and SQLite (public domain)
            • TwelveMonkeys ImageIO (BSD 3-Clause)
        """.trimIndent()
    }
}
