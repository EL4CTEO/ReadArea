package com.readarea.desktop.platform

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Text to speech with the voices the operating system already has: `say` on macOS, SAPI on Windows,
 * speech-dispatcher or eSpeak NG on Linux. Nothing leaves the computer.
 *
 * Book text reaches the speech program through its standard input (or base64 inside it), never on a
 * command line a shell could interpret, and is stripped of control characters and engine commands.
 */
interface SpeechEngine {
    val name: String
    fun voices(): List<String> = emptyList()

    /** Speaks [text] and calls [onDone] when finished, unless [stop] is called first. */
    fun speak(text: String, rate: Float, voice: String, onDone: () -> Unit)
    fun stop()
    fun close() = stop()

    companion object {
        fun detect(): SpeechEngine? = when (Os.current) {
            Os.MAC -> SystemIntegration.systemBinary("/usr/bin/say")?.let { MacSay(it) }
            Os.WINDOWS -> WindowsSapi.find()
            else -> SystemIntegration.systemBinary("/usr/bin/espeak-ng", "/usr/local/bin/espeak-ng", "/usr/bin/espeak")?.let { Espeak(it) }
                ?: SystemIntegration.systemBinary("/usr/bin/spd-say")?.let { SpdSay(it) }
        }

        /** Removes control characters and engine markup such as `[[rate 900]]` from book text. */
        fun clean(text: String): String = text
            .replace(Regex("\\[\\[[^\\]]{0,40}]]"), " ")
            .replace(Regex("[\\u0000-\\u001F\\u007F\\u2028\\u2029\\uFFFC]"), " ")
            .replace('\u00AD', '\u200B')
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(2000)
    }
}

/** Runs one speech process per utterance; stopping kills it. */
private abstract class ProcessSpeech : SpeechEngine {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "speech").apply { isDaemon = true } }
    private val generation = AtomicLong()
    @Volatile private var current: Process? = null

    abstract fun command(rate: Float, voice: String): List<String>

    override fun speak(text: String, rate: Float, voice: String, onDone: () -> Unit) {
        val gen = generation.get()
        val clean = SpeechEngine.clean(text)
        worker.execute {
            if (generation.get() != gen) return@execute
            if (clean.isNotEmpty()) runCatching {
                val p = ProcessBuilder(command(rate, voice)).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
                current = p
                p.outputStream.use { it.write(clean.toByteArray(StandardCharsets.UTF_8)) }
                p.waitFor()
                current = null
            }
            if (generation.get() == gen) onDone()
        }
    }

    override fun stop() {
        generation.incrementAndGet()
        current?.destroyForcibly()
        current = null
    }
}

private class MacSay(private val bin: String) : ProcessSpeech() {
    override val name = "macOS"

    override fun command(rate: Float, voice: String): List<String> {
        val cmd = mutableListOf(bin, "-r", (180 * rate).toInt().coerceIn(80, 540).toString())
        if (voice.isNotBlank() && VOICE.matches(voice)) cmd += listOf("-v", voice)
        return cmd + listOf("-f", "-")
    }

    override fun voices(): List<String> = SystemIntegration.run(listOf(bin, "-v", "?"), 4000)
        ?.lines()?.mapNotNull { l -> Regex("^(.+?)\\s{2,}[a-z]{2}[_-]").find(l)?.groupValues?.get(1)?.trim() }?.distinct().orEmpty()

    companion object {
        val VOICE = Regex("[\\p{L}\\p{N} ()._-]{1,64}")
    }
}

private class Espeak(private val bin: String) : ProcessSpeech() {
    override val name = "eSpeak NG"

    override fun command(rate: Float, voice: String): List<String> {
        val cmd = mutableListOf(bin, "-s", (175 * rate).toInt().coerceIn(80, 450).toString())
        if (voice.isNotBlank() && Regex("[A-Za-z0-9+_-]{1,32}").matches(voice)) cmd += listOf("-v", voice)
        return cmd + "--stdin"
    }

    override fun voices(): List<String> = SystemIntegration.run(listOf(bin, "--voices"), 4000)
        ?.lines()?.drop(1)?.mapNotNull { it.trim().split(Regex("\\s+")).getOrNull(1) }?.distinct().orEmpty()
}

private class SpdSay(private val bin: String) : ProcessSpeech() {
    override val name = "Speech Dispatcher"

    // spd-say reads the text from standard input with -e; -w waits until it has been spoken.
    override fun command(rate: Float, voice: String): List<String> =
        listOf(bin, "-w", "-e", "-r", ((rate - 1f) * 100).toInt().coerceIn(-100, 100).toString())
}

