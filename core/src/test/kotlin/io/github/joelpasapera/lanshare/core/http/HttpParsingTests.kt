package io.github.joelpasapera.lanshare.core.http

import io.github.joelpasapera.lanshare.core.testkit.Check
import io.github.joelpasapera.lanshare.core.testkit.Suite
import java.io.InputStream

class HttpParsingTests : Suite("HTTP: análisis de peticiones") {

    /** Entrega los bytes de [chunk] en [chunk] para simular la red. */
    private class Trickle(private val data: ByteArray, private val chunk: Int) : InputStream() {
        private var pos = 0
        override fun read(): Int = if (pos < data.size) data[pos++].toInt() and 0xFF else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= data.size) return -1
            val n = minOf(len, chunk, data.size - pos)
            System.arraycopy(data, pos, b, off, n)
            pos += n
            return n
        }
    }

    private fun head(text: String, chunk: Int = 4096, capacity: Int = 1024): String? {
        val input = HttpInput(Trickle(text.toByteArray(Charsets.ISO_8859_1), chunk), capacity)
        return input.readHead(capacity)?.let { String(it, Charsets.ISO_8859_1) }
    }

    private fun parse(text: String): RequestHead = RequestParser.parse(text.toByteArray(Charsets.ISO_8859_1))

    init {
        test("lee la cabecera con CRLF y con LF solo, aunque llegue de byte en byte") {
            for (chunk in listOf(1, 2, 3, 7, 4096)) {
                Check.equal("GET / HTTP/1.1\r\nHost: a", head("GET / HTTP/1.1\r\nHost: a\r\n\r\nRESTO", chunk), "CRLF, trozos de $chunk")
                Check.equal("GET / HTTP/1.1\nHost: a", head("GET / HTTP/1.1\nHost: a\n\nRESTO", chunk), "LF, trozos de $chunk")
            }
        }

        test("descarta líneas vacías antes de la línea de petición") {
            Check.equal("GET / HTTP/1.1\r\nHost: a", head("\r\n\r\nGET / HTTP/1.1\r\nHost: a\r\n\r\n"), "CRLF previos")
        }

        test("null si la conexión se cierra antes de empezar") {
            Check.equal(null, head(""), "vacío")
            Check.equal(null, head("\r\n"), "solo saltos")
        }

        test("SEGURIDAD: una cabecera mayor que el máximo se rechaza sin crecer el búfer") {
            Check.throws<HeadTooLargeException>("cabecera infinita") { head("GET / HTTP/1.1\r\nX: " + "a".repeat(5000) + "\r\n\r\n", 512) }
        }

        test("peticiones encadenadas: el cuerpo y la siguiente petición salen del mismo búfer") {
            val raw = "POST /a HTTP/1.1\r\nHost: x\r\nContent-Length: 5\r\n\r\nhola!GET /b HTTP/1.1\r\nHost: x\r\n\r\n"
            for (chunk in listOf(1, 3, 4096)) {
                val input = HttpInput(Trickle(raw.toByteArray(), chunk), 1024)
                val first = RequestParser.parse(input.readHead(1024)!!)
                Check.equal(5L, first.contentLength, "longitud del cuerpo")
                val body = input.body(first.contentLength).readBytes()
                Check.equal("hola!", String(body), "cuerpo, trozos de $chunk")
                val second = RequestParser.parse(input.readHead(1024)!!)
                Check.equal("/b", second.rawPath, "segunda petición, trozos de $chunk")
            }
        }

        test("separa ruta y consulta; admite la forma absoluta y '*' con OPTIONS") {
            val a = parse("GET /a/b.js?v=1&x=2 HTTP/1.1\r\nHost: h")
            Check.equal("/a/b.js", a.rawPath, "ruta")
            Check.equal("v=1&x=2", a.query, "consulta")
            val b = parse("GET http://h:8080/x?y HTTP/1.1\r\nHost: h")
            Check.equal("/x", b.rawPath, "forma absoluta")
            Check.equal("y", b.query, "consulta en forma absoluta")
            Check.equal("*", parse("OPTIONS * HTTP/1.1\r\nHost: h").rawPath, "asterisco")
        }

        test("las cabeceras se buscan sin distinguir mayúsculas y se recortan") {
            val r = parse("GET / HTTP/1.1\r\nHOST:  ejemplo:80 \r\nAccept-Encoding: gzip, br\r\nConnection: Keep-Alive, Upgrade")
            Check.equal("ejemplo:80", r.headers["host"], "Host")
            Check.isTrue(r.headers.hasToken("connection", "keep-alive"), "token en lista")
            Check.isFalse(r.headers.hasToken("connection", "close"), "token ausente")
        }

        for ((name, raw, status) in listOf(
            Triple("método con caracteres no válidos", "G(T / HTTP/1.1\r\nHost: h", 400),
            Triple("dos espacios en la línea", "GET  / HTTP/1.1\r\nHost: h", 400),
            Triple("versión desconocida", "GET / HTTP/2.0\r\nHost: h", 505),
            Triple("versión inventada", "GET / XTTP/1.1\r\nHost: h", 400),
            Triple("espacio antes de los dos puntos", "GET / HTTP/1.1\r\nHost : h", 400),
            Triple("cabecera plegada", "GET / HTTP/1.1\r\nHost: h\r\n  sigue", 400),
            Triple("sin Host en HTTP/1.1", "GET / HTTP/1.1\r\nAccept: */*", 400),
            Triple("Transfer-Encoding", "POST / HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked", 501),
            Triple("Content-Length contradictorio", "POST / HTTP/1.1\r\nHost: h\r\nContent-Length: 5\r\nContent-Length: 6", 400),
            Triple("Content-Length negativo", "POST / HTTP/1.1\r\nHost: h\r\nContent-Length: -1", 400),
            Triple("destino relativo", "GET a/b HTTP/1.1\r\nHost: h", 400),
            Triple("asterisco con GET", "GET * HTTP/1.1\r\nHost: h", 400),
        )) {
            test("SEGURIDAD: rechaza $name con $status") {
                val e = Check.throws<HttpParseException>(name) { parse(raw) }
                Check.equal(status, e.status, "estado para $name")
            }
        }

        test("HTTP/1.0 sin Host es válido y no mantiene la conexión salvo que lo pida") {
            val r = parse("GET / HTTP/1.0\r\nAccept: */*")
            Check.equal(HttpVersion.HTTP_1_0, r.version, "versión")
        }
    }
}

