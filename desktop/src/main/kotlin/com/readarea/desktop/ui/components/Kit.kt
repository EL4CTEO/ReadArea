package com.readarea.desktop.ui.components

import com.formdev.flatlaf.FlatClientProperties
import com.readarea.desktop.ui.theme.AppTheme
import com.readarea.desktop.ui.theme.VectorIcon
import com.readarea.desktop.ui.theme.alpha
import com.readarea.desktop.ui.theme.lerp
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import javax.swing.AbstractButton
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.JToggleButton
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import javax.swing.border.EmptyBorder

/**
 * Antialiased shapes and smooth image scaling. Text hints are left to the look and feel, which follows
 * the desktop's settings, so text drawn here measures the same as in standard components.
 */
fun Graphics.smooth(): Graphics2D = (this as Graphics2D).also {
    it.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    it.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    it.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    if (it.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING) == RenderingHints.VALUE_TEXT_ANTIALIAS_DEFAULT) {
        it.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    }
}

val pal get() = AppTheme.palette

/** Small layout helpers so screens read top to bottom. */
object Ui {
    fun vbox(vararg items: Component, gap: Int = 0, align: Float = Component.LEFT_ALIGNMENT): JPanel = Transparent().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        items.forEachIndexed { i, c ->
            if (i > 0 && gap > 0) add(Box.createVerticalStrut(gap))
            (c as? JComponent)?.alignmentX = align
            add(c)
        }
    }

    fun hbox(vararg items: Component, gap: Int = 0): JPanel = Transparent().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        items.forEachIndexed { i, c ->
            if (i > 0 && gap > 0) add(Box.createHorizontalStrut(gap))
            (c as? JComponent)?.alignmentY = Component.CENTER_ALIGNMENT
            add(c)
        }
    }

    fun flow(vararg items: Component, gap: Int = 8, align: Int = FlowLayout.LEFT): JPanel = Transparent(FlowLayout(align, gap, gap / 2)).apply {
        items.forEach { add(it) }
    }

    fun glue(): Component = Box.createHorizontalGlue()
    fun vglue(): Component = Box.createVerticalGlue()
    fun gap(w: Int): JComponent = Box.createRigidArea(Dimension(w, w)) as JComponent

    fun label(text: String, size: Float = 13f, style: Int = Font.PLAIN, color: (() -> Color)? = null): JLabel = ThemedLabel(text, color ?: { pal.onSurface }).apply {
        font = AppTheme.ui(size, style)
    }

    fun secondary(text: String, size: Float = 12.5f): JLabel = label(text, size, color = { pal.onSurfaceVariant })

    fun headline(text: String, size: Float = 28f): JLabel = ThemedLabel(text) { pal.onSurface }.apply { font = AppTheme.headline(size) }

    fun wrapLabel(text: String, width: Int, size: Float = 13f, color: (() -> Color)? = null): JLabel = label("<html><div style='width:${width}px'>${escape(text)}</div></html>", size, color = color)

    fun escape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>")

    fun scroll(view: Component, horizontal: Boolean = false): JScrollPane = JScrollPane(view).apply {
        border = BorderFactory.createEmptyBorder()
        viewportBorder = null
        isOpaque = false
        viewport.isOpaque = false
        horizontalScrollBarPolicy = if (horizontal) ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED else ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBar.unitIncrement = 24
        horizontalScrollBar.unitIncrement = 24
    }

    fun padded(c: Component, top: Int, left: Int, bottom: Int, right: Int): JPanel = Transparent(BorderLayout()).apply {
        border = EmptyBorder(top, left, bottom, right)
        add(c)
    }
}

/**
 * Base for custom-painted components. Plain JComponents have no accessibility information, so screen
 * readers would skip them; this gives each one an accessible context with a fitting role.
 */
open class Widget(private val role: javax.accessibility.AccessibleRole = javax.accessibility.AccessibleRole.PANEL) : JComponent() {
    override fun getAccessibleContext(): javax.accessibility.AccessibleContext {
        if (accessibleContext == null) accessibleContext = object : AccessibleJComponent() {
            override fun getAccessibleRole(): javax.accessibility.AccessibleRole = role
        }
        return accessibleContext
    }
}

