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
import javax.swing.SwingUtilities

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
    fun vbox(vararg items: Component, gap: Int = 0, align: Float = Component.LEFT_ALIGNMENT): JPanel = VBox(align).apply {
        items.forEachIndexed { i, c ->
            if (i > 0 && gap > 0) add(Box.createVerticalStrut(gap))
            add(c)
        }
    }

    fun hbox(vararg items: Component, gap: Int = 0): JPanel = Transparent().apply {
        layout = BoxLayout(this, BoxLayout.LINE_AXIS)
        items.forEachIndexed { i, c ->
            if (i > 0 && gap > 0) add(Box.createHorizontalStrut(gap))
            (c as? JComponent)?.alignmentY = Component.CENTER_ALIGNMENT
            add(c)
        }
    }

    fun flow(vararg items: Component, gap: Int = 8, align: Int = FlowLayout.LEADING): JPanel = Transparent(FlowLayout(align, gap, gap / 2)).apply {
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

    /** A paragraph that wraps to the room it gets, at most [width] pixels wide. */
    fun wrapLabel(text: String, width: Int = Int.MAX_VALUE, size: Float = 13f, color: (() -> Color)? = null): WrapText = WrapText(text, size, maxWidth = width, colorOf = color ?: { pal.onSurface })

    /** A setting: a title with an optional explanation on the left, its control on the right. */
    fun settingRow(title: String, hint: String?, control: JComponent, titleSize: Float = 13.5f, hintSize: Float = 12f): JPanel {
        val left = vbox(label(title, titleSize))
        if (hint != null) left.add(WrapText(hint, hintSize, colorOf = { pal.onSurfaceVariant }))
        val p = SettingRow(left, control)
        p.border = LeadingBorder(6, 0, 6, 0)
        p.alignmentX = Component.LEFT_ALIGNMENT
        return p
    }

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
        border = LeadingBorder(top, left, bottom, right)
        add(c)
    }
}

/**
 * One setting: its title and hint at the leading edge, its control at the trailing one. A control too wide to leave
 * the title a readable width, as in a narrow window, goes under the title instead of squeezing it away.
 */
internal class SettingRow(private val left: Component, private val control: Component) : Transparent(null) {
    /** Whether the last preferred height was worked out for stacked parts; it depends on the width given. */
    private var assumedStacked = false

    init {
        add(left)
        add(control)
    }

    private fun stackedAt(room: Int): Boolean = room > 0 && control.preferredSize.width + GAP + MIN_TITLE > room

    override fun getPreferredSize(): Dimension {
        val l = left.preferredSize
        val c = control.preferredSize
        val stacked = stackedAt(width - insets.left - insets.right)
        assumedStacked = stacked
        val h = if (stacked) l.height + STACK_GAP + c.height else maxOf(l.height, c.height)
        return Dimension(l.width + GAP + c.width + insets.left + insets.right, h + insets.top + insets.bottom)
    }

    override fun getMinimumSize(): Dimension = Dimension(maxOf(MIN_TITLE, control.preferredSize.width) + insets.left + insets.right, preferredSize.height)

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    override fun doLayout() {
        val ins = insets
        val w = width - ins.left - ins.right
        val ltr = componentOrientation.isLeftToRight
        val c = control.preferredSize
        val stacked = stackedAt(w)
        // Positions run from the leading edge, so a right-to-left window is the mirror image.
        fun put(comp: Component, x: Int, y: Int, cw: Int, ch: Int) = comp.setBounds(if (ltr) ins.left + x else width - ins.right - x - cw, ins.top + y, cw, ch)
        if (stacked) {
            val lh = left.preferredSize.height
            put(left, 0, 0, w, lh)
            put(control, 0, lh + STACK_GAP, minOf(c.width, w), c.height)
        } else {
            val rowHeight = height - ins.top - ins.bottom
            val cw = minOf(c.width, w)
            put(control, w - cw, (rowHeight - c.height) / 2, cw, c.height)
            put(left, 0, 0, (w - cw - GAP).coerceAtLeast(0), rowHeight)
        }
        // The height changes with the mode: ask the parent to lay this out again with the right one.
        if (stacked != assumedStacked) SwingUtilities.invokeLater { revalidate() }
    }

