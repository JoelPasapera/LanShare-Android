package io.github.joelpasapera.lanshare.core.site

import io.github.joelpasapera.lanshare.core.cache.ByteBudget
import io.github.joelpasapera.lanshare.core.cache.LruCache
import io.github.joelpasapera.lanshare.core.compress.ByteSink
import io.github.joelpasapera.lanshare.core.compress.GzipEncoder
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Versiones gzip de los archivos para los clientes de la red local.
 *
 * Sin transferencia por trozos, Content-Length va delante, así que lo
 * comprimido tiene que estar entero en memoria antes de responder. Para que
 * eso nunca tumbe la app:
 *
 * - lo ya comprimido vive en una caché LRU con presupuesto en bytes;
 * - lo que se comprime a la vez también tiene presupuesto: si no cabe, se
 *   sirve sin comprimir, que siempre es posible y no gasta memoria;
 * - un mismo archivo no se comprime dos veces a la vez: quien llega mientras
 *   otro lo prepara se lleva la versión sin comprimir, sin esperar;
 * - lo que apenas comprime se recuerda para no volver a intentarlo.
 */
class GzipVariants(cacheBytes: Long, inFlightBytes: Long) {

    class Compressed(val bytes: ByteArray, val fromCache: Boolean)

    private val cache = LruCache<String, ByteArray>(cacheBytes) { it.size.toLong() + ENTRY_OVERHEAD }
    private val budget = ByteBudget(inFlightBytes)
    private val inProgress: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * La versión gzip de [key] ([sourceBytes] sin comprimir), haciéndola con
     * [write] si hace falta y se puede. null: servir sin comprimir.
     * [cachedOnly]: solo si ya estaba hecha (para HEAD, que no debe comprimir
     * nada solo para medirlo).
     */
    fun obtain(key: String, sourceBytes: Long, cachedOnly: Boolean, write: (OutputStream) -> Unit): Compressed? {
        val hit = cache.get(key)
        if (hit != null) return if (hit === NOT_WORTH) null else Compressed(hit, true)
        if (cachedOnly || sourceBytes > Int.MAX_VALUE / 2) return null

        if (!inProgress.add(key)) return null
        try {
            val cost = costOf(sourceBytes)
            if (!budget.tryAcquire(cost)) return null
            try {
                val bytes = compress(sourceBytes, write)
                cache.put(key, bytes ?: NOT_WORTH)
                return bytes?.let { Compressed(it, false) }
            } finally {
                budget.release(cost)
            }
        } finally {
            inProgress.remove(key)
        }
    }

    /** Olvida lo comprimido cuyas claves empiezan por [prefix] (un sitio reinstalado). */
    fun forget(prefix: String) {
        cache.removeIf { it.startsWith(prefix) }
    }

    fun clear() = cache.clear()

    val cachedBytes: Long get() = cache.weight

    val bytesInFlight: Long get() = budget.inUse

    private fun compress(sourceBytes: Long, write: (OutputStream) -> Unit): ByteArray? {
        // Si no baja del 90 %, no compensa ni el trabajo ni la memoria.
        val limit = (sourceBytes * 9 / 10).toInt().coerceAtLeast(64)
        val sink = ByteSink((sourceBytes / 4).toInt().coerceAtLeast(4096), limit)
        val encoder = GzipEncoder(sink)
        try {
            write(encoder)
            encoder.finish()
        } catch (e: ByteSink.Overflow) {
            return null
        } finally {
            encoder.release()
        }
        return sink.toByteArray()
    }

    companion object {
        /** Máximo que se comprime en memoria. */
        const val MAX_SOURCE_BYTES = 8L * 1024 * 1024

        private const val ENTRY_OVERHEAD = 96L

        /** Marca de "no compensa": cuenta en la caché como una entrada vacía. */
        private val NOT_WORTH = ByteArray(0)

        /**
         * Memoria máxima mientras se comprime: el búfer al crecer (viejo y
         * nuevo a la vez), la copia final y el estado de zlib.
         */
        fun costOf(sourceBytes: Long): Long = sourceBytes * 2 + 320 * 1024

        /** Presupuestos proporcionales al montón de la app (128 MB en un móvil modesto, 512 MB en uno grande). */
        fun forHeap(maxHeapBytes: Long = Runtime.getRuntime().maxMemory()): GzipVariants {
            val mb = 1024L * 1024
            return GzipVariants(
                cacheBytes = (maxHeapBytes / 10).coerceIn(4 * mb, 24 * mb),
                inFlightBytes = (maxHeapBytes / 8).coerceIn(8 * mb, 32 * mb),
            )
        }
    }
}
