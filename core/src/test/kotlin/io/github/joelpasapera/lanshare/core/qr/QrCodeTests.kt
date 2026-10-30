package io.github.joelpasapera.lanshare.core.qr

import io.github.joelpasapera.lanshare.core.testkit.Check
import io.github.joelpasapera.lanshare.core.testkit.Suite

class QrCodeTests : Suite("QrCode") {

    private fun rows(code: QrCode): List<String> = (0 until code.size).map { r ->
        buildString { for (c in 0 until code.size) append(if (code[r, c]) '#' else '.') }
    }

    init {
        for (reference in QrReferenceCodes.readable) {
            test("REGRESION: genera exactamente la matriz que leyó OpenCV para ${reference.text}") {
                val code = QrCode.encode(reference.text)
                Check.equal(reference.version, code.version, "versión")
                Check.equal(reference.mask, code.mask, "máscara elegida")
                val actual = rows(code)
                for (r in reference.rows.indices) Check.equal(reference.rows[r], actual[r], "fila $r")
            }
        }

        test("REGRESION: no reproduce la variante con los bits de formato traspuestos") {
            val code = QrCode.encode(QrReferenceCodes.readable[0].text)
            Check.isTrue(rows(code) != QrReferenceCodes.transposedFormat, "coincide con la versión ilegible")
        }

        test("los bits de formato de las dos copias coinciden y pasan la comprobación BCH") {
            for (text in listOf("http://127.0.0.1:8080/", "http://192.168.100.123:65535/", "http://10.0.0.1:1024/x")) {
                val code = QrCode.encode(text)
                val n = code.size
                var first = 0
                for (i in 0..5) if (code[i, 8]) first = first or (1 shl i)
                if (code[7, 8]) first = first or (1 shl 6)
                if (code[8, 8]) first = first or (1 shl 7)
                if (code[8, 7]) first = first or (1 shl 8)
                for (i in 9 until 15) if (code[8, 14 - i]) first = first or (1 shl i)
                var second = 0
                for (i in 0 until 8) if (code[8, n - 1 - i]) second = second or (1 shl i)
                for (i in 8 until 15) if (code[n - 15 + i, 8]) second = second or (1 shl i)
                Check.equal(first, second, "copias del formato para $text")
                val raw = first xor 0x5412
                var rem = raw
                for (i in 14 downTo 10) if (rem and (1 shl i) != 0) rem = rem xor (0x537 shl (i - 10))
                Check.equal(0, rem, "resto BCH para $text")
                Check.equal(0, (raw ushr 13) and 3, "nivel de corrección M para $text")
                Check.isTrue(code[n - 8, 8], "módulo oscuro fijo")
            }
        }

        test("elige la versión mínima y rechaza lo que no cabe") {
            Check.equal(1, QrCode.encode("http://a.b/").version, "texto corto")
            Check.equal(6, QrCode.encode("x".repeat(QrCode.MAX_BYTES)).version, "límite exacto")
            Check.throws<IllegalArgumentException>("106+1 bytes") { QrCode.encode("x".repeat(QrCode.MAX_BYTES + 1)) }
        }

        test("los patrones de búsqueda están en las tres esquinas") {
            val code = QrCode.encode("http://192.168.43.1:8080/")
            val n = code.size
            for ((r0, c0) in listOf(0 to 0, 0 to n - 7, n - 7 to 0)) {
                for (d in 0 until 7) {
                    Check.isTrue(code[r0, c0 + d] && code[r0 + 6, c0 + d], "bordes horizontales ($r0,$c0)")
                    Check.isTrue(code[r0 + d, c0] && code[r0 + d, c0 + 6], "bordes verticales ($r0,$c0)")
                }
                Check.isFalse(code[r0 + 1, c0 + 1], "anillo claro ($r0,$c0)")
                Check.isTrue(code[r0 + 3, c0 + 3], "centro oscuro ($r0,$c0)")
            }
        }
    }
}
