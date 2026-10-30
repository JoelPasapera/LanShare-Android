package io.github.joelpasapera.lanshare.core.qr

/**
 * Generador de códigos QR implementado desde cero según ISO/IEC 18004.
 * Portado línea a línea de LanShare para Windows, cuyas matrices leyó OpenCV.
 *
 * Alcance deliberado: modo byte, nivel de corrección M y versiones 1 a 6.
 * Cubre 106 bytes, muy por encima de cualquier URL de red local, y evita el
 * bloque de información de versión que solo exigen las versiones 7+.
 */
class QrCode private constructor(val version: Int) {
    val size: Int = version * 4 + 17
    private val modules = Array(size) { BooleanArray(size) }
    private val isFunction = Array(size) { BooleanArray(size) }

    /** Módulo oscuro en (fila, columna). */
    operator fun get(row: Int, column: Int): Boolean = modules[row][column]

    var mask: Int = -1
        private set

    private fun buildCodewords(payload: ByteArray): IntArray {
        val dataCodewords = dataCodewords(version)
        val bits = BitBuffer()
        bits.append(0x4, 4) // modo byte
        bits.append(payload.size, 8) // contador de caracteres (versiones 1 a 9)
        for (b in payload) bits.append(b.toInt() and 0xFF, 8)

        val capacityBits = dataCodewords * 8
        // Terminador, relleno hasta byte completo y bytes alternos, en ese orden.
        bits.append(0, minOf(4, capacityBits - bits.length))
        bits.append(0, (8 - bits.length % 8) % 8)
        val padding = intArrayOf(0xEC, 0x11)
        var i = 0
        while (bits.length < capacityBits) bits.append(padding[i++ % 2], 8)

        val data = bits.toBytes()
        // En las versiones 1 a 6 con nivel M todos los bloques miden igual:
        // el entrelazado es una simple trasposición.
        val blocks = BLOCK_COUNT[version]
        val perBlock = dataCodewords / blocks
        val ecCount = EC_PER_BLOCK[version]

        val dataBlocks = Array(blocks) { b -> IntArray(perBlock) { k -> data[b * perBlock + k] } }
        val ecBlocks = Array(blocks) { b -> ReedSolomon.encode(dataBlocks[b], ecCount) }

        val result = IntArray(TOTAL_CODEWORDS[version])
        var n = 0
        for (k in 0 until perBlock) for (b in 0 until blocks) result[n++] = dataBlocks[b][k]
        for (k in 0 until ecCount) for (b in 0 until blocks) result[n++] = ecBlocks[b][k]
        return result
    }

    private fun setFunction(row: Int, column: Int, dark: Boolean) {
        modules[row][column] = dark
        isFunction[row][column] = true
    }

    private fun drawFunctionPatterns() {
        // Sincronización: alterna claro y oscuro; da al lector el tamaño del módulo.
        for (i in 0 until size) {
            setFunction(6, i, i % 2 == 0)
            setFunction(i, 6, i % 2 == 0)
        }
        drawFinder(3, 3)
        drawFinder(3, size - 4)
        drawFinder(size - 4, 3)
        drawAlignmentPatterns()
        // Reservar el área de formato antes de colocar datos.
        drawFormatBits(0)
    }

    private fun drawFinder(centerRow: Int, centerColumn: Int) {
        // Anillos: oscuro hasta 1, claro en 2, oscuro en 3; el 4 es el separador claro.
        for (dr in -4..4) {
            for (dc in -4..4) {
                val row = centerRow + dr
                val column = centerColumn + dc
                if (row < 0 || row >= size || column < 0 || column >= size) continue
                val ring = maxOf(Math.abs(dr), Math.abs(dc))
                setFunction(row, column, ring != 2 && ring <= 3)
            }
        }
    }

    private fun drawAlignmentPatterns() {
        if (ALIGNMENT_CENTER[version] == 0) return
        val centers = intArrayOf(6, ALIGNMENT_CENTER[version])
        for (row in centers) {
            for (column in centers) {
                // Las tres esquinas las ocupan los patrones de búsqueda.
                val collides = (row == 6 && column == 6) || (row == 6 && column == centers[1]) ||
                    (row == centers[1] && column == 6)
                if (collides) continue
                for (dr in -2..2) {
                    for (dc in -2..2) {
                        val r = row + dr
                        val c = column + dc
                        if (r < 0 || r >= size || c < 0 || c >= size) continue
                        setFunction(r, c, maxOf(Math.abs(dr), Math.abs(dc)) != 1)
                    }
                }
            }
        }
    }

