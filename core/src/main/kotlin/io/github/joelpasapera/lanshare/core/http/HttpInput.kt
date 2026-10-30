package io.github.joelpasapera.lanshare.core.http

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException

/** La cabecera de la petición supera el máximo admitido. */
class HeadTooLargeException : IOException("Cabecera demasiado grande")

/** Cambia el tiempo de espera de la próxima lectura (en un socket, SO_TIMEOUT). */
internal fun interface ReadTimeout {
    fun set(millis: Int)

    companion object {
        val NONE = ReadTimeout { }
    }
}

/**
 * Lectura con búfer propio de una conexión HTTP.
 *
 * Un único arreglo hace de búfer para la cabecera y para el comienzo del
 * cuerpo: lo que llega pegado a la cabecera (o la siguiente petición, si el
 * cliente encadena) se queda en él y lo consume quien lea a continuación.
 * Así se admiten peticiones encadenadas sin copiar nada.
 *
 * Plazos: el tiempo de espera del socket solo corta a quien calla; a quien
 * envía un byte cada pocos segundos para retener el hilo (slowloris) lo corta
 * un plazo total, que se traslada al socket antes de cada lectura para que
 * ninguna espere más de lo que queda.
 */
internal class HttpInput(
    private val input: InputStream,
    capacity: Int,
    private val readTimeout: ReadTimeout = ReadTimeout.NONE,
) {
    private val buf = ByteArray(capacity)
    private var start = 0
    private var end = 0
    private var appliedTimeout = -1

    /**
     * Bytes recibidos y sin consumir. Tras un plazo agotado en [readHead],
     * distinto de cero significa que el cliente dejó una cabecera a medias
     * (merece un 408); cero, que la conexión simplemente estaba ociosa.
     */
    val pendingBytes: Int get() = end - start

    /**
     * Lee la cabecera completa hasta la línea en blanco y la devuelve sin
     * ella. null si la conexión se cerró limpiamente antes de empezar.
     *
     * Admite LF solo además de CRLF (RFC 9112 §2.2) y descarta las líneas
     * vacías previas a la línea de petición, que algunos clientes envían
     * tras un POST.
     *
     * [idleTimeoutMs] limita cada espera (0: sin límite); [headTimeoutMs], el
     * total desde que llega el primer byte de la cabecera.
     */
    fun readHead(maxBytes: Int, idleTimeoutMs: Int = 0, headTimeoutMs: Int = 0): ByteArray? {
        // El plazo total empieza con el primer byte, aunque sea un salto de línea
        // previo: si no, "\r\n" cada pocos segundos retendría el hilo sin límite.
        var deadline = if (start < end) startDeadline(headTimeoutMs) else NO_DEADLINE
        while (true) {
            while (start < end && (buf[start] == CR || buf[start] == LF)) start++
            if (start < end) break
            start = 0
            end = 0
            prepareRead(idleTimeoutMs, deadline)
            val n = input.read(buf, 0, buf.size)
            if (n < 0) return null
            end = n
            if (deadline == NO_DEADLINE) deadline = startDeadline(headTimeoutMs)
        }

        var scan = start
        while (true) {
            var i = scan
            while (i < end) {
                if (buf[i] == LF) {
                    if (i + 1 < end && buf[i + 1] == LF) return take(i, i + 2)
                    if (i + 2 < end && buf[i + 1] == CR && buf[i + 2] == LF) return take(i, i + 3)
                }
                i++
            }

            if (end - start >= maxBytes) throw HeadTooLargeException()

            // Volver a mirar los dos últimos bytes: el terminador puede llegar partido.
            scan = maxOf(start, end - 2)

            if (end == buf.size) {
                if (start == 0) throw HeadTooLargeException()
                val pending = end - start
                System.arraycopy(buf, start, buf, 0, pending)
                scan -= start
                start = 0
                end = pending
            }

            prepareRead(idleTimeoutMs, deadline)
            val n = input.read(buf, end, buf.size - end)
            if (n < 0) throw EOFException("Conexión cerrada a mitad de la cabecera")
            end += n
        }
    }

    private fun startDeadline(timeoutMs: Int): Long =
        if (timeoutMs > 0) System.nanoTime() + timeoutMs * 1_000_000L else NO_DEADLINE

    /** Ajusta el tiempo de espera de la lectura que viene; lanza si el plazo ya pasó. */
    private fun prepareRead(idleTimeoutMs: Int, deadlineNanos: Long) {
        var timeout = idleTimeoutMs
        if (deadlineNanos != NO_DEADLINE) {
            val remaining = (deadlineNanos - System.nanoTime()) / 1_000_000
            if (remaining <= 0) throw SocketTimeoutException("Plazo agotado")
            if (timeout == 0 || remaining < timeout) timeout = remaining.toInt()
        }
        if (timeout != appliedTimeout) {
            readTimeout.set(timeout)
            appliedTimeout = timeout
        }
    }

    private fun take(lastLineFeed: Int, next: Int): ByteArray {
        // Sin el CR que precede al último LF: la cabecera sale limpia.
        val headEnd = if (lastLineFeed > start && buf[lastLineFeed - 1] == CR) lastLineFeed - 1 else lastLineFeed
        val head = buf.copyOfRange(start, headEnd)
        start = next
        return head
    }

    /**
     * Cuerpo de longitud conocida: primero lo que quede en el búfer, luego el
     * socket. [bodyTimeoutMs] limita el total desde la primera lectura, y
     * [beforeFirstRead] se ejecuta justo antes de ella (el servidor envía ahí
     * el "100 Continue": solo si el manejador de verdad quiere el cuerpo).
     */
    fun body(length: Long, idleTimeoutMs: Int = 0, bodyTimeoutMs: Int = 0, beforeFirstRead: (() -> Unit)? = null): Body =
        Body(length, idleTimeoutMs, bodyTimeoutMs, beforeFirstRead)

    inner class Body internal constructor(
        private var remaining: Long,
        private val idleTimeoutMs: Int,
        private val bodyTimeoutMs: Int,
        private var beforeFirstRead: (() -> Unit)?,
    ) : InputStream() {
        private var deadline = NO_DEADLINE

        /** Se leyó (o descartó) todo el cuerpo declarado. */
        val isFullyRead: Boolean get() = remaining <= 0

        /** Alguien empezó a leer el cuerpo. */
        var started = false
            private set

        override fun read(): Int {
            if (remaining <= 0) return -1
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            if (len == 0) return 0
            if (!started) {
                started = true
                if (bodyTimeoutMs > 0) deadline = System.nanoTime() + bodyTimeoutMs * 1_000_000L
                beforeFirstRead?.let {
                    beforeFirstRead = null
                    it()
                }
            }
            val want = minOf(len.toLong(), remaining).toInt()

            val buffered = end - start
            if (buffered > 0) {
                val n = minOf(want, buffered)
                System.arraycopy(buf, start, b, off, n)
                start += n
                remaining -= n
                return n
            }

            prepareRead(idleTimeoutMs, deadline)
            val n = input.read(b, off, want)
            if (n < 0) throw EOFException("Conexión cerrada a mitad del cuerpo")
            remaining -= n
            return n
        }

        override fun available(): Int = minOf((end - start).toLong(), remaining).toInt()

        override fun skip(n: Long): Long {
            var skipped = 0L
            val scratch = ByteArray(8192)
            while (skipped < n) {
                val r = read(scratch, 0, minOf(scratch.size.toLong(), n - skipped).toInt())
                if (r < 0) break
                skipped += r
            }
            return skipped
        }
    }

    private companion object {
        const val CR: Byte = '\r'.code.toByte()
        const val LF: Byte = '\n'.code.toByte()
        const val NO_DEADLINE = Long.MAX_VALUE
    }
}
