package io.github.joelpasapera.lanshare.core.qr

/**
 * Aritmética en GF(256) y codewords de corrección Reed-Solomon tal como los
 * define la norma QR. Las multiplicaciones van por tablas de logaritmos: cada
 * producto es una suma de índices. Portado de LanShare para Windows.
 */
internal object ReedSolomon {
    /** Polinomio primitivo de QR: x^8 + x^4 + x^3 + x^2 + 1. */
    private const val PRIMITIVE = 0x11D

    private val EXP = IntArray(512)
    private val LOG = IntArray(256)

    init {
        var value = 1
        for (i in 0 until 255) {
            EXP[i] = value
            LOG[value] = i
            value = value shl 1
            if (value and 0x100 != 0) value = value xor PRIMITIVE
        }
        // Duplicar la tabla evita reducir módulo 255 en cada producto.
        for (i in 255 until 512) EXP[i] = EXP[i - 255]
    }

    private fun multiply(a: Int, b: Int): Int =
        if (a == 0 || b == 0) 0 else EXP[LOG[a] + LOG[b]]

    /** Producto de (x - α^i) para i en 0..count-1; el coeficiente de mayor grado primero. */
    private fun generator(count: Int): IntArray {
        var poly = intArrayOf(1)
        for (i in 0 until count) {
            val next = IntArray(poly.size + 1)
            for (j in poly.indices) {
                next[j] = next[j] xor poly[j]
                next[j + 1] = next[j + 1] xor multiply(poly[j], EXP[i])
            }
            poly = next
        }
        return poly
    }

    /** Resto de dividir el mensaje desplazado entre el generador: el bloque de corrección. */
    fun encode(data: IntArray, ecCount: Int): IntArray {
        val gen = generator(ecCount)
        val remainder = IntArray(ecCount)
        for (d in data) {
            val factor = d xor remainder[0]
            System.arraycopy(remainder, 1, remainder, 0, ecCount - 1)
            remainder[ecCount - 1] = 0
            for (i in 0 until ecCount) {
                remainder[i] = remainder[i] xor multiply(gen[i + 1], factor)
            }
        }
        return remainder
    }
}
