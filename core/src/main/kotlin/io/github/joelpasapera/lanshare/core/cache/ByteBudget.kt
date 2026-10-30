package io.github.joelpasapera.lanshare.core.cache

import java.util.concurrent.atomic.AtomicLong

/**
 * Presupuesto de bytes compartido entre hilos: un semáforo que cuenta bytes
 * en lugar de permisos y que nunca bloquea. Quien no cabe recibe false y
 * elige otro camino (por ejemplo, servir sin comprimir) en vez de esperar.
 */
class ByteBudget(val capacity: Long) {
    private val used = AtomicLong()

    val inUse: Long get() = used.get()

    fun tryAcquire(bytes: Long): Boolean {
        require(bytes >= 0) { "bytes < 0" }
        if (bytes > capacity) return false
        while (true) {
            val current = used.get()
            val next = current + bytes
            if (next > capacity) return false
            if (used.compareAndSet(current, next)) return true
        }
    }

    fun release(bytes: Long) {
        used.addAndGet(-bytes)
    }
}