/** A panel that paints nothing, so the parent's background shows through. */
open class Transparent(layout: java.awt.LayoutManager? = FlowLayout()) : JPanel(layout) {
    init {
        isOpaque = false
    }
}

/** A label whose color comes from the current palette, so it follows theme changes. */
class ThemedLabel(text: String, private val colorOf: () -> Color) : JLabel(text) {
    override fun paintComponent(g: Graphics) {
        foreground = colorOf()
        g.smooth()
        super.paintComponent(g)
    }
}

/** A rounded surface, like a Material card. */
open class Card(private val radius: Int = 16, layout: java.awt.LayoutManager = BorderLayout(), private val fill: () -> Color = { pal.surface }, private val outline: Boolean = false) : JPanel(layout) {
    init {
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create().smooth()
        g2.color = fill()
        val shape = RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), radius * 2f, radius * 2f)
        g2.fill(shape)
        if (outline) {
            g2.color = pal.outlineVariant
            g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, radius * 2f, radius * 2f))
        }
        g2.dispose()
    }
}

enum class ButtonKind { PRIMARY, TONAL, OUTLINE, TEXT, DANGER }

/** A pill button painted from the palette: filled, tonal, outlined or plain text. */
class PillButton(text: String, icon: String? = null, private val kind: ButtonKind = ButtonKind.PRIMARY, private val compact: Boolean = false) : JButton(text) {
    private var hover = false
    private var pressed = false

    init {
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        isRolloverEnabled = true
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        font = AppTheme.ui(if (compact) 12.5f else 13.5f, Font.BOLD)
        border = EmptyBorder(if (compact) 5 else 9, if (compact) 12 else 18, if (compact) 5 else 9, if (compact) 14 else 20)
        iconTextGap = 8
        if (icon != null) this.icon = VectorIcon(icon, if (compact) 16 else 18) { fg() }
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
            override fun mouseExited(e: MouseEvent) { hover = false; pressed = false; repaint() }
            override fun mousePressed(e: MouseEvent) { pressed = true; repaint() }
            override fun mouseReleased(e: MouseEvent) { pressed = false; repaint() }
        })
    }

    private fun fg(): Color = when (kind) {
        ButtonKind.PRIMARY -> pal.onAccent
        ButtonKind.TONAL -> pal.onAccentContainer
        ButtonKind.DANGER -> if (pal.dark) Color(0x601410) else Color.WHITE
        else -> pal.accent
    }

    private fun bg(): Color? = when (kind) {
        ButtonKind.PRIMARY -> pal.accent
        ButtonKind.TONAL -> pal.accentContainer
        ButtonKind.DANGER -> pal.danger
        else -> null
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create().smooth()
        val shape = RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, height.toFloat(), height.toFloat())
        val base = bg()
        if (base != null) {
            g2.color = when {
                !isEnabled -> base.alpha(90)
                pressed -> lerp(base, if (pal.dark) Color.WHITE else Color.BLACK, 0.16f)
                hover -> lerp(base, if (pal.dark) Color.WHITE else Color.BLACK, 0.08f)
                else -> base
            }
            g2.fill(shape)
        } else {
            if (hover || pressed) {
                g2.color = pal.accent.alpha(if (pressed) 40 else 22)
                g2.fill(shape)
            }
            if (kind == ButtonKind.OUTLINE) {
                g2.color = pal.outline.alpha(150)
                g2.stroke = BasicStroke(1f)
                g2.draw(shape)
            }
        }
        foreground = if (isEnabled) fg() else fg().alpha(120)
        g2.dispose()
        super.paintComponent(g.smooth())
    }
}

/** A round icon-only button with a hover halo and a tooltip. */
class IconButton(icon: String, tooltip: String, size: Int = 20, private val tint: (() -> Color)? = null) : JButton() {
    private var hover = false
    var active = false
        set(v) {
            field = v
            repaint()
        }
    var iconName: String = icon
        set(v) {
            field = v
            this.icon = VectorIcon(v, iconSize) { color() }
            repaint()
        }
    private val iconSize = size