class UrlPathTests : Suite("HTTP: rutas de URL") {

    private fun ok(raw: String): DecodedPath.Ok {
        val d = UrlPath.decode(raw)
        Check.isTrue(d is DecodedPath.Ok, "se esperaba ruta válida para $raw, motivo: ${(d as? DecodedPath.Rejected)?.reason}")
        return d as DecodedPath.Ok
    }

    private fun rejected(raw: String): Int {
        val d = UrlPath.decode(raw)
        Check.isTrue(d is DecodedPath.Rejected, "se esperaba rechazo para $raw")
        return (d as DecodedPath.Rejected).status
    }

    init {
        test("decodifica UTF-8 y conserva '+' y los puntos interiores") {
            val d = ok("/Docs/presentaci%C3%B3n%20final.html")
            Check.equal(listOf("Docs", "presentación final.html"), d.path.segments, "segmentos")
            Check.equal(listOf("a+b.js"), ok("/a+b.js").path.segments, "'+' no es espacio")
            Check.equal(listOf("v1.2.3"), ok("/v1.2.3").path.segments, "puntos interiores")
        }

        test("normaliza barras repetidas y '.'; la barra final marca carpeta") {
            Check.equal(listOf("a", "b"), ok("//a/./b").path.segments, "normalización")
            Check.isTrue(ok("/a/").trailingSlash, "barra final")
            Check.isFalse(ok("/a").trailingSlash, "sin barra")
            Check.isTrue(ok("/").path.isRoot && ok("/").trailingSlash, "raíz")
        }

        test("SEGURIDAD: '..' en bruto o codificado da 403") {
            Check.equal(403, rejected("/../etc/passwd"), "..")
            Check.equal(403, rejected("/a/%2e%2e/b"), "%2e%2e")
            Check.equal(403, rejected("/a/%2E%2E"), "%2E%2E")
        }

        test("SEGURIDAD: separadores y nulo codificados no fabrican rutas") {
            Check.equal(400, rejected("/a%2f..%2fb"), "%2f")
            Check.equal(400, rejected("/a%5cb"), "%5c")
            Check.equal(400, rejected("/a%00.html"), "%00")
            Check.equal(400, rejected("/a%0d%0aSet-Cookie:x"), "CRLF")
        }

        test("SEGURIDAD: escapes mal formados o UTF-8 inválido dan 400") {
            Check.equal(400, rejected("/a%zz"), "%zz")
            Check.equal(400, rejected("/a%4"), "% truncado")
            Check.equal(400, rejected("/a%C3"), "UTF-8 incompleto")
            Check.equal(400, rejected("/a%C0%AF"), "UTF-8 sobrelargo")
        }

        test("codifica segmentos para enlaces sin dejar pasar nada peligroso") {
            Check.equal("a%20b%22%3C%3E%23%3F", UrlPath.encodeSegment("a b\"<>#?"), "reservados")
            Check.equal("presentaci%C3%B3n.html", UrlPath.encodeSegment("presentación.html"), "UTF-8")
        }
    }
}

class HttpDateTests : Suite("HTTP: fechas") {
    init {
        test("formatea con el día de dos cifras y GMT") {
            Check.equal("Sun, 06 Nov 1994 08:49:37 GMT", HttpDate.format(784111777000L), "RFC 9110")
            Check.equal("Thu, 01 Jan 1970 00:00:00 GMT", HttpDate.format(0L), "época")
            Check.equal("Tue, 29 Feb 2028 23:59:59 GMT", HttpDate.format(1835481599000L), "bisiesto")
        }

        test("ida y vuelta en fechas variadas") {
            val samples = longArrayOf(0L, 784111777000L, 951782400000L, 1704067199000L, 1835481599000L, 4102444800000L)
            for (ms in samples) Check.equal(ms, HttpDate.parse(HttpDate.format(ms)), "ida y vuelta $ms")
            var t = 0L
            while (t < 4_000_000_000_000L) {
                Check.equal(t, HttpDate.parse(HttpDate.format(t)), "barrido $t")
                t += 86_399_000L * 37
            }
        }

        test("rechaza otros formatos sin lanzar") {
            Check.equal(null, HttpDate.parse("Sunday, 06-Nov-94 08:49:37 GMT"), "RFC 850")
            Check.equal(null, HttpDate.parse("Sun Nov  6 08:49:37 1994"), "asctime")
            Check.equal(null, HttpDate.parse("basura"), "basura")
        }
    }
}