    /**
     * Los 15 bits de formato llevan su propia corrección BCH y van dos veces:
     * si una esquina se estropea, el lector usa la otra.
     */
    private fun drawFormatBits(mask: Int) {
        val data = (FORMAT_EC_BITS shl 3) or mask
        var remainder = data
        for (i in 0 until 10) remainder = (remainder shl 1) xor ((remainder ushr 9) * 0x537)
        val bits = ((data shl 10) or remainder) xor 0x5412

        // Primera copia, alrededor del buscador superior izquierdo: bits 0 a 7
        // bajando por la columna 8 (saltando la fila 6) y 8 a 14 por la fila 8
        // hacia la izquierda (saltando la columna 6). Es el orden de ZXing.
        // REGRESIÓN (LanShare para Windows): estaban traspuestos y ningún móvil
        // leía los códigos.
        for (i in 0..5) setFunction(i, 8, bit(bits, i))
        setFunction(7, 8, bit(bits, 6))
        setFunction(8, 8, bit(bits, 7))
        setFunction(8, 7, bit(bits, 8))
        for (i in 9 until 15) setFunction(8, 14 - i, bit(bits, i))

        // Segunda copia: bits 0 a 7 por la fila 8 bajo el buscador superior
        // derecho, de derecha a izquierda; 8 a 14 por la columna 8 junto al
        // inferior izquierdo, hacia abajo.
        for (i in 0 until 8) setFunction(8, size - 1 - i, bit(bits, i))
        for (i in 8 until 15) setFunction(size - 15 + i, 8, bit(bits, i))

        setFunction(size - 8, 8, true) // módulo oscuro, siempre presente
    }

