package com.readarea.desktop.ui

import com.readarea.desktop.ui.components.ScreenHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.ComponentOrientation
import java.awt.Dimension
import javax.swing.JComponent

class ScreenHeaderTest {
    /** A stand-in of a known size, like a title, a search field or a button. */
    private fun part(w: Int, h: Int, min: Int = w) = object : JComponent() {
        init {
            preferredSize = Dimension(w, h)
            minimumSize = Dimension(min, h)
        }
    }

    private class Setup(val header: ScreenHeader, val title: JComponent, val tools: JComponent, val action: JComponent)

    // A 200px title, tools that can shrink from 300px to 100px, and a 120px action, with 10px between them.
    private fun setup(width: Int): Setup {
        val title = part(200, 40)
        val tools = part(300, 34, min = 100)
        val action = part(120, 34)
        val header = ScreenHeader(title, tools, action, gap = 10)
        header.setSize(width, 200)
        header.preferredSize // works out which layout this width calls for, as the parent does before laying out
        header.doLayout()
        return Setup(header, title, tools, action)
    }

    @Test
    fun everythingSitsOnOneLineWhenThereIsRoom() {
        val s = setup(800)
        assertEquals(0, s.title.x)
        assertEquals(800, s.action.x + s.action.width)
        assertTrue(s.title.x + s.title.width <= s.tools.x)
        assertTrue(s.tools.x + s.tools.width <= s.action.x)
        assertEquals("the tools keep their full size", 300, s.tools.width)
        assertEquals("one row", 40, s.header.preferredSize.height)
    }

    @Test
    fun theToolsShrinkBeforeAnythingOverlaps() {
        // 200 + 10 + 120 + 10 leaves 160 of 500 for the tools: less than they'd like, more than they need.
        val s = setup(500)
        assertEquals(160, s.tools.width)
        assertTrue(s.title.x + s.title.width <= s.tools.x)
        assertTrue(s.tools.x + s.tools.width <= s.action.x)
        assertEquals("still one row", 40, s.header.preferredSize.height)
    }

    @Test
    fun theToolsDropUnderTheTitleWhenTheyCannotFitBesideIt() {
        val s = setup(400)
        assertTrue("the title keeps its line", s.title.y + s.title.height <= s.tools.y)
        assertEquals(0, s.tools.x)
        assertTrue(s.tools.x + s.tools.width <= 400)
        assertEquals("the action stays at the far edge of the first line", 400, s.action.x + s.action.width)
        assertTrue(s.title.x + s.title.width <= s.action.x)
        assertEquals("two rows", 40 + 10 + 34, s.header.preferredSize.height)
    }

    @Test
    fun aRightToLeftWindowIsTheMirrorImage() {
        val ltr = setup(800)
        val title = part(200, 40)
        val tools = part(300, 34, min = 100)
        val action = part(120, 34)
        val header = ScreenHeader(title, tools, action, gap = 10)
        header.componentOrientation = ComponentOrientation.RIGHT_TO_LEFT
        header.setSize(800, 200)
        header.preferredSize
        header.doLayout()
        assertEquals("the title starts at the right", 800, title.x + title.width)
        assertEquals("the action ends at the left", 0, action.x)
        assertEquals(800 - (ltr.tools.x + ltr.tools.width), tools.x)
        assertFalse(title.bounds.intersects(tools.bounds))
        assertFalse(tools.bounds.intersects(action.bounds))
    }
}
