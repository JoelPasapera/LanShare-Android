package io.github.joelpasapera.lanshare.core.console

/**
 * Limitador por cubeta de fichas: ráfagas de hasta [capacity] y, de forma
 * sostenida, [refillPerSecond] por segundo. El tiempo lo pone quien llama (ms
 * de un reloj monótono), así se prueba sin esperar y un reloj que retrocede no
 * regala fichas. Portado de LanShare para Windows.
 */
class TokenBucket(val capacity: Int, refillPerSecond: Double, nowMs: Long) {
    private val refillPerMs: Double
    private var tokens: Double
    private var lastMs: Long = nowMs

    init {
        require(capacity >= 1) { "capacity" }
        require(refillPerSecond > 0) { "refillPerSecond" }
        refillPerMs = refillPerSecond / 1000.0
        tokens = capacity.toDouble()
    }

    /** Cuántas de las [wanted] fichas pedidas se conceden. */
    @Synchronized
    fun take(wanted: Int, nowMs: Long): Int {
        if (wanted <= 0) return 0
        if (nowMs > lastMs) {
            tokens = minOf(capacity.toDouble(), tokens + (nowMs - lastMs) * refillPerMs)
            lastMs = nowMs
        }
        val granted = minOf(wanted.toDouble(), Math.floor(tokens)).toInt()
        tokens -= granted
        return granted
    }
}