    /** Zigzag desde la esquina inferior derecha, de dos en dos columnas, saltando la 6. */
    private fun drawCodewords(codewords: IntArray) {
        var bitIndex = 0
        val totalBits = codewords.size * 8
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vertical in 0 until size) {
                for (j in 0 until 2) {
                    val column = right - j
                    val upward = ((right + 1) and 2) == 0
                    val row = if (upward) size - 1 - vertical else vertical
                    if (isFunction[row][column] || bitIndex >= totalBits) continue
                    modules[row][column] = bit(codewords[bitIndex ushr 3], 7 - (bitIndex and 7))
                    bitIndex++
                }
            }
            right -= 2
        }
    }

    /** Se prueban las ocho máscaras y gana la de menor penalización. */
    private fun applyBestMask() {
        var best = 0
        var bestPenalty = Int.MAX_VALUE
        for (m in 0 until 8) {
            applyMask(m)
            drawFormatBits(m)
            val penalty = penalty()
            if (penalty < bestPenalty) {
                bestPenalty = penalty
                best = m
            }
            applyMask(m) // la máscara es su propia inversa
        }
        applyMask(best)
        drawFormatBits(best)
        mask = best
    }

    private fun applyMask(mask: Int) {
        for (row in 0 until size) {
            for (column in 0 until size) {
                if (isFunction[row][column]) continue
                if (maskCondition(mask, row, column)) modules[row][column] = !modules[row][column]
            }
        }
    }

    private fun penalty(): Int = penaltyRuns() + penaltyBlocks() + penaltyFinderLike() + penaltyBalance()

    private fun penaltyRuns(): Int {
        var penalty = 0
        for (line in 0 until size) {
            penalty += runPenalty(line, true)
            penalty += runPenalty(line, false)
        }
        return penalty
    }

    private fun runPenalty(line: Int, horizontal: Boolean): Int {
        var penalty = 0
        var runLength = 1
        var runColor = if (horizontal) modules[line][0] else modules[0][line]
        for (i in 1 until size) {
            val current = if (horizontal) modules[line][i] else modules[i][line]
            if (current == runColor) {
                runLength++
            } else {
                if (runLength >= 5) penalty += PENALTY_WEIGHTS[0] + (runLength - 5)
                runColor = current
                runLength = 1
            }
        }
        if (runLength >= 5) penalty += PENALTY_WEIGHTS[0] + (runLength - 5)
        return penalty
    }

    private fun penaltyBlocks(): Int {
        var penalty = 0
        for (row in 0 until size - 1) {
            for (column in 0 until size - 1) {
                val color = modules[row][column]
                if (color == modules[row][column + 1] && color == modules[row + 1][column] &&
                    color == modules[row + 1][column + 1]
                ) penalty += PENALTY_WEIGHTS[1]
            }
        }
        return penalty
    }

    private fun penaltyFinderLike(): Int {
        var penalty = 0
        val length = FINDER_LIKE.size
        for (line in 0 until size) {
            var start = 0
            while (start + length <= size) {
                if (matches(line, start, true, false)) penalty += PENALTY_WEIGHTS[2]
                if (matches(line, start, true, true)) penalty += PENALTY_WEIGHTS[2]
                if (matches(line, start, false, false)) penalty += PENALTY_WEIGHTS[2]
                if (matches(line, start, false, true)) penalty += PENALTY_WEIGHTS[2]
                start++
            }
        }
        return penalty
    }

    private fun matches(line: Int, start: Int, horizontal: Boolean, reversed: Boolean): Boolean {
        val length = FINDER_LIKE.size
        for (i in 0 until length) {
            val expected = FINDER_LIKE[if (reversed) length - 1 - i else i]
            val actual = if (horizontal) modules[line][start + i] else modules[start + i][line]
            if (actual != expected) return false
        }
        return true
    }

    private fun penaltyBalance(): Int {
        var dark = 0
        for (row in 0 until size) for (column in 0 until size) if (modules[row][column]) dark++
        val total = size * size
        val deviation = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1
        return maxOf(0, deviation) * PENALTY_WEIGHTS[3]
    }

    /** Acumulador de bits de mayor a menor peso. */
    private class BitBuffer {
        private val bits = ArrayList<Boolean>(1400)
        val length: Int get() = bits.size

        fun append(value: Int, count: Int) {
            for (i in count - 1 downTo 0) bits.add(((value ushr i) and 1) != 0)
        }

        fun toBytes(): IntArray {
            val result = IntArray((bits.size + 7) / 8)
            for (i in bits.indices) {
                if (bits[i]) result[i ushr 3] = result[i ushr 3] or (1 shl (7 - (i and 7)))
            }
            return result
        }
    }

    companion object {
        private const val MIN_VERSION = 1
        private const val MAX_VERSION = 6

        /** Nivel M: recupera hasta el 15 % de módulos dañados. */
        private const val FORMAT_EC_BITS = 0

        private val TOTAL_CODEWORDS = intArrayOf(0, 26, 44, 70, 100, 134, 172)
        private val EC_PER_BLOCK = intArrayOf(0, 10, 16, 26, 18, 24, 16)
        private val BLOCK_COUNT = intArrayOf(0, 1, 1, 1, 2, 2, 4)

        /** Segundo centro de los patrones de alineación; 0 en la versión 1. */
        private val ALIGNMENT_CENTER = intArrayOf(0, 0, 18, 22, 26, 30, 34)
        private val PENALTY_WEIGHTS = intArrayOf(3, 3, 40, 10)

        /** 1:1:3:1:1 rodeado de zona clara: lo que imita a un patrón de búsqueda. */
        private val FINDER_LIKE = booleanArrayOf(true, false, true, true, true, false, true, false, false, false, false)

        /** Bytes que caben como máximo (versión 6, nivel M). */
        const val MAX_BYTES = 106

        fun encode(text: String): QrCode {
            val payload = text.toByteArray(Charsets.UTF_8)
            val version = chooseVersion(payload.size)
            require(version > 0) { "El texto no cabe en un código QR de versión 6 o inferior." }
            val code = QrCode(version)
            val codewords = code.buildCodewords(payload)
            code.drawFunctionPatterns()
            code.drawCodewords(codewords)
            code.applyBestMask()
            return code
        }

        private fun dataCodewords(version: Int): Int =
            TOTAL_CODEWORDS[version] - EC_PER_BLOCK[version] * BLOCK_COUNT[version]

        private fun chooseVersion(length: Int): Int {
            for (v in MIN_VERSION..MAX_VERSION) {
                // Cabecera: 4 bits de modo más 8 de contador.
                if (length <= dataCodewords(v) - 2) return v
            }
            return -1
        }

        private fun bit(value: Int, index: Int): Boolean = ((value ushr index) and 1) != 0

        private fun maskCondition(mask: Int, row: Int, column: Int): Boolean = when (mask) {
            0 -> (column + row) % 2 == 0
            1 -> row % 2 == 0
            2 -> column % 3 == 0
            3 -> (column + row) % 3 == 0
            4 -> (column / 3 + row / 2) % 2 == 0
            5 -> column * row % 2 + column * row % 3 == 0
            6 -> (column * row % 2 + column * row % 3) % 2 == 0
            7 -> ((column + row) % 2 + column * row % 3) % 2 == 0
            else -> false
        }
    }
}
