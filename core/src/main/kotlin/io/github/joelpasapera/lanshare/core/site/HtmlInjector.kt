package io.github.joelpasapera.lanshare.core.site

/** Bytes por posición, sin cargar el documento entero: -1 fuera de rango. */
fun interface ByteSource {
    fun at(index: Long): Int
}

/**
 * Inserta un fragmento en una página HTML al servirla, sin tocar el archivo.
 * Portado de LanShare para Windows, pero trabajando sobre bytes: se buscan
 * las etiquetas como ASCII sin decodificar el documento, así que una página en
 * UTF-8, Latin-1 o Windows-1252 sale intacta salvo por lo insertado.
 *
 * Dónde: justo después de la apertura de &lt;head&gt;, para que el script se
 * ejecute antes que cualquier otro de la página. Para encontrarla se recorre
 * el principio del documento como lo haría el tokenizador de HTML, saltando
 * comentarios, el DOCTYPE y espacios, y solo se aceptan &lt;html&gt; y
 * &lt;head&gt; si son las primeras etiquetas. Buscar "&lt;head" en todo el
 * documento acertaría casi siempre, pero una página que omite &lt;head&gt;
 * (es opcional en HTML5) y escribe "&lt;head&gt;" dentro de un script recibiría
 * la etiqueta en mitad del código, y su "&lt;/script&gt;" lo cortaría.
 *
 * Nunca antes del DOCTYPE: el navegador pasaría a modo quirks y la página se
 * vería distinta solo por haber activado una opción.
 */
object HtmlInjector {

    /**
     * Posición donde insertar en un documento de [length] bytes:
     * - tras &lt;head …&gt; si es la primera etiqueta (o la siguiente a &lt;html&gt;);
     * - si no, justo antes de lo primero que no sea DOCTYPE, comentario,
     *   espacio o &lt;html …&gt; (el analizador abre ahí el &lt;head&gt; implícito,
     *   así que el script sigue yendo dentro de él).
     *
     * Casi siempre basta con leer unos cientos de bytes.
     */
    fun injectionPoint(source: ByteSource, length: Long): Long {
        var pos = 0L
        if (length >= 3 && source.at(0) == 0xEF && source.at(1) == 0xBB && source.at(2) == 0xBF) pos = 3
        var sawHtml = false
        while (true) {
            while (pos < length && isSpace(source.at(pos))) pos++
            if (pos >= length || source.at(pos) != LT) return pos
            val next = source.at(pos + 1)
            when {
                next == BANG -> {
                    val end = if (startsWith(source, pos, length, COMMENT_OPEN)) commentEnd(source, pos, length)
                    else indexOf(source, GT, pos + 2, length).let { if (it < 0) -1 else it + 1 } // DOCTYPE o comentario falso
                    if (end < 0) return pos
                    pos = end
                }
                next == QUESTION -> {
                    // "<?xml ...>" y similares: para HTML, un comentario falso hasta '>'.
                    val close = indexOf(source, GT, pos + 2, length)
                    if (close < 0) return pos
                    pos = close + 1
                }
                isAsciiLetter(next) -> {
                    val nameEnd = tagNameEnd(source, pos + 1, length)
                    val name = pos + 1
                    if (nameIs(source, name, nameEnd, HEAD)) {
                        val end = tagEnd(source, nameEnd, length)
                        return if (end < 0) pos else end
                    }
                    if (!sawHtml && nameIs(source, name, nameEnd, HTML)) {
                        val end = tagEnd(source, nameEnd, length)
                        if (end < 0) return pos
                        sawHtml = true
                        pos = end
                        continue
                    }
                    return pos
                }
                // Texto, una etiqueta de cierre suelta o un '<' que no abre nada.
                else -> return pos
            }
        }
    }

    fun injectHead(html: ByteArray, length: Int, snippet: ByteArray): ByteArray {
        val at = injectionPoint(arraySource(html, length), length.toLong()).toInt()
        val out = ByteArray(length + snippet.size)
        System.arraycopy(html, 0, out, 0, at)
        System.arraycopy(snippet, 0, out, at, snippet.size)
        System.arraycopy(html, at, out, at + snippet.size, length - at)
        return out
    }

    fun injectHead(html: String, snippet: String): String {
        val bytes = html.toByteArray(Charsets.UTF_8)
        return String(injectHead(bytes, bytes.size, snippet.toByteArray(Charsets.UTF_8)), Charsets.UTF_8)
    }

    fun arraySource(data: ByteArray, length: Int = data.size): ByteSource =
        ByteSource { i -> if (i in 0 until length) data[i.toInt()].toInt() and 0xFF else -1 }

