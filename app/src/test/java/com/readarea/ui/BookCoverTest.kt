package com.readarea.ui

import android.app.Application
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.readarea.data.db.BookEntity
import com.readarea.ui.components.BookCover
import com.readarea.ui.components.CoverCache
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xxhdpi")
class BookCoverTest {
    @get:Rule
    val compose = createComposeRule()

    private val dir get() = File(ApplicationProvider.getApplicationContext<Application>().cacheDir, "cover-test").apply { mkdirs() }

    private fun cover(name: String, top: Int, bottom: Int): String {
        val file = File(dir, "$name.png").apply { writeBytes(CoverArt.make(name, "Author", top, bottom, 0xFFF7F1E6.toInt(), 3)) }
        // Decoded ahead, so the test tells which book's picture is on screen rather than how quickly it loads.
        CoverCache.load(file.path)
        return file.path
    }

    private fun book(id: Long, title: String, coverPath: String?) =
        BookEntity(id = id, uri = "file:///$title.epub", fileName = "$title.epub", format = "EPUB", title = title, coverPath = coverPath)

    /** Whether the cover on screen is mostly red or mostly blue, read where the artwork's title and pattern leave it clear. */
    private fun tint(): String {
        val image = compose.onNodeWithTag("cover").captureToImage()
        val pixel = image.toPixelMap()[image.width / 2, image.height * 4 / 10]
        return if (pixel.red > pixel.blue) "red" else "blue"
    }

    @Test
    fun theCoverFollowsTheBookItBelongsTo() {
        val red = book(1, "Red", cover("Red", 0xFFB0463C.toInt(), 0xFF5A1D18.toInt()))
        val blue = book(2, "Blue", cover("Blue", 0xFF3F6E8C.toInt(), 0xFF16293A.toInt()))
        val plain = book(3, "Plain", null)
        var shown by mutableStateOf(red)
        compose.setContent { BookCover(shown, Modifier.width(120.dp).testTag("cover")) }
        compose.waitForIdle()
        assertEquals("red", tint())

        // Another book takes over the same card, as on the home screen when a different book is opened.
        compose.runOnIdle { shown = blue }
        compose.waitForIdle()
        assertEquals("the first book's cover stayed on screen", "blue", tint())
        compose.onNodeWithContentDescription("Blue").assertExists()

        compose.runOnIdle { shown = red }
        compose.waitForIdle()
        assertEquals("red", tint())

        // A book with no cover gets the generated one, not the previous book's picture.
        compose.runOnIdle { shown = plain }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Plain").assertDoesNotExist()
        compose.onNodeWithText("Plain").assertExists()
    }
}
