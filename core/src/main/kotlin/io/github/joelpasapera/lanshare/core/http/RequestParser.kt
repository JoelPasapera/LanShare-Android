package io.github.joelpasapera.lanshare.core.http

/** Petición imposible de atender; [status] es la respuesta que corresponde. */
class HttpParseException(val status: Int, message: String) : Exception(message)

/** Línea de petición y cabeceras ya validadas, sin el cuerpo. */
internal class RequestHead(
    val method: String,
    val target: String,
    val rawPath: String,
    val query: String,
    val version: HttpVersion,
    val headers: HttpHeaders,
    val contentLength: Long,
)

/**
 * Analizador de la cabecera de una petición HTTP/1.x (RFC 9112), escrito a mano
 * sobre los bytes: sin expresiones regulares ni cadenas intermedias por línea.
 *
 * Es estricto donde la holgura abre la puerta al contrabando de peticiones
 * (espacios antes de los dos puntos, líneas plegadas, Content-Length
 * contradictorios, Transfer-Encoding) y tolerante donde no cuesta nada (LF
 * sin CR).
 */
internal object RequestParser {
    const val MAX_TARGET_LENGTH = 8 * 1024
    const val MAX_HEADERS = 100

    fun parse(head: ByteArray): RequestHead {
        val lines = splitLines(head)
        if (lines.isEmpty()) throw HttpParseException(400, "Petición vacía")

        val requestLine = lines[0]
        val sp1 = requestLine.indexOf(' ')
        val sp2 = if (sp1 < 0) -1 else requestLine.indexOf(' ', sp1 + 1)
        if (sp1 <= 0 || sp2 < 0 || requestLine.indexOf(' ', sp2 + 1) >= 0) {
            throw HttpParseException(400, "Línea de petición mal formada")
        }

        val method = requestLine.substring(0, sp1)
        val target = requestLine.substring(sp1 + 1, sp2)
        val versionText = requestLine.substring(sp2 + 1)

        if (method.length > 16 || !method.all(::isTokenChar)) throw HttpParseException(400, "Método no válido")
        if (target.isEmpty() || target.length > MAX_TARGET_LENGTH) throw HttpParseException(414, "Destino demasiado largo")
        for (c in target) {
            if (c <= ' ' || c.code >= 0x7f) throw HttpParseException(400, "Destino con caracteres no válidos")
        }

        val version = when (versionText) {
            "HTTP/1.1" -> HttpVersion.HTTP_1_1
            "HTTP/1.0" -> HttpVersion.HTTP_1_0
            else -> {
                if (versionText.startsWith("HTTP/")) throw HttpParseException(505, "Versión de HTTP no admitida")
                throw HttpParseException(400, "Versión de HTTP no válida")
            }
        }

        val headers = HttpHeaders()
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) continue
            if (line[0] == ' ' || line[0] == '\t') throw HttpParseException(400, "Cabecera plegada (obsoleta)")
            val colon = line.indexOf(':')
            if (colon <= 0) throw HttpParseException(400, "Cabecera sin nombre")
            val name = line.substring(0, colon)
            if (!name.all(::isTokenChar)) throw HttpParseException(400, "Nombre de cabecera no válido")
            val value = line.substring(colon + 1).trim(' ', '\t')
            for (c in value) {
                if (c == '\u0000' || c == '\r' || c == '\n') throw HttpParseException(400, "Valor de cabecera no válido")
            }
            if (headers.size >= MAX_HEADERS) throw HttpParseException(431, "Demasiadas cabeceras")
            headers.add(name, value)
        }

        // RFC 9112 §3.2: sin Host en 1.1, con varias líneas Host o con un valor
        // que no es un nombre de equipo, 400. Con dos, cada intermediario podría
        // quedarse con una distinta.
        val hosts = headers.all("Host")
        if (hosts.size > 1) throw HttpParseException(400, "Varias cabeceras Host")
        if (hosts.isEmpty() && version == HttpVersion.HTTP_1_1) throw HttpParseException(400, "Falta la cabecera Host")
        if (hosts.isNotEmpty() && !isValidHost(hosts[0])) throw HttpParseException(400, "Cabecera Host no válida")
        if (headers.contains("Transfer-Encoding")) {
            throw HttpParseException(501, "Cuerpos con Transfer-Encoding no admitidos")
        }

        val contentLength = parseContentLength(headers)
        val (rawPath, query) = splitTarget(method, target)
        return RequestHead(method, target, rawPath, query, version, headers, contentLength)
    }

    private fun parseContentLength(headers: HttpHeaders): Long {
        val values = headers.all("Content-Length")
        if (values.isEmpty()) return 0
        var result = -1L
        for (raw in values) {
            for (part in raw.split(',')) {
                val text = part.trim()
                if (text.isEmpty() || text.length > 18 || !text.all { it in '0'..'9' }) {
                    throw HttpParseException(400, "Content-Length no válido")
                }
                val value = text.toLong()
                if (result >= 0 && value != result) throw HttpParseException(400, "Content-Length contradictorio")
                result = value
            }
        }
        return result
    }

    /** Separa ruta y consulta. Admite forma origen ("/a?b"), absoluta ("http://h/a") y "*" con OPTIONS. */
    private fun splitTarget(method: String, target: String): Pair<String, String> {
        var path = target
        if (target == "*") {
            if (method != "OPTIONS") throw HttpParseException(400, "'*' solo con OPTIONS")
            return Pair("*", "")
        }
        if (!target.startsWith("/")) {
            val scheme = target.indexOf("://")
            if (scheme <= 0) throw HttpParseException(400, "Destino no válido")
            val slash = target.indexOf('/', scheme + 3)
            path = if (slash < 0) "/" else target.substring(slash)
        }
        val hash = path.indexOf('#')
        if (hash >= 0) path = path.substring(0, hash)
        val q = path.indexOf('?')
        return if (q < 0) Pair(path, "") else Pair(path.substring(0, q), path.substring(q + 1))
    }

    /** Divide por LF y quita el CR final de cada línea. Los bytes se leen como ISO-8859-1. */
    private fun splitLines(head: ByteArray): List<String> {
        val lines = ArrayList<String>(16)
        var from = 0
        val n = head.size
        var i = 0
        while (i <= n) {
            if (i == n || head[i] == '\n'.code.toByte()) {
                var to = i
                if (to > from && head[to - 1] == '\r'.code.toByte()) to--
                lines.add(latin1(head, from, to))
                from = i + 1
            }
            i++
        }
        return lines
    }

    private fun latin1(bytes: ByteArray, from: Int, to: Int): String {
        val chars = CharArray(to - from)
        for (k in from until to) chars[k - from] = (bytes[k].toInt() and 0xFF).toChar()
        return String(chars)
    }

    /**
     * uri-host [ ":" port ] (RFC 3986 §3.2.2): letras, dígitos y "-._~" más
     * sub-delims y '%', o un literal IPv6 entre corchetes. Vacío vale (HTTP/1.1
     * lo permite cuando el destino no tiene autoridad).
     */
    private fun isValidHost(value: String): Boolean {
        if (value.length > 255) return false
        for (c in value) {
            val ok = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || "-._~!$&'()*+,;=%:[]".indexOf(c) >= 0
            if (!ok) return false
        }
        return true
    }

    /** tchar de RFC 9110 §5.6.2. */
    fun isTokenChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || "!#$%&'*+-.^_`|~".indexOf(c) >= 0
}