/**
 * Windows voices through System.Speech, driven by one PowerShell process that reads commands from its
 * input. Each line carries the text base64-encoded and is only ever decoded into a string to speak, so
 * nothing in a book can become PowerShell code.
 */
private class WindowsSapi(private val powershell: String) : SpeechEngine {
    override val name = "Windows"
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "speech").apply { isDaemon = true } }
    private val generation = AtomicLong()
    @Volatile private var process: Process? = null
    private var input: OutputStreamWriter? = null
    private var output: BufferedReader? = null

    private fun ensure(): Boolean {
        if (process?.isAlive == true) return true
        return runCatching {
            // The script is a constant; standard input carries only the SAY/LIST protocol.
            val encoded = Base64.getEncoder().encodeToString(SCRIPT.toByteArray(StandardCharsets.UTF_16LE))
            val p = ProcessBuilder(powershell, "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded).redirectErrorStream(true).start()
            val w = OutputStreamWriter(p.outputStream, StandardCharsets.US_ASCII)
            process = p
            input = w
            output = BufferedReader(InputStreamReader(p.inputStream, StandardCharsets.UTF_8))
            true
        }.getOrDefault(false)
    }

    private fun send(line: String) {
        input?.apply {
            write(line)
            write("\n")
            flush()
        }
    }

    private fun awaitLine(prefix: String): String? {
        val r = output ?: return null
        while (true) {
            val l = r.readLine() ?: return null
            if (l.startsWith(prefix)) return l
        }
    }

    override fun speak(text: String, rate: Float, voice: String, onDone: () -> Unit) {
        val gen = generation.get()
        val clean = SpeechEngine.clean(text)
        worker.execute {
            if (generation.get() != gen) return@execute
            if (clean.isNotEmpty() && ensure()) runCatching {
                val b64 = Base64.getEncoder().encodeToString(clean.toByteArray(StandardCharsets.UTF_8))
                val v = Base64.getEncoder().encodeToString(voice.take(64).toByteArray(StandardCharsets.UTF_8))
                val sapiRate = ((rate - 1f) * 5f).toInt().coerceIn(-10, 10)
                send("SAY $sapiRate $v $b64")
                awaitLine("DONE")
            }
            if (generation.get() == gen) onDone()
        }
    }

    override fun voices(): List<String> {
        if (!ensure()) return emptyList()
        return runCatching {
            synchronized(this) {
                send("LIST")
                val out = ArrayList<String>()
                while (true) {
                    val l = awaitLine("V ") ?: break
                    if (l == "V END") break
                    out.add(l.removePrefix("V "))
                }
                out
            }
        }.getOrDefault(emptyList())
    }

    override fun stop() {
        generation.incrementAndGet()
        process?.destroyForcibly()
        process = null
    }

    companion object {
        fun find(): WindowsSapi? {
            val root = System.getenv("SystemRoot") ?: "C:\\Windows"
            val ps = File(root, "System32\\WindowsPowerShell\\v1.0\\powershell.exe")
            return if (ps.isFile) WindowsSapi(ps.path) else null
        }

        private val SCRIPT = """
Add-Type -AssemblyName System.Speech
${'$'}s = New-Object System.Speech.Synthesis.SpeechSynthesizer
${'$'}enc = [System.Text.Encoding]::UTF8
while (${'$'}true) {
  ${'$'}line = [Console]::In.ReadLine()
  if (${'$'}line -eq ${'$'}null) { break }
  ${'$'}p = ${'$'}line.Split(' ')
  if (${'$'}p[0] -eq 'SAY' -and ${'$'}p.Length -ge 4) {
    try {
      ${'$'}s.Rate = [int]${'$'}p[1]
      ${'$'}v = ${'$'}enc.GetString([Convert]::FromBase64String(${'$'}p[2]))
      if (${'$'}v.Length -gt 0) { try { ${'$'}s.SelectVoice(${'$'}v) } catch {} }
      ${'$'}s.Speak(${'$'}enc.GetString([Convert]::FromBase64String(${'$'}p[3])))
    } catch {}
    [Console]::Out.WriteLine('DONE'); [Console]::Out.Flush()
  } elseif (${'$'}p[0] -eq 'LIST') {
    foreach (${'$'}x in ${'$'}s.GetInstalledVoices()) { if (${'$'}x.Enabled) { [Console]::Out.WriteLine('V ' + ${'$'}x.VoiceInfo.Name) } }
    [Console]::Out.WriteLine('V END'); [Console]::Out.Flush()
  }
}
""".trimIndent()
    }
}
