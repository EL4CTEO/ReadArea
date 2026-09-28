package readarea.tooling

import org.gradle.api.logging.Logger
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Records an application class-data archive for a jpackage app image: starts the app through its own
 * launcher with a small book, lets it quit on its own (READAREA_TRAINING_RUN), and has the JVM write
 * the classes it loaded to `<name>.jsa` next to the jars. The launcher is only pointed at the archive
 * once it exists, so a failed training leaves a working image that starts the ordinary way.
 */
class ClassArchiveTraining(private val image: File, private val name: String, private val scratch: File, private val logger: Logger) {
    fun run() {
        val os = Host.current().os
        val launcher = when (os) {
            HostOs.MAC -> File(image, "$name.app/Contents/MacOS/$name")
            HostOs.WINDOWS -> File(image, "$name/$name.exe")
            HostOs.LINUX -> File(image, "$name/bin/$name")
        }
        val appDir = if (os == HostOs.MAC) File(image, "$name.app/Contents/app") else File(image, "$name/lib/app")
        val cfg = File(appDir, "$name.cfg")
        val archive = File(appDir, "$name.jsa")
        if (!launcher.isFile || !cfg.isFile) return logger.warn("Class-data archive skipped: no launcher or configuration in $image")
        val home = File(scratch, "training-home").apply { deleteRecursively(); mkdirs() }
        val book = File(scratch, "Training.epub").apply { writeBytes(sampleEpub()) }
        archive.delete()
        val pb = ProcessBuilder(launcher.path, book.path).redirectErrorStream(true)
        val env = pb.environment()
        env["JAVA_TOOL_OPTIONS"] = listOfNotNull(env["JAVA_TOOL_OPTIONS"], "-XX:ArchiveClassesAtExit=${archive.path}").joinToString(" ")
        env["READAREA_HOME"] = home.path
        env["READAREA_TRAINING_RUN"] = "1"
        val p = pb.start()
        val out = ByteArrayOutputStream()
        val reader = Thread { runCatching { p.inputStream.copyTo(out) } }.apply { isDaemon = true; start() }
        val finished = p.waitFor(2, TimeUnit.MINUTES)
        if (!finished) p.destroyForcibly()
        reader.join(1000)
        home.deleteRecursively()
        if (!finished || p.exitValue() != 0 || !archive.isFile || archive.length() < 1_000_000) {
            archive.delete()
            logger.warn("Class-data archive skipped: the training run didn't complete (exit ${if (finished) p.exitValue() else "timeout"}).\n${out.toString(Charsets.UTF_8).takeLast(2000)}")
            return
        }
        val lines = cfg.readLines().toMutableList()
        val section = lines.indexOfFirst { it.trim() == "[JavaOptions]" }
        if (section < 0) {
            archive.delete()
            return logger.warn("Class-data archive skipped: no [JavaOptions] in $cfg")
        }
        lines.add(section + 1, "java-options=-XX:SharedArchiveFile=\$APPDIR/$name.jsa")
        cfg.writeText(lines.joinToString("\n", postfix = "\n"))
        logger.lifecycle("Class-data archive: ${archive.length() / (1 shl 20)} MB")
    }

    /** A short EPUB for the training run to open, so the reader's classes are recorded too. */
    private fun sampleEpub(): ByteArray {
        val para = "The lamp burned low while the pages turned, one after another, through the quiet hours of the night."
        val chapter = "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>One</title></head><body><h1>Chapter One</h1>" +
            (1..40).joinToString("") { "<p>$para <i>Softly</i> and <b>slowly</b>.</p>" } + "</body></html>"
        val files = linkedMapOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to "<?xml version=\"1.0\"?><container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles><rootfile full-path=\"content.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>",
            "content.opf" to "<?xml version=\"1.0\"?><package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"id\"><metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:identifier id=\"id\">training</dc:identifier><dc:title>Training</dc:title><dc:language>en</dc:language></metadata><manifest><item id=\"c1\" href=\"c1.xhtml\" media-type=\"application/xhtml+xml\"/><item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/></manifest><spine><itemref idref=\"c1\"/></spine></package>",
            "nav.xhtml" to "<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\"><body><nav epub:type=\"toc\"><ol><li><a href=\"c1.xhtml\">Chapter One</a></li></ol></nav></body></html>",
            "c1.xhtml" to chapter,
        )
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((path, text) in files) {
                val data = text.toByteArray()
                val e = ZipEntry(path)
                if (path == "mimetype") {
                    // The EPUB rules: stored, not compressed.
                    e.method = ZipEntry.STORED
                    e.size = data.size.toLong()
                    e.crc = CRC32().apply { update(data) }.value
                }
                z.putNextEntry(e)
                z.write(data)
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }
}
