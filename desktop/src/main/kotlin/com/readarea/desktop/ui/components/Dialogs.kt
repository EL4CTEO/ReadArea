package com.readarea.desktop.ui.components

import com.readarea.desktop.i18n.tr
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Window
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.KeyStroke

/** Small modal dialogs in the app's style: confirm, text input, message. Enter confirms, Escape cancels. */
object Dialogs {
    private fun dialog(owner: Component?, title: String, content: JComponent, buttons: List<PillButton>, onEnter: (() -> Unit)?, onEscape: () -> Unit): JDialog {
        val w = owner as? Window ?: (owner?.let { javax.swing.SwingUtilities.getWindowAncestor(it) })
        val d = JDialog(w, title, java.awt.Dialog.ModalityType.APPLICATION_MODAL)
        val root = object : JPanel(BorderLayout()) {
            override fun paintComponent(g: java.awt.Graphics) {
                g.color = pal.surface
                g.fillRect(0, 0, width, height)
            }
        }
        root.border = LeadingBorder(22, 24, 18, 24)
        root.add(Ui.label(title, 17f, Font.BOLD).apply { font = com.readarea.desktop.ui.theme.AppTheme.headline(19f) }, BorderLayout.NORTH)
        root.add(Ui.padded(content, 12, 0, 16, 0), BorderLayout.CENTER)
        root.add(Transparent(FlowLayout(FlowLayout.TRAILING, 8, 0)).apply { buttons.forEach { add(it) } }, BorderLayout.SOUTH)
        d.contentPane = root
        d.rootPane.registerKeyboardAction({ onEscape(); d.dispose() }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW)
        if (onEnter != null) {
            d.rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "ok")
            d.rootPane.actionMap.put("ok", object : AbstractAction() { override fun actionPerformed(e: java.awt.event.ActionEvent) { onEnter(); d.dispose() } })
        }
        d.pack()
        d.minimumSize = Dimension(380, d.height)
        d.setLocationRelativeTo(w)
        return d
    }

    /** Asks a yes/no question. Returns (confirmed, checkbox ticked). */
    fun confirm(owner: Component?, title: String, body: String, confirmText: String, danger: Boolean = false, checkbox: String? = null): Pair<Boolean, Boolean> {
        var ok = false
        val box = checkbox?.let { JCheckBox(it).apply { isOpaque = false } }
        val content = Ui.vbox(Ui.wrapLabel(body, 340, 13.5f) { pal.onSurfaceVariant }, gap = 10).apply { box?.let { add(Ui.gap(10)); add(it) } }
        lateinit var d: JDialog
        val yes = PillButton(confirmText, null, if (danger) ButtonKind.DANGER else ButtonKind.PRIMARY).apply { addActionListener { ok = true; d.dispose() } }
        val no = PillButton(tr("cancel"), null, ButtonKind.TEXT).apply { addActionListener { d.dispose() } }
        d = dialog(owner, title, content, listOf(no, yes), { ok = true }, {})
        d.rootPane.defaultButton = yes
        d.isVisible = true
        return ok to (box?.isSelected == true)
    }

    fun input(owner: Component?, title: String, initial: String = "", confirmText: String = tr("save"), multiline: Boolean = false, placeholder: String = ""): String? {
        var result: String? = null
        val field: JComponent = if (multiline) JTextArea(initial, 6, 32).apply {
            lineWrap = true
            wrapStyleWord = true
            putClientProperty(com.formdev.flatlaf.FlatClientProperties.PLACEHOLDER_TEXT, placeholder)
        } else JTextField(initial, 26).apply { putClientProperty(com.formdev.flatlaf.FlatClientProperties.PLACEHOLDER_TEXT, placeholder) }
        val content: JComponent = if (multiline) Ui.scroll(field).apply { preferredSize = Dimension(380, 140); border = javax.swing.BorderFactory.createLineBorder(pal.outlineVariant) } else field
        lateinit var d: JDialog
        fun value() = (field as? JTextField)?.text ?: (field as JTextArea).text
        val yes = PillButton(confirmText, null).apply { addActionListener { result = value(); d.dispose() } }
        val no = PillButton(tr("cancel"), null, ButtonKind.TEXT).apply { addActionListener { d.dispose() } }
        d = dialog(owner, title, content, listOf(no, yes), if (multiline) null else ({ result = value() }), {})
        javax.swing.SwingUtilities.invokeLater { field.requestFocusInWindow(); (field as? JTextField)?.selectAll() }
        d.isVisible = true
        return result
    }

    /** Shows [content] with Cancel and [confirmText]; true if the reader confirmed. */
    fun custom(owner: Component?, title: String, content: JComponent, confirmText: String = tr("save")): Boolean {
        var ok = false
        lateinit var d: JDialog
        val yes = PillButton(confirmText, null).apply { addActionListener { ok = true; d.dispose() } }
        val no = PillButton(tr("cancel"), null, ButtonKind.TEXT).apply { addActionListener { d.dispose() } }
        d = dialog(owner, title, content, listOf(no, yes), null, {})
        d.rootPane.defaultButton = yes
        d.isVisible = true
        return ok
    }

    fun message(owner: Component?, title: String, body: String) {
        lateinit var d: JDialog
        val ok = PillButton(tr("ok"), null).apply { addActionListener { d.dispose() } }
        d = dialog(owner, title, Ui.wrapLabel(body, 380, 13.5f) { pal.onSurfaceVariant }, listOf(ok), {}, {})
        d.isVisible = true
    }

    /** A dialog showing arbitrary content with a single close button. */
    fun show(owner: Component?, title: String, content: JComponent, closeText: String = tr("close"), extra: List<PillButton> = emptyList()): JDialog {
        lateinit var d: JDialog
        val close = PillButton(closeText, null, ButtonKind.TEXT).apply { addActionListener { d.dispose() } }
        d = dialog(owner, title, content, extra + close, null, {})
        return d
    }
}
