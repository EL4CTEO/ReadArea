package com.readarea.qa

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import com.readarea.OpenBookActivity
import com.readarea.core.format.TestBooks
import com.readarea.data.SafeFiles
import com.readarea.data.db.BookEntity
import com.readarea.reader.ReaderActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xxhdpi", shadows = [NoMagnifier::class, StrictContentResolver::class])
class SecurityTest : ReaderQa() {

    @Test
    fun sharedFileNamesCannotEscapeTheImportFolder() {
        val payload = QaBooks.realisticEpub("Payload", 1)
        EvilProvider.file = File(app.cacheDir, "evil-source/payload.epub").apply { parentFile!!.mkdirs(); writeBytes(payload) }
        Robolectric.setupContentProvider(EvilProvider::class.java, "evil.books")
        val imported = File(app.filesDir, "imported")
        val names = listOf("../../databases/readarea.db-wal", "../shared_prefs/prefs.xml", "/data/data/com.readarea/files/datastore/settings.preferences_pb", "..\\..\\evil.epub", "....//....//x.epub", "..", ".", "", "a\u0000b.epub", "x".repeat(400) + ".epub")
        names.forEachIndexed { i, n ->
            EvilProvider.displayName = n
            val id = runBlocking { app.library.openExternal(Uri.parse("content://evil.books/book/$i")) }
            assertNotNull("'$n' should still be imported safely", id)
            val f = File(Uri.parse(runBlocking { app.database.books().get(id!!) }!!.uri).path!!)
            assertTrue("copy of '$n' must stay inside imported/, was $f", SafeFiles.inside(imported, f))
            assertTrue(f.name.length <= 130)
        }
        val leaked = app.dataDir.walkTopDown().filter { it.isFile && it.length() == payload.size.toLong() && !SafeFiles.inside(imported, it) && it != EvilProvider.file && it.readBytes().contentEquals(payload) }.toList()
        assertTrue("payload written outside the import folder: $leaked", leaked.isEmpty())
    }

    @Test
    fun openWithRefusesReadAreasOwnPrivateFiles() {
        val secret = File(app.filesDir, "datastore/secret.txt").apply { parentFile!!.mkdirs(); writeText(TestBooks.lorem(4000)) }
        val uris = listOf(
            Uri.fromFile(secret),
            Uri.fromFile(File(app.dataDir, "databases/readarea.db")),
            Uri.parse("file://" + app.filesDir.absolutePath + "/../databases/readarea.db"),
            Uri.parse("content://${app.packageName}.files/imported/x.epub"),
            Uri.parse("javascript:alert(1)"),
        )
        for (uri in uris) {
            val a = Robolectric.buildActivity(OpenBookActivity::class.java, Intent(Intent.ACTION_VIEW).setDataAndType(uri, "text/plain")).create().get()
            assertNull("must not open $uri", shadowOf(a).nextStartedActivity)
            assertTrue(a.isFinishing)
            assertNull(runBlocking { app.library.openExternal(uri) })
        }
        val books = runBlocking { app.database.books().all() }
        assertTrue(books.none { b -> uris.any { it.toString() == b.uri } })
    }

