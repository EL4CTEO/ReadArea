package com.readarea.desktop.ui.components

import com.readarea.desktop.i18n.I18n
import java.awt.AWTEvent
import java.awt.ComponentOrientation
import java.awt.Toolkit
import java.awt.event.ContainerEvent
import javax.swing.SwingUtilities

/**
 * Lays the whole app out right to left for Arabic and other right-to-left languages. Swing doesn't pass a
 * container's orientation on to children added later (rebuilt screens, dialogs, new windows), so every
 * component takes it as it is added. Book pages follow the book's own direction, set separately.
 */
object Mirroring {
    private var installed = false

    fun install() {
        if (installed || !I18n.rtl) return
        installed = true
        Toolkit.getDefaultToolkit().addAWTEventListener({ e ->
            if (e.id == ContainerEvent.COMPONENT_ADDED && I18n.rtl) {
                val child = (e as ContainerEvent).child
                val physical = child is LeftToRight || SwingUtilities.getAncestorOfClass(LeftToRight::class.java, child) != null
                if (!physical && child.componentOrientation.isLeftToRight) child.applyComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT)
            }
        }, AWTEvent.CONTAINER_EVENT_MASK)
    }
}

/**
 * A row that keeps physical left-to-right order in every language: controls that stand for directions on
 * the page, like the chapter arrows around the reader's progress slider.
 */
open class LeftToRight(layout: java.awt.LayoutManager? = null) : Transparent(layout) {
    init {
        super.setComponentOrientation(ComponentOrientation.LEFT_TO_RIGHT)
    }

    override fun setComponentOrientation(o: ComponentOrientation) {}

    override fun applyComponentOrientation(o: ComponentOrientation) {}
}

/** True when this component lays out right to left. */
val java.awt.Component.rtl: Boolean get() = !componentOrientation.isLeftToRight

/** [x] for a box [w] wide, measured from the leading edge: from the right in right-to-left layouts. */
fun java.awt.Component.leadingX(x: Float, w: Float): Float = if (rtl) width - x - w else x

/**
 * Padding given as leading and trailing instead of left and right, so it follows the layout into
 * right-to-left languages. Same argument order as [javax.swing.border.EmptyBorder].
 */
class LeadingBorder(top: Int, leading: Int, bottom: Int, trailing: Int) : javax.swing.border.EmptyBorder(top, leading, bottom, trailing) {
    override fun getBorderInsets(c: java.awt.Component, insets: java.awt.Insets): java.awt.Insets {
        val ltr = c.componentOrientation.isLeftToRight
        insets.set(top, if (ltr) left else right, bottom, if (ltr) right else left)
        return insets
    }

    override fun getBorderInsets(c: java.awt.Component): java.awt.Insets = getBorderInsets(c, java.awt.Insets(0, 0, 0, 0))
}
