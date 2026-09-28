package com.readarea.desktop.platform

import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

/**
 * Keeps one ReadArea running per user. A second launch (say, double-clicking a book in the file manager)
 * hands its files to the running app and exits.
 *
 * The channel is a Unix domain socket inside the app's private data folder, so only the same user can
 * reach it; requests also carry a random token from a file only that user can read. The running app only
 * accepts a short list of existing regular files, which it opens exactly as if they'd been dropped on it.
 */
class SingleInstance(private val dir: File) : AutoCloseable {
    private val socket = File(dir, "instance.sock")
    private val tokenFile = File(dir, "instance.token")
    private var server: ServerSocketChannel? = null
    @Volatile private var closed = false

    /** Sends [files] (or just "come to the front") to a running instance. True if one took them. */
    fun forward(files: List<File>): Boolean {
        if (!socket.exists() || !tokenFile.isFile) return false
        val token = runCatching { tokenFile.readText().trim() }.getOrNull() ?: return false
        return runCatching {
            SocketChannel.open(UnixDomainSocketAddress.of(socket.toPath())).use { ch ->
                val msg = buildString {
                    append(MAGIC).append('\n').append(token).append('\n')
                    files.take(MAX_FILES).forEach { append(it.absolutePath.replace('\n', ' ')).append('\n') }
                    append('\n')
                }
                ch.write(ByteBuffer.wrap(msg.toByteArray(StandardCharsets.UTF_8)))
                val reply = ByteBuffer.allocate(8)
                ch.read(reply)
                String(reply.array(), 0, reply.position(), StandardCharsets.UTF_8).startsWith("OK")
            }
        }.getOrDefault(false)
    }

    /**
     * Starts accepting requests from later launches. [onRequest] runs on a background thread with the
     * files to open (possibly none, meaning "show the window"). Returns false if the socket couldn't be set up,
     * in which case every launch simply runs on its own.
     */
    fun listen(onRequest: (List<File>) -> Unit): Boolean {
        AppDirs.ensurePrivate(dir)
        val token = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        return runCatching {
            if (socket.exists()) socket.delete()
            val ch = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
            ch.bind(UnixDomainSocketAddress.of(socket.toPath()), 4)
            AppDirs.makePrivate(socket)
            tokenFile.writeText(token)
            AppDirs.makePrivate(tokenFile)
            server = ch
            Thread({ serve(ch, token, onRequest) }, "single-instance").apply { isDaemon = true }.start()
            true
        }.getOrDefault(false)
    }

    private fun serve(ch: ServerSocketChannel, token: String, onRequest: (List<File>) -> Unit) {
        while (!closed) {
            val client = runCatching { ch.accept() }.getOrNull() ?: if (closed) return else continue
            // One slow client must not hold up the next launch.
            Thread.ofVirtual().name("single-instance-client").start { handle(client, token, onRequest) }
        }
    }

    private fun handle(client: SocketChannel, token: String, onRequest: (List<File>) -> Unit) {
        run {
            runCatching {
                client.use { c ->
                    val text = readLimited(c) ?: return@use
                    val lines = text.split('\n')
                    if (lines.size < 2 || lines[0] != MAGIC || !constantTimeEquals(lines[1], token)) return@use
                    val files = lines.drop(2).asSequence().filter { it.isNotBlank() }.take(MAX_FILES)
                        .map { File(it) }.filter { it.isAbsolute && it.isFile }.toList()
                    c.write(ByteBuffer.wrap("OK\n".toByteArray()))
                    onRequest(files)
                }
            }
        }
    }

    private fun readLimited(c: SocketChannel): String? {
        val buf = ByteBuffer.allocate(MAX_BYTES)
        while (buf.hasRemaining()) {
            val n = c.read(buf)
            if (n < 0) break
            val s = String(buf.array(), 0, buf.position(), StandardCharsets.UTF_8)
            if (s.endsWith("\n\n")) return s
        }
        return null
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    override fun close() {
        closed = true
        runCatching { server?.close() }
        runCatching { socket.delete() }
        runCatching { tokenFile.delete() }
    }

    companion object {
        private const val MAGIC = "READAREA1"
        const val MAX_FILES = 50
        private const val MAX_BYTES = 64 * 1024
    }
}