    private companion object {
        const val GAP = 16
        const val STACK_GAP = 8
        const val MIN_TITLE = 150
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

/**
 * The keyboard focus indicator for custom-painted controls. The ring shows only when focus arrived from
 * the keyboard (Tab), so clicking leaves no rings behind while keyboard users always see where they are.
 */
object FocusRing {
    private const val KEY = "readarea.keyboardFocus"

    fun install(c: JComponent) {
        c.addFocusListener(object : java.awt.event.FocusListener {
            override fun focusGained(e: java.awt.event.FocusEvent) {
                c.putClientProperty(KEY, e.cause.name.startsWith("TRAVERSAL"))
                c.repaint()
            }

            override fun focusLost(e: java.awt.event.FocusEvent) {
                c.putClientProperty(KEY, false)
                c.repaint()
            }
        })
        c.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                c.putClientProperty(KEY, false)
                c.repaint()
            }
        })
    }

    fun visible(c: JComponent): Boolean = c.isFocusOwner && c.getClientProperty(KEY) == true

    fun paint(g: Graphics2D, c: JComponent, shape: java.awt.Shape) {
        if (!visible(c)) return
        val saved = g.stroke
        g.color = pal.accent
        g.stroke = BasicStroke(2f)
        g.draw(shape)
        g.stroke = saved
    }
}

/** Makes a custom component focusable and runs [action] on Space or Enter, like a button. */
fun JComponent.onActivate(action: () -> Unit) {
    isFocusable = true
    FocusRing.install(this)
    getInputMap(JComponent.WHEN_FOCUSED).put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_SPACE, 0), "activate")
    getInputMap(JComponent.WHEN_FOCUSED).put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, 0), "activate")
    actionMap.put("activate", object : javax.swing.AbstractAction() {
        override fun actionPerformed(e: java.awt.event.ActionEvent) = action()
    })
}

/**
 * A vertical stack. BoxLayout offsets children whose alignments differ, so every child added, now or
 * later, takes the stack's alignment.
 */
open class VBox(private val align: Float = Component.LEFT_ALIGNMENT) : Transparent(null) {
    init {
        layout = BoxLayout(this, BoxLayout.PAGE_AXIS)
    }