    init {
        this.icon = VectorIcon(icon, size) { color() }
        toolTipText = tooltip
        getAccessibleContext().accessibleName = tooltip
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        val pad = (size * 0.45f).toInt()
        border = EmptyBorder(pad, pad, pad, pad)
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
            override fun mouseExited(e: MouseEvent) { hover = false; repaint() }
        })
    }

    private fun color(): Color = tint?.invoke() ?: if (active) pal.accent else pal.onSurfaceVariant

    override fun paintComponent(g: Graphics) {
        val g2 = g.create().smooth()
        if (hover || active) {
            g2.color = if (active) pal.accent.alpha(34) else pal.onSurface.alpha(16)
            g2.fill(Ellipse2D.Float(0f, 0f, width.toFloat(), height.toFloat()))
        }
        g2.dispose()
        super.paintComponent(g)
    }
}

/** A filter chip that toggles, as in the Android library. */
class Chip(text: String, selected: Boolean = false, icon: String? = null) : JToggleButton(text, selected) {
    private var hover = false

    init {
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        font = AppTheme.ui(12.5f, Font.BOLD)
        border = EmptyBorder(6, 13, 6, 13)
        if (icon != null) {
            this.icon = VectorIcon(icon, 15) { if (isSelected) pal.onAccentContainer else pal.onSurfaceVariant }
            iconTextGap = 6
        }
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
            override fun mouseExited(e: MouseEvent) { hover = false; repaint() }
        })
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create().smooth()
        val shape = RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, 12f, 12f)
        if (isSelected) {
            g2.color = pal.accentContainer
            g2.fill(shape)
        } else {
            if (hover) {
                g2.color = pal.onSurface.alpha(12)
                g2.fill(shape)
            }
            g2.color = pal.outlineVariant
            g2.draw(shape)
        }
        foreground = if (isSelected) pal.onAccentContainer else pal.onSurfaceVariant
        g2.dispose()
        super.paintComponent(g.smooth())
    }
}

/** An on/off switch. */
class Switch(on: Boolean = false) : JToggleButton() {
    init {
        isSelected = on
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        preferredSize = Dimension(42, 24)
        minimumSize = preferredSize
        maximumSize = preferredSize
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create().smooth()
        val w = 40f
        val h = 22f
        val y = (height - h) / 2f
        g2.color = if (isSelected) pal.accent else pal.surfaceHigh
        g2.fill(RoundRectangle2D.Float(1f, y, w, h, h, h))
        if (!isSelected) {
            g2.color = pal.outline.alpha(160)
            g2.draw(RoundRectangle2D.Float(1f, y, w, h, h, h))
        }
        val d = if (isSelected) 16f else 12f
        val cx = if (isSelected) 1f + w - h / 2 else 1f + h / 2
        g2.color = if (isSelected) pal.onAccent else pal.outline
        g2.fill(Ellipse2D.Float(cx - d / 2, y + h / 2 - d / 2, d, d))
        g2.dispose()
    }
}

/** A row of mutually exclusive options, like a segmented button. */
class Segmented(options: List<String>, selected: Int, private val onChange: (Int) -> Unit) : JPanel() {
    private var options: List<String> = options

    /** Replaces the labels (for counts that change), keeping the selection. */
    fun setOptions(labels: List<String>) {
        if (labels == options) return
        options = labels
        getAccessibleContext().accessibleName = labels.joinToString(" / ")
        revalidate()
        repaint()
    }

    var selected = selected
        set(v) {
            field = v
            repaint()
        }
    private var hover = -1

    init {
        isOpaque = false
        font = AppTheme.ui(12.5f, Font.BOLD)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        val m = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val i = index(e.x)
                if (i >= 0 && i != this@Segmented.selected) {
                    this@Segmented.selected = i
                    onChange(i)
                }
            }

            override fun mouseMoved(e: MouseEvent) {
                hover = index(e.x)
                repaint()
            }

