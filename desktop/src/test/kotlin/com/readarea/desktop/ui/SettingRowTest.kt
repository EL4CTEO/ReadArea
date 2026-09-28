package com.readarea.desktop.ui

import com.readarea.desktop.ui.components.SettingRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.ComponentOrientation
import java.awt.Dimension
import javax.swing.JComponent

class SettingRowTest {
    /** A stand-in of a known size, like a title with its hint or a control. */
    private fun part(w: Int, h: Int) = object : JComponent() {
        init {
            preferredSize = Dimension(w, h)
        }
    }

    private class Setup(val row: SettingRow, val title: JComponent, val control: JComponent)

    // A 200x36 title block and a control of the given width, in a row given [width], laid out as a parent would:
    // first told the width, then asked how tall it wants to be, then given that height.
    private fun setup(width: Int, controlWidth: Int, rtl: Boolean = false): Setup {
        val title = part(200, 36)
        val control = part(controlWidth, 30)
        val row = SettingRow(title, control)
        if (rtl) row.componentOrientation = ComponentOrientation.RIGHT_TO_LEFT
        row.setSize(width, 0)
        row.setSize(width, row.preferredSize.height)
        row.doLayout()
        return Setup(row, title, control)
    }

    @Test
    fun aSmallControlSitsBesideItsTitle() {
        val s = setup(width = 500, controlWidth = 100)
        assertEquals("the control ends at the trailing edge", 500, s.control.x + s.control.width)
        assertTrue(s.title.x + s.title.width <= s.control.x)
        assertEquals("one row, as tall as the taller part", 36, s.row.preferredSize.height)
        assertEquals("the control is centered against the title", 3, s.control.y)
    }

    @Test
    fun aWideControlGoesUnderItsTitleInsteadOfSqueezingItAway() {
        val s = setup(width = 400, controlWidth = 300)
        assertEquals("the title keeps the whole width", 400, s.title.width)
        assertTrue("the control is under the title", s.title.y + s.title.height <= s.control.y)
        assertEquals("at the leading edge", 0, s.control.x)
        assertEquals("two rows", 36 + 8 + 30, s.row.preferredSize.height)
    }

    @Test
    fun theTitleAlwaysKeepsAReadableWidthBesideTheControl() {
        // 300 for the control, 16 between and 150 for the title is the least that still sits on one line.
        val fits = setup(width = 466, controlWidth = 300)
        assertEquals(150, fits.title.width)
        assertTrue(fits.title.x + fits.title.width <= fits.control.x)
        assertEquals(36, fits.row.preferredSize.height)
        val tooNarrow = setup(width = 465, controlWidth = 300)
        assertEquals("one pixel less and it stacks", 36 + 8 + 30, tooNarrow.row.preferredSize.height)
    }

    @Test
    fun aRightToLeftRowIsTheMirrorImage() {
        val s = setup(width = 500, controlWidth = 100, rtl = true)
        assertEquals("the control is at the left", 0, s.control.x)
        assertEquals("the title starts at the right", 500, s.title.x + s.title.width)
        val stacked = setup(width = 400, controlWidth = 300, rtl = true)
        assertEquals("stacked, the control lines up with the title at the right", 400, stacked.control.x + stacked.control.width)
    }
}
