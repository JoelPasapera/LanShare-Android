package io.github.joelpasapera.lanshare.core.log

import java.util.concurrent.CopyOnWriteArrayList

enum class LogLevel(val tag: String) {
    INFO("INFO"),
    WARN("WARN"),
    ERROR("ERROR"),
    SECURITY("SEGURIDAD"),
    JS_LOG("JS LOG"),
    JS_INFO("JS INFO"),
    JS_DEBUG("JS DEBUG"),
    JS_WARN("JS WARN"),
    JS_ERROR("JS ERROR");

    val isJavaScript: Boolean get() = name.startsWith("JS_")
    val isProblem: Boolean get() = this == WARN || this == ERROR || this == SECURITY || this == JS_WARN || this == JS_ERROR
}

/** Una línea del registro. [port] es el del sitio (0 si no corresponde a ninguno). */
class LogEntry(
    val sequence: Long,
    val timeMillis: Long,
    val level: LogLevel,
    val port: Int,
    val text: String,
)

/**
 * Registro compartido por el servidor y la interfaz. Guarda las últimas
 * [capacity] líneas en una cola circular y avisa a los oyentes de cada nueva.
 *
 * Los oyentes se llaman en el hilo que escribe (un hilo del servidor): la
 * interfaz debe pasar al hilo principal por su cuenta.
 */
class LogBus(capacity: Int = 2000) {
    private val ring = RingBuffer<LogEntry>(capacity)
    private val listeners = CopyOnWriteArrayList<(LogEntry) -> Unit>()
    private var sequence = 0L

    fun write(level: LogLevel, port: Int, text: String) {
        val entry: LogEntry
        synchronized(ring) {
            entry = LogEntry(++sequence, System.currentTimeMillis(), level, port, text)
            ring.add(entry)
        }
        for (listener in listeners) {
            try {
                listener(entry)
            } catch (e: RuntimeException) {
                // Un oyente defectuoso no puede tumbar el servidor.
            }
        }
    }

    fun snapshot(): List<LogEntry> = synchronized(ring) { ring.toList() }

    fun clear() {
        synchronized(ring) { ring.clear() }
    }

    fun addListener(listener: (LogEntry) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (LogEntry) -> Unit) {
        listeners.remove(listener)
    }
}