            override fun mouseExited(e: MouseEvent) {
                hover = -1
                repaint()
            }
        }
        addMouseListener(m)
        addMouseMotionListener(m)
        isFocusable = true
        getAccessibleContext().accessibleName = options.joinToString(" / ")
    }

    private fun index(x: Int): Int = if (options.isEmpty()) -1 else (x * options.size / width.coerceAtLeast(1)).coerceIn(0, options.size - 1)

    override fun getPreferredSize(): Dimension {
        val fm = getFontMetrics(font)
        val w = options.maxOfOrNull { fm.stringWidth(it) } ?: 40
        return Dimension((w + 28) * options.size, 34)
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, 34)

    override fun paintComponent(g: Graphics) {
        val g2 = g.create().smooth()
        val h = height.toFloat() - 2
        g2.color = pal.outlineVariant
        g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, h, h, h))
        val cw = width.toFloat() / options.size
        val fm = g2.getFontMetrics(font)
        g2.font = font
        options.forEachIndexed { i, text ->
            val x = i * cw
            if (i == selected || i == hover) {
                g2.color = if (i == selected) pal.accentContainer else pal.onSurface.alpha(12)
                g2.fill(RoundRectangle2D.Float(x + 2f, 2.5f, cw - 4f, h - 4f, h - 4f, h - 4f))
            }
            g2.color = if (i == selected) pal.onAccentContainer else pal.onSurfaceVariant
            val tw = fm.stringWidth(text)
            g2.drawString(text, x + (cw - tw) / 2f, (h + fm.ascent - fm.descent) / 2f + 1)
        }
        g2.dispose()
    }
}

/** A thin rounded progress bar. */
class ProgressLine(var value: Float = 0f, private val heightPx: Int = 4) : Widget() {
    init {
        preferredSize = Dimension(80, heightPx)
        maximumSize = Dimension(Int.MAX_VALUE, heightPx)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create().smooth()
        val h = heightPx.toFloat()
        val y = (height - h) / 2f
        g2.color = pal.onSurface.alpha(28)
        g2.fill(RoundRectangle2D.Float(0f, y, width.toFloat(), h, h, h))
        g2.color = pal.accent
        g2.fill(RoundRectangle2D.Float(0f, y, (width * value.coerceIn(0f, 1f)).coerceAtLeast(if (value > 0f) h else 0f), h, h, h))
        g2.dispose()
    }
}

/** A search box with a leading magnifier and a clear button (FlatLaf features). */
fun searchField(placeholder: String, onChange: (String) -> Unit): JTextField = JTextField().apply {
    putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, placeholder)
    putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, VectorIcon("search", 16) { pal.onSurfaceVariant })
    putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true)
    putClientProperty(FlatClientProperties.STYLE, "arc: 999; margin: 6,10,6,10")
    getAccessibleContext().accessibleName = placeholder
    document.addDocumentListener(object : javax.swing.event.DocumentListener {
        override fun insertUpdate(e: javax.swing.event.DocumentEvent) = onChange(text)
        override fun removeUpdate(e: javax.swing.event.DocumentEvent) = onChange(text)
        override fun changedUpdate(e: javax.swing.event.DocumentEvent) = onChange(text)
    })
}

/** A friendly message where content will appear, with optional actions. */
fun emptyState(icon: String, title: String, body: String, vararg actions: JComponent): JPanel {
    val iconLabel = JLabel(VectorIcon(icon, 44) { pal.accent.alpha(200) })
    val t = Ui.headline(title, 22f).apply { horizontalAlignment = SwingConstants.CENTER }
    val b = Ui.label("<html><div style='width:360px;text-align:center'>${Ui.escape(body)}</div></html>", 13.5f) { pal.onSurfaceVariant }.apply { horizontalAlignment = SwingConstants.CENTER }
    val buttons = Ui.flow(*actions, gap = 10, align = FlowLayout.CENTER)
    val col = Ui.vbox(iconLabel, t, b, buttons, gap = 12, align = Component.CENTER_ALIGNMENT)
    return Transparent(java.awt.GridBagLayout()).apply { add(col) }
}

/** The section header used across screens: a title and optional trailing actions. */
fun sectionHeader(title: String, vararg trailing: Component): JPanel {
    val row = Transparent(BorderLayout())
    row.add(Ui.label(title, 16f, Font.BOLD), BorderLayout.WEST)
    if (trailing.isNotEmpty()) row.add(Ui.hbox(*trailing, gap = 6), BorderLayout.EAST)
    row.border = EmptyBorder(4, 0, 8, 0)
    row.maximumSize = Dimension(Int.MAX_VALUE, 44)
    return row
}

fun AbstractButton.onClick(block: () -> Unit): AbstractButton = apply { addActionListener { block() } }

fun Icon.asLabel(): JLabel = JLabel(this)

fun icon(name: String, size: Int = 18, color: () -> Color = { pal.onSurfaceVariant }) = VectorIcon(name, size, color = color)
