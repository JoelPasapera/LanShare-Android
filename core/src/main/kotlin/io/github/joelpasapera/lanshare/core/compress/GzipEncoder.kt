package io.github.joelpasapera.lanshare.core.compress

import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Bytes en memoria que crecen hasta un tope. Al pasarlo no se reserva nada
 * más: se lanza [Overflow] y quien escribe decide (gzip que no compensa).
 */
class ByteSink(initialCapacity: Int, private val limit: Int) {
    class Overflow : RuntimeException("Supera el tope", null, false, false)

    private var buffer = ByteArray(initialCapacity.coerceIn(16, maxOf(16, limit)))

    var size = 0
        private set

    fun write(b: ByteArray, off: Int, len: Int) {
        val needed = size + len
        if (needed > limit || needed < 0) throw Overflow()
        if (needed > buffer.size) {
            var capacity = buffer.size
            while (capacity < needed) capacity = if (capacity > limit / 2) limit else capacity * 2
            buffer = buffer.copyOf(capacity)
        }
        System.arraycopy(b, off, buffer, size, len)
        size = needed
    }

    /** Arreglo justo del tamaño escrito (sin copia si ya lo es). */
    fun toByteArray(): ByteArray = if (buffer.size == size) buffer else buffer.copyOf(size)
}

/**
 * Compresor gzip (RFC 1952) escrito sobre Deflater: cabecera, flujo DEFLATE
 * sin envoltorio y cola con CRC-32 y longitud.
 *
 * Existe en lugar de GZIPOutputStream por el control del Deflater nativo: en
 * los Android basados en OpenJDK 8, si el destino lanza mientras se cierra
 * (aquí, al pasar el tope de [ByteSink]), GZIPOutputStream no libera la
 * memoria de zlib hasta que pase el recolector. Aquí [release] la libera
 * siempre, en un finally.
 */
class GzipEncoder(private val sink: ByteSink, level: Int = Deflater.DEFAULT_COMPRESSION) : OutputStream() {
    private val deflater = Deflater(level, true)
    private val crc = CRC32()
    private val chunk = ByteArray(16 * 1024)
    private var inputBytes = 0L
    private var finished = false

    init {
        sink.write(HEADER, 0, HEADER.size)
    }

    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()), 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        check(!finished) { "gzip ya terminado" }
        if (len == 0) return
        crc.update(b, off, len)
        inputBytes += len
        deflater.setInput(b, off, len)
        // Deflater guarda una referencia a [b]: hay que consumirlo entero antes de volver.
        while (!deflater.needsInput()) pump()
    }

    /** Termina el flujo y escribe la cola. Después ya no se puede escribir. */
    fun finish() {
        if (finished) return
        deflater.finish()
        while (!deflater.finished()) pump()
        val v = crc.value
        val n = inputBytes
        val tail = byteArrayOf(
            v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte(),
            n.toByte(), (n ushr 8).toByte(), (n ushr 16).toByte(), (n ushr 24).toByte(),
        )
        sink.write(tail, 0, tail.size)
        finished = true
    }

    /** Libera la memoria nativa de zlib. Imprescindible, haya ido bien o mal. */
    fun release() {
        deflater.end()
    }

    override fun close() {
        try {
            finish()
        } finally {
            release()
        }
    }

    private fun pump() {
        val n = deflater.deflate(chunk, 0, chunk.size)
        if (n > 0) sink.write(chunk, 0, n)
    }

    companion object {
        /** ID1 ID2, CM = deflate, sin banderas ni fecha, XFL 0, sistema desconocido. */
        private val HEADER = byteArrayOf(0x1f, 0x8b.toByte(), 8, 0, 0, 0, 0, 0, 0, 0xff.toByte())

        /** Comprime [data] entero (contenido ya en memoria, como un listado de carpeta). */
        fun compress(data: ByteArray): ByteArray {
            val sink = ByteSink(data.size / 3 + 64, Int.MAX_VALUE - 16)
            val encoder = GzipEncoder(sink)
            try {
                encoder.write(data, 0, data.size)
                encoder.finish()
            } finally {
                encoder.release()
            }
            return sink.toByteArray()
        }
    }
}
