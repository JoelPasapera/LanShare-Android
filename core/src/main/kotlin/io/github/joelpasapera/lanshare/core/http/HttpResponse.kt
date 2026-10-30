package io.github.joelpasapera.lanshare.core.http

import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Respuesta HTTP con longitud siempre conocida: el servidor nunca usa
 * transferencia por trozos, así que cada respuesta declara Content-Length y la
 * conexión puede reutilizarse sin ambigüedad.
 *
 * Responsabilidad única: el formato en el cable. Qué responder lo decide el
 * manejador.
 */
class HttpResponse internal constructor(
    private val out: OutputStream,
    private val headOnly: Boolean,
) {
    private val names = ArrayList<String>(12)
    private val values = ArrayList<String>(12)

    var status: Int = 200
        private set

    /** La cabecera ya se escribió: no se pueden añadir más. */
    var isCommitted: Boolean = false
        private set

    /** Bytes de cuerpo enviados (0 en HEAD, 304...). */
    var bodyBytes: Long = 0
        private set

    /** El manejador pide cerrar la conexión tras esta respuesta. */
    var closeConnection: Boolean = false

    /** Cliente HTTP/1.0 que pidió keep-alive: hay que confirmárselo o esperaría al cierre. */
    internal var echoKeepAlive: Boolean = false

    /** Lo llama el servidor justo antes de escribir la cabecera (última ocasión de pedir el cierre). */
    internal var beforeCommit: (() -> Unit)? = null

    private var declaredLength = 0L
    private var carriesBody = false

    /**
     * Se escribió todo el cuerpo declarado. Si no, la conexión queda
     * desalineada (el cliente esperaría más bytes) y hay que cerrarla.
     */
    internal val isBodyComplete: Boolean
        get() = !isCommitted || !carriesBody || bodyBytes == declaredLength

    fun header(name: String, value: String): HttpResponse {
        check(!isCommitted) { "Cabecera ya enviada" }
        names.add(name)
        values.add(sanitize(value))
        return this
    }

    fun setHeader(name: String, value: String): HttpResponse {
        removeHeader(name)
        return header(name, value)
    }

    fun removeHeader(name: String) {
        check(!isCommitted) { "Cabecera ya enviada" }
        var i = names.size - 1
        while (i >= 0) {
            if (names[i].equals(name, ignoreCase = true)) {
                names.removeAt(i)
                values.removeAt(i)
            }
            i--
        }
    }

    fun hasHeader(name: String): Boolean = names.any { it.equals(name, ignoreCase = true) }

    /**
     * Quita las cabeceras que describen un contenido concreto (tipo, validadores,
     * rangos, codificación). Antes de un error: la página de error no es el
     * archivo, y heredar su tipo o su ETag confundiría al navegador y a su caché.
     */
    fun clearRepresentation() {
        for (name in REPRESENTATION_HEADERS) removeHeader(name)
    }

    /** Página de error mínima, sin heredar nada del contenido que se iba a servir. */
    fun sendErrorPage(status: Int, detail: String?) {
        clearRepresentation()
        send(status, "text/html; charset=utf-8", HttpStatus.errorPage(status, detail).toByteArray(StandardCharsets.UTF_8))
    }

    /**
     * Escribe la línea de estado y las cabeceras, y devuelve el flujo del
     * cuerpo. El flujo descarta lo que se escriba en HEAD y en los estados sin
     * cuerpo, y lanza si se intenta escribir más de [contentLength].
     */
    fun begin(status: Int, contentLength: Long): OutputStream {
        check(!isCommitted) { "Respuesta ya iniciada" }
        beforeCommit?.let {
            beforeCommit = null
            it()
        }
        this.status = status
        val bodiless = isBodiless(status)

        val sb = StringBuilder(256)
        sb.append("HTTP/1.1 ").append(status).append(' ').append(HttpStatus.reason(status)).append("\r\n")
        sb.append("Date: ").append(DateCache.now()).append("\r\n")
        sb.append("Server: LanShare-Android\r\n")
        for (i in names.indices) sb.append(names[i]).append(": ").append(values[i]).append("\r\n")
        if (!bodiless) sb.append("Content-Length: ").append(contentLength).append("\r\n")
        if (closeConnection) sb.append("Connection: close\r\n")
        else if (echoKeepAlive) sb.append("Connection: keep-alive\r\n")
        sb.append("\r\n")

        out.write(sb.toString().toByteArray(StandardCharsets.ISO_8859_1))
        isCommitted = true
        declaredLength = contentLength
        carriesBody = !bodiless && !headOnly

        return if (carriesBody) CountingBody(contentLength) else DiscardingBody
    }

    /** [contentType] null conserva el que ya hubiera; si no, lo sustituye. */
    fun send(status: Int, contentType: String?, body: ByteArray, offset: Int = 0, length: Int = body.size) {
        if (contentType != null) setHeader("Content-Type", contentType)
        val stream = begin(status, length.toLong())
        stream.write(body, offset, length)
    }

    fun sendText(status: Int, text: String, contentType: String = "text/plain; charset=utf-8") {
        send(status, contentType, text.toByteArray(StandardCharsets.UTF_8))
    }

    fun sendEmpty(status: Int) {
        begin(status, 0)
    }

    internal fun flush() {
        out.flush()
    }

    private inner class CountingBody(private val declared: Long) : OutputStream() {
        override fun write(b: Int) {
            if (bodyBytes + 1 > declared) throw IOException("Cuerpo más largo que Content-Length")
            out.write(b)
            bodyBytes++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (bodyBytes + len > declared) throw IOException("Cuerpo más largo que Content-Length")
            out.write(b, off, len)
            bodyBytes += len
        }

        override fun flush() = out.flush()
    }

    private object DiscardingBody : OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }

    /** La fecha solo cambia una vez por segundo: se formatea una vez por segundo. */
    private object DateCache {
        @Volatile
        private var cached: Pair<Long, String> = Pair(-1L, "")

        fun now(): String {
            val second = System.currentTimeMillis() / 1000
            val current = cached
            if (current.first == second) return current.second
            val text = HttpDate.format(second * 1000)
            cached = Pair(second, text)
            return text
        }
    }

    companion object {
        private val REPRESENTATION_HEADERS = arrayOf(
            "Content-Type", "Content-Encoding", "Content-Range", "Content-Language", "Content-Disposition",
            "ETag", "Last-Modified", "Accept-Ranges", "Vary",
        )

        fun isBodiless(status: Int): Boolean = status in 100..199 || status == 204 || status == 304

        /** Una cabecera nunca puede partir la respuesta: CR y LF se cambian por espacios. */
        private fun sanitize(value: String): String =
            if (value.indexOf('\r') < 0 && value.indexOf('\n') < 0) value
            else value.replace('\r', ' ').replace('\n', ' ')
    }
}
