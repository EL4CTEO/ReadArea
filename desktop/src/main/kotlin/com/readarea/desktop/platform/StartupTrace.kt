package com.readarea.desktop.platform

import java.time.Duration
import java.time.Instant

/**
 * Opt-in start-up timings for measuring and tuning launch speed: with `READAREA_TRACE_STARTUP=1`, each
 * milestone is printed to standard error as milliseconds since the process started.
 */
object StartupTrace {
    private val enabled = System.getenv("READAREA_TRACE_STARTUP") == "1"
    private val start: Instant? = if (enabled) ProcessHandle.current().info().startInstant().orElse(null) else null

    fun mark(label: String) {
        if (!enabled) return
        val ms = start?.let { Duration.between(it, Instant.now()).toMillis() } ?: -1
        System.err.println("startup $label: ${ms}ms")
    }
}