    override fun addImpl(comp: Component, constraints: Any?, index: Int) {
        (comp as? JComponent)?.alignmentX = align
        super.addImpl(comp, constraints, index)
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

/**
 * A paragraph of text that wraps to the width its container gives it; its preferred height follows that
 * width, so it never clips however narrow the window or long the translation. Draws with the same text
 * layouts it measures with.
 */
class WrapText(
    text: String,
    size: Float = 13f,
    style: Int = Font.PLAIN,
    private val maxWidth: Int = Int.MAX_VALUE,
    private val center: Boolean = false,
    private val colorOf: () -> Color = { pal.onSurface },
) : Widget(javax.accessibility.AccessibleRole.LABEL) {
    var text: String = text
        set(v) {
            if (v == field) return
            field = v
            getAccessibleContext().accessibleName = v
            laidOutFor = -1
            revalidate()
            repaint()
        }
    private var laidOutFor = -1
    private var lines: List<java.awt.font.TextLayout> = emptyList()
    private var textHeight = 0

    init {
        font = AppTheme.ui(size, style)
        getAccessibleContext().accessibleName = text
    }

    override fun setFont(f: Font?) {
        super.setFont(f)
        laidOutFor = -1
    }

    private fun lineWidth(w: Int): Float = (minOf(w, maxWidth) - insets.left - insets.right).coerceAtLeast(40).toFloat()

    private fun layout(w: Int): Int {
        if (w == laidOutFor) return textHeight
        val frc = getFontMetrics(font).fontRenderContext
        val wrap = lineWidth(w)
        val out = ArrayList<java.awt.font.TextLayout>()
        for (para in text.split('\n')) {
            if (para.isEmpty()) {
                out.add(java.awt.font.TextLayout(" ", font, frc))
                continue
            }
            val attr = java.text.AttributedString(para, mapOf(java.awt.font.TextAttribute.FONT to font))
            val lbm = java.awt.font.LineBreakMeasurer(attr.iterator, frc)
            while (lbm.position < para.length) out.add(lbm.nextLayout(wrap))
        }
        lines = out
        textHeight = out.sumOf { (it.ascent + it.descent + it.leading).toDouble() }.let { kotlin.math.ceil(it).toInt() }
        laidOutFor = w
        return textHeight
    }

    private fun natural(): Int {
        val fm = getFontMetrics(font)
        return text.split('\n').maxOf { fm.stringWidth(it) } + 2
    }

    override fun getPreferredSize(): Dimension {
        if (isPreferredSizeSet) return super.getPreferredSize()
        val ins = insets
        val w = if (width > 0) width else minOf(natural() + ins.left + ins.right, maxWidth, 360)
        return Dimension(minOf(natural() + ins.left + ins.right, w, maxWidth), layout(w) + ins.top + ins.bottom)
    }

    override fun getMinimumSize(): Dimension = Dimension(60, preferredSize.height)

    override fun getMaximumSize(): Dimension = Dimension(if (center) Int.MAX_VALUE else maxWidth, preferredSize.height)

    override fun setBounds(x: Int, y: Int, w: Int, h: Int) {
        val changed = w != width
        super.setBounds(x, y, w, h)
        // A new width can change the number of lines; ask the container for a matching height.
        if (changed && w > 0) {
            val want = layout(w) + insets.top + insets.bottom
            if (want != h) javax.swing.SwingUtilities.invokeLater { revalidate() }
        }
    }

    override fun paintComponent(g0: Graphics) {
        val g = g0.create() as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = colorOf()
        layout(width)
        val ins = insets
        val avail = lineWidth(width)
        val boxLeft = ins.left + if (center) (width - ins.left - ins.right - avail) / 2f else 0f
        var y = ins.top.toFloat()
        val ltr = componentOrientation.isLeftToRight
        for (l in lines) {
            y += l.ascent
            val adv = l.visibleAdvance
            // Lines start at the leading edge of the layout, like a label's, whatever script they're in.
            val x = when {
                center -> boxLeft + (avail - adv) / 2f
                ltr -> boxLeft
                else -> width - ins.right - adv
            }
            l.drawAt(g, x, y)
            y += l.descent + l.leading
        }
        g.dispose()
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
        border = LeadingBorder(if (compact) 5 else 9, if (compact) 12 else 18, if (compact) 5 else 9, if (compact) 14 else 20)
        iconTextGap = 8
        if (icon != null) this.icon = VectorIcon(icon, if (compact) 16 else 18) { fg() }
        FocusRing.install(this)
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
        FocusRing.paint(g2, this, RoundRectangle2D.Float(1f, 1f, width - 2f, height - 2f, height - 2f, height - 2f))
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
        border = LeadingBorder(pad, pad, pad, pad)
        FocusRing.install(this)
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
        FocusRing.paint(g2, this, Ellipse2D.Float(1f, 1f, width - 2f, height - 2f))
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
        border = LeadingBorder(6, 13, 6, 13)
        FocusRing.install(this)
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
        FocusRing.paint(g2, this, RoundRectangle2D.Float(1f, 1f, width - 2f, height - 2f, 12f, 12f))
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
        FocusRing.install(this)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create().smooth()
        mirrorIfRtl(g2)
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
        FocusRing.paint(g2, this, RoundRectangle2D.Float(1f, y, w, h, h, h))
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
        FocusRing.install(this)
        // Arrow keys move the selection, as in a radio group.
        for ((key, step) in listOf(java.awt.event.KeyEvent.VK_LEFT to -1, java.awt.event.KeyEvent.VK_RIGHT to 1)) {
            val name = "move$step"
            getInputMap(WHEN_FOCUSED).put(javax.swing.KeyStroke.getKeyStroke(key, 0), name)
            actionMap.put(name, object : javax.swing.AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent) {
                    val dir = if (componentOrientation.isLeftToRight) step else -step
                    val next = (this@Segmented.selected + dir).coerceIn(0, (options.size - 1).coerceAtLeast(0))
                    if (next != this@Segmented.selected) {
                        this@Segmented.selected = next
                        onChange(next)
                    }
                }
            })
        }
        getAccessibleContext().accessibleName = options.joinToString(" / ")
    }

    /**
     * Segment edges: equal widths when every label fits that way, otherwise widths in proportion to the
     * labels so a long label borrows room from short ones.
     */
    private fun edges(): FloatArray {
        val n = options.size
        val total = width.toFloat()
        val fm = getFontMetrics(font)
        val natural = FloatArray(n) { fm.stringWidth(options[it]) + 20f }
        val equal = total / n.coerceAtLeast(1)
        val shares = if (natural.all { it <= equal }) FloatArray(n) { 1f } else natural
        val sum = shares.sum().coerceAtLeast(1f)
        val out = FloatArray(n + 1)
        for (i in 0 until n) out[i + 1] = out[i] + total * shares[i] / sum
        return out
    }

