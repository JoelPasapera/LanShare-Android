package io.github.joelpasapera.lanshare.core.site

import io.github.joelpasapera.lanshare.core.content.ContentReadException
import io.github.joelpasapera.lanshare.core.content.OpenedFile
import java.io.IOException
import java.io.OutputStream
import java.nio.Buffer
import java.nio.ByteBuffer

/**
 * Del archivo al flujo de respuesta en trozos de 64 KB, sin cargarlo entero,
 * con la etiqueta de la consola insertada si hace falta.
 *
 * Los fallos al leer el archivo salen como [ContentReadException]; los de
 * escritura, como IOException: así el servidor distingue "no pude leer"
 * (500) de "el cliente se fue".
 */
internal object FileBodies {
    private val BUFFER = ThreadLocal.withInitial { ByteBuffer.allocate(64 * 1024) }
    private const val MAX_EMPTY_READS = 16

    /**
     * Envía [length] bytes desde [start]. El primer trozo se lee ANTES de
     * llamar a [begin] (que escribe la cabecera): si el archivo no se puede
     * leer, el fallo llega con la respuesta aún sin comprometer y se contesta
     * 500 en lugar de cortar la conexión a mitad.
     */
    fun send(file: OpenedFile, start: Long, length: Long, begin: () -> OutputStream) {
        val buffer = BUFFER.get()
        val first = minOf(length, buffer.capacity().toLong()).toInt()
        fill(file, buffer, start, first)
        val out = begin()
        out.write(buffer.array(), 0, first)
        copy(file, start + first, length - first, out)
    }

    fun copy(file: OpenedFile, start: Long, length: Long, out: OutputStream) {
        val buffer = BUFFER.get()
        var position = start
        var remaining = length
        while (remaining > 0) {
            val n = minOf(remaining, buffer.capacity().toLong()).toInt()
            fill(file, buffer, position, n)
            out.write(buffer.array(), 0, n)
            position += n
            remaining -= n
        }
    }

    /** El archivo entero, con [snippet] en la posición [at] (o sin nada si es null). */
    fun write(file: OpenedFile, snippet: ByteArray?, at: Long, out: OutputStream) {
        if (snippet == null) {
            copy(file, 0, file.size, out)
            return
        }
        copy(file, 0, at, out)
        out.write(snippet)
        copy(file, at, file.size - at, out)
    }

    /** Llena las primeras [count] posiciones de [buffer] con el archivo desde [position], o lanza. */
    private fun fill(file: OpenedFile, buffer: ByteBuffer, position: Long, count: Int) {
        // Como Buffer y no como ByteBuffer: los clear()/limit() covariantes son
        // de Java 9 y no existen en los Android antiguos.
        val raw: Buffer = buffer
        raw.clear()
        raw.limit(count)
        var at = position
        var stalls = 0
        while (buffer.hasRemaining()) {
            val n = read(file, buffer, at)
            if (n < 0) throw ContentReadException("El archivo se acortó mientras se enviaba")
            if (n == 0) {
                if (++stalls > MAX_EMPTY_READS) throw ContentReadException("El archivo dejó de entregar datos")
                continue
            }
            stalls = 0
            at += n
        }
    }

    fun read(file: OpenedFile, dst: ByteBuffer, position: Long): Int = try {
        file.read(dst, position)
    } catch (e: IOException) {
        throw ContentReadException(e.message ?: e.javaClass.simpleName, e)
    } catch (e: SecurityException) {
        // SAF: el permiso sobre la carpeta se retiró con el archivo abierto.
        throw ContentReadException(e.message ?: "Permiso retirado", e)
    }
}

/**
 * Vista por posición de un archivo abierto, con una ventana pequeña: lo que
 * necesita [HtmlInjector] para recorrer el principio de una página sin
 * leerla entera.
 */
internal class FileWindow(private val file: OpenedFile, capacity: Int = 8 * 1024) : ByteSource {
    private val buffer = ByteBuffer.allocate(capacity)
    private var windowStart = 0L
    private var windowLength = 0

    override fun at(index: Long): Int {
        if (index < 0 || index >= file.size) return -1
        var offset = index - windowStart
        if (offset < 0 || offset >= windowLength) {
            fill(index)
            offset = 0
            if (windowLength == 0) return -1
        }
        return buffer.get(offset.toInt()).toInt() and 0xFF
    }

    private fun fill(from: Long) {
        val raw: Buffer = buffer
        raw.clear()
        windowStart = from
        var position = from
        while (buffer.hasRemaining()) {
            val n = FileBodies.read(file, buffer, position)
            if (n <= 0) break
            position += n
        }
        windowLength = buffer.position()
    }
}