    @Test
    fun openWithForwardsRealBooksToTheReader() {
        val f = File(Environment.getExternalStorageDirectory(), "Download/novel.epub").apply { parentFile!!.mkdirs(); writeBytes(QaBooks.realisticEpub("Novel", 1)) }
        val uri = Uri.fromFile(f)
        val a = Robolectric.buildActivity(OpenBookActivity::class.java, Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/epub+zip")).create().get()
        val next = shadowOf(a).nextStartedActivity
        assertNotNull(next)
        assertEquals(ReaderActivity::class.java.name, next.component?.className)
        assertEquals(uri, next.data)
        assertTrue(next.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(a.isFinishing)
    }

    @Test
    fun onlyTheOpenerIsReachableFromOtherApps() {
        val pm = app.packageManager
        assertFalse("the reader must not be exported", pm.getActivityInfo(ComponentName(app, ReaderActivity::class.java), 0).exported)
        assertTrue(pm.getActivityInfo(ComponentName(app, OpenBookActivity::class.java), 0).exported)
        assertFalse(pm.getProviderInfo(ComponentName(app, FileProvider::class.java), 0).exported)
        val perms = pm.getPackageInfo(app.packageName, android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toList()
        assertFalse("ReadArea must never ask for internet access", perms.contains("android.permission.INTERNET"))
    }

    private fun linkBook(): ByteArray {
        val links = listOf(
            "jslink" to "javascript:alert(1)",
            "intentlink" to "intent://scan/#Intent;scheme=zxing;package=com.evil;end",
            "filelink" to "file:///data/data/com.readarea/databases/readarea.db",
            "contentlink" to "content://com.readarea.files/imported/a.epub",
            "tellink" to "tel:5551234",
            "weblink" to "https://example.com/page",
            "maillink" to "mailto:reader@example.com",
        )
        val body = links.joinToString(" ") { (w, h) -> "<a href=\"${h.replace("&", "&amp;")}\">$w</a>" }
        return TestBooks.zip(
            linkedMapOf(
                "mimetype" to "application/epub+zip".toByteArray(),
                "META-INF/container.xml" to "<container><rootfiles><rootfile full-path=\"c.opf\"/></rootfiles></container>".toByteArray(),
                "c.opf" to "<package version=\"3.0\"><metadata><dc:title>Links</dc:title></metadata><manifest><item id=\"a\" href=\"a.xhtml\" media-type=\"application/xhtml+xml\"/></manifest><spine><itemref idref=\"a\"/></spine></package>".toByteArray(),
                "a.xhtml" to "<html><body><p>$body</p><p>${TestBooks.lorem(300)}</p></body></html>".toByteArray(),
            ),
        )
    }

    @Test
    fun bookLinksOnlyOpenWebAndEmail() {
        val id = addBook("links.epub", linkBook(), "EPUB", "Links")
        open(id).use { sc ->
            val e = engine(sc)
            val text = e.plainText(0)
            val expected = mapOf("jslink" to null, "intentlink" to null, "filelink" to null, "contentlink" to null, "tellink" to null, "weblink" to "link:https://example.com/page", "maillink" to "link:mailto:reader@example.com")
            for ((word, msg) in expected) {
                val at = text.indexOf(word)
                val ink = inkOnScreen(sc, at, at + word.length)
                tap(sc, ink.centerX(), ink.centerY())
                assertEquals("tapping $word", msg, sc.get { it.vm.ui.value.message })
                sc.onActivity { it.vm.dismissMessage() }
                assertEquals(com.readarea.reader.engine.PagePos(0, 0), pos(sc))
            }
        }
    }

    private fun bomb(entryName: String, prefix: String, suffix: String, megabytes: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            fun put(name: String, bytes: ByteArray) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
            put("mimetype", "application/epub+zip".toByteArray())
            put("META-INF/container.xml", "<container><rootfiles><rootfile full-path=\"c.opf\"/></rootfiles></container>".toByteArray())
            if (entryName != "c.opf") put("c.opf", "<package version=\"3.0\"><metadata><dc:title>Bomb</dc:title></metadata><manifest><item id=\"a\" href=\"a.xhtml\" media-type=\"application/xhtml+xml\"/></manifest><spine><itemref idref=\"a\"/></spine></package>".toByteArray())
            z.putNextEntry(ZipEntry(entryName))
            z.write(prefix.toByteArray())
            val chunk = ByteArray(1 shl 20) { ' '.code.toByte() }
            repeat(megabytes) { z.write(chunk) }
            z.write(suffix.toByteArray())
            z.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun zipBombsFailFastWithAClearMessage() {
        val opf = bomb("c.opf", "<package version=\"3.0\"><metadata><dc:title>Bomb</dc:title>", "</metadata></package>", 48)
        assertTrue("fixture should be small on disk (${opf.size})", opf.size < 1_000_000)
        val id = addBook("bomb.epub", opf, "EPUB", "Bomb")
        val started = System.currentTimeMillis()
        val sc = androidx.test.core.app.ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(app, id))
        sc.use {
            sc.waitFor(30_000, "error shown") { it.vm.ui.value.error != null }
            assertEquals("This file is too large to open.", sc.get { it.vm.ui.value.error })
        }
        assertTrue("fails fast (${System.currentTimeMillis() - started} ms)", System.currentTimeMillis() - started < 30_000)
        val book = runBlocking { app.database.books().get(id) }!!
        runBlocking { app.library.loadMetadata(book.copy(metaLoaded = false)) }

        val chapter = bomb("a.xhtml", "<html><body><p>", "</p></body></html>", 48)
        val id2 = addBook("bomb2.epub", chapter, "EPUB", "Bomb 2")
        androidx.test.core.app.ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(app, id2)).use { sc2 ->
            sc2.waitFor(30_000, "chapter bomb handled") { it.vm.ui.value.error != null || it.vm.ui.value.laidOut }
            assertTrue(sc2.get { it.vm.ui.value.error == null || it.vm.ui.value.error == "This file is too large to open." })
        }
    }

    @Test
    fun backupsOnlyLeaveThePhoneEndToEndEncrypted() {
        val res = File("src/main/res")
        val cloud = File(res, "xml/data_extraction_rules.xml").readText()
        assertTrue(cloud.contains("<cloud-backup disableIfNoEncryptionCapabilities=\"true\">"))
        val legacy = File(res, "xml/backup_rules.xml").readText()
        assertFalse("Android 8 has no encrypted backups, so nothing is included there", legacy.contains("<include"))
        val v28 = File(res, "xml-v28/backup_rules.xml").readText()
        val includes = Regex("<include [^>]*/>").findAll(v28).map { it.value }.toList()
        assertTrue(includes.isNotEmpty())
        assertTrue(includes.all { it.contains("requireFlags=\"clientSideEncryption\"") })
    }

    @Test
    fun safeFileNamesStripPathsAndControlCharacters() {
        assertEquals("readarea.db-wal", SafeFiles.fileName("../../databases/readarea.db-wal"))
        assertEquals("evil.epub", SafeFiles.fileName("..\\..\\evil.epub"))
        assertEquals("book", SafeFiles.fileName(".."))
        assertEquals("book", SafeFiles.fileName(null))
        assertEquals("a_b.epub", SafeFiles.fileName("a\u0000b.epub"))
        assertEquals("hidden.epub", SafeFiles.fileName(".hidden.epub"))
        assertTrue(SafeFiles.fileName("x".repeat(500) + ".epub").let { it.length <= 120 && it.endsWith(".epub") })
        assertTrue(SafeFiles.isOpenableLink(Uri.parse("HTTPS://example.com")))
        assertFalse(SafeFiles.isOpenableLink(Uri.parse("intent://x#Intent;end")))
        assertFalse(SafeFiles.isOpenableLink(Uri.parse("file:///sdcard/x")))
        assertFalse(SafeFiles.isOpenableLink(Uri.parse("javascript:alert(1)")))
    }

    @Test
    fun deviceBooksCanBeSharedWithoutCrashing() {
        val f = File(Environment.getExternalStorageDirectory(), "Books/share.epub").apply { parentFile!!.mkdirs(); writeBytes(QaBooks.realisticEpub("Share Me", 1)) }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", f)
        assertEquals("content", uri.scheme)
        assertEquals(f.readBytes().size, app.contentResolver.openInputStream(uri)!!.use { it.readBytes() }.size)
        runBlocking { app.database.books().insert(BookEntity(uri = Uri.fromFile(f).toString(), fileName = f.name, folderUri = "device", format = "EPUB", size = f.length(), title = "Share Me", metaLoaded = true)) }
    }
}