    private fun index(x: Int): Int {
        if (options.isEmpty()) return -1
        val e = edges()
        // The first option sits at the leading edge: on the right in right-to-left layouts.
        val lx = if (componentOrientation.isLeftToRight) x else width - x
        for (i in options.indices) if (lx < e[i + 1]) return i
        return options.size - 1
    }

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
        val e = edges()
        val fm = g2.getFontMetrics(font)
        g2.font = font
        options.forEachIndexed { i, label ->
            val cw = e[i + 1] - e[i]
            val x = if (componentOrientation.isLeftToRight) e[i] else width - e[i + 1]
            if (i == selected || i == hover) {
                g2.color = if (i == selected) pal.accentContainer else pal.onSurface.alpha(12)
                g2.fill(RoundRectangle2D.Float(x + 2f, 2.5f, cw - 4f, h - 4f, h - 4f, h - 4f))
            }
            if (i == selected) FocusRing.paint(g2, this, RoundRectangle2D.Float(x + 2f, 2.5f, cw - 4f, h - 4f, h - 4f, h - 4f))
            g2.color = if (i == selected) pal.onAccentContainer else pal.onSurfaceVariant
            val text = ellipsize(label, fm, cw - 10f)
            val tw = fm.stringWidth(text)
            g2.drawString(text, x + (cw - tw) / 2f, (h + fm.ascent - fm.descent) / 2f + 1)
        }
        g2.dispose()
    }
}

/**
 * Draws a line so its visible glyphs start at [visibleLeft]. A right-to-left line keeps its trailing space
 * on its visual left, so drawing it at `right - visibleAdvance` would push the glyphs past the edge.
 */
fun java.awt.font.TextLayout.drawAt(g: Graphics2D, visibleLeft: Float, baseline: Float) {
    val lead = if (isLeftToRight) 0f else advance - visibleAdvance
    draw(g, visibleLeft - lead, baseline)
}

/** For purely graphical painting (no text): flips the drawing horizontally in right-to-left layouts. */
fun java.awt.Component.mirrorIfRtl(g: Graphics2D) {
    if (componentOrientation.isLeftToRight) return
    g.translate(width.toDouble(), 0.0)
    g.scale(-1.0, 1.0)
}

/**
 * Shortens [text] with an ellipsis so it fits in [max] pixels, measuring a few dozen times however long the
 * text is: titles come from books and can be any length, and this runs while painting.
 */
fun ellipsize(text: String, fm: java.awt.FontMetrics, max: Float): String {
    // Far more characters than any line on any screen can show.
    val limit = 1000
    if (text.length <= limit && fm.stringWidth(text) <= max) return text
    var lo = 0
    var hi = minOf(text.length, limit)
    while (lo < hi) {
        val mid = (lo + hi + 1) / 2
        if (fm.stringWidth(text.substring(0, mid) + "…") <= max) lo = mid else hi = mid - 1
    }
    // Never split a surrogate pair.
    if (lo > 0 && Character.isHighSurrogate(text[lo - 1])) lo--
    return text.substring(0, lo).trimEnd() + "…"
}

/** A thin rounded progress bar. */
class ProgressLine(var value: Float = 0f, private val heightPx: Int = 4) : Widget() {
    init {
        preferredSize = Dimension(80, heightPx)
        maximumSize = Dimension(Int.MAX_VALUE, heightPx)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create().smooth()
        mirrorIfRtl(g2)
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
    val b = WrapText(body, 13.5f, maxWidth = 380, center = true) { pal.onSurfaceVariant }
    val buttons = Ui.flow(*actions, gap = 10, align = FlowLayout.CENTER)
    val col = Ui.vbox(iconLabel, t, b, buttons, gap = 12, align = Component.CENTER_ALIGNMENT)
    return Transparent(java.awt.GridBagLayout()).apply { add(col) }
}

/** The section header used across screens: a title and optional trailing actions. */
fun sectionHeader(title: String, vararg trailing: Component): JPanel {
    val row = Transparent(BorderLayout())
    row.add(Ui.label(title, 16f, Font.BOLD), BorderLayout.LINE_START)
    if (trailing.isNotEmpty()) row.add(Ui.hbox(*trailing, gap = 6), BorderLayout.LINE_END)
    row.border = LeadingBorder(4, 0, 8, 0)
    row.maximumSize = Dimension(Int.MAX_VALUE, 44)
    return row
}

fun AbstractButton.onClick(block: () -> Unit): AbstractButton = apply { addActionListener { block() } }

fun Icon.asLabel(): JLabel = JLabel(this)

fun icon(name: String, size: Int = 18, color: () -> Color = { pal.onSurfaceVariant }) = VectorIcon(name, size, color = color)