    private const val LT = '<'.code
    private const val GT = '>'.code
    private const val BANG = '!'.code
    private const val QUESTION = '?'.code
    private const val DASH = '-'.code

    private val COMMENT_OPEN = "<!--".toByteArray(Charsets.US_ASCII)
    private val HEAD = "head".toByteArray(Charsets.US_ASCII)
    private val HTML = "html".toByteArray(Charsets.US_ASCII)

    /**
     * Fin de un comentario que empieza en [pos] ("&lt;!--"), como lo cierra el
     * tokenizador: "--&gt;", "--!&gt;" o los vacíos abruptos "&lt;!--&gt;" y
     * "&lt;!---&gt;". -1 si no termina.
     */
    private fun commentEnd(source: ByteSource, pos: Long, length: Long): Long {
        var i = pos + 4
        if (source.at(i) == GT) return i + 1
        if (source.at(i) == DASH && source.at(i + 1) == GT) return i + 2
        while (i < length) {
            if (source.at(i) == DASH && source.at(i + 1) == DASH) {
                val c = source.at(i + 2)
                if (c == GT) return i + 3
                if (c == BANG && source.at(i + 3) == GT) return i + 4
            }
            i++
        }
        return -1
    }

    private fun tagNameEnd(source: ByteSource, from: Long, length: Long): Long {
        var i = from
        while (i < length) {
            val c = source.at(i)
            if (isSpace(c) || c == '/'.code || c == GT) break
            i++
        }
        return i
    }

    /**
     * Posición tras el '&gt;' que cierra la etiqueta, con los atributos
     * entre comillas recorridos como tales: "&lt;html data-x="a&gt;b"&gt;" no
     * termina en el primer '&gt;'. -1 si el documento acaba antes.
     */
    private fun tagEnd(source: ByteSource, from: Long, length: Long): Long {
        var state = BETWEEN
        var i = from
        while (i < length) {
            val c = source.at(i)
            when (state) {
                BETWEEN -> when {
                    c == GT -> return i + 1
                    isSpace(c) || c == '/'.code -> Unit
                    else -> state = NAME
                }
                NAME -> when {
                    c == GT -> return i + 1
                    c == '='.code -> state = BEFORE_VALUE
                    isSpace(c) -> state = AFTER_NAME
                    c == '/'.code -> state = BETWEEN
                }
                AFTER_NAME -> when {
                    c == GT -> return i + 1
                    c == '='.code -> state = BEFORE_VALUE
                    isSpace(c) -> Unit
                    c == '/'.code -> state = BETWEEN
                    else -> state = NAME
                }
                BEFORE_VALUE -> when {
                    isSpace(c) -> Unit
                    c == '"'.code -> state = DOUBLE_QUOTED
                    c == '\''.code -> state = SINGLE_QUOTED
                    c == GT -> return i + 1
                    else -> state = UNQUOTED
                }
                DOUBLE_QUOTED -> if (c == '"'.code) state = BETWEEN
                SINGLE_QUOTED -> if (c == '\''.code) state = BETWEEN
                UNQUOTED -> when {
                    c == GT -> return i + 1
                    isSpace(c) -> state = BETWEEN
                }
            }
            i++
        }
        return -1
    }

    private const val BETWEEN = 0
    private const val NAME = 1
    private const val AFTER_NAME = 2
    private const val BEFORE_VALUE = 3
    private const val DOUBLE_QUOTED = 4
    private const val SINGLE_QUOTED = 5
    private const val UNQUOTED = 6

    /** El nombre en [from, until) es [expected] (en minúsculas), sin distinguir caja ASCII. */
    private fun nameIs(source: ByteSource, from: Long, until: Long, expected: ByteArray): Boolean {
        if (until - from != expected.size.toLong()) return false
        for (k in expected.indices) {
            var c = source.at(from + k)
            if (c in 'A'.code..'Z'.code) c += 32
            if (c != expected[k].toInt()) return false
        }
        return true
    }

    private fun startsWith(source: ByteSource, pos: Long, length: Long, prefix: ByteArray): Boolean {
        if (pos + prefix.size > length) return false
        for (k in prefix.indices) if (source.at(pos + k) != prefix[k].toInt()) return false
        return true
    }

    private fun indexOf(source: ByteSource, b: Int, from: Long, length: Long): Long {
        var i = from
        while (i < length) {
            if (source.at(i) == b) return i
            i++
        }
        return -1
    }

    /** Espacio para el tokenizador de HTML: tabulador, LF, FF, CR y espacio. */
    private fun isSpace(c: Int): Boolean = c == 0x20 || c == 0x09 || c == 0x0A || c == 0x0C || c == 0x0D

    private fun isAsciiLetter(c: Int): Boolean = c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code
}
