package io.github.joelpasapera.lanshare.core.server

import io.github.joelpasapera.lanshare.core.content.LocalFileTree
import io.github.joelpasapera.lanshare.core.content.RelativePath
import io.github.joelpasapera.lanshare.core.http.HttpServerConfig
import io.github.joelpasapera.lanshare.core.log.LogBus
import io.github.joelpasapera.lanshare.core.log.LogLevel
import io.github.joelpasapera.lanshare.core.net.LanAddresses
import io.github.joelpasapera.lanshare.core.site.ServerEngine
import io.github.joelpasapera.lanshare.core.site.ServerOptions
import io.github.joelpasapera.lanshare.core.site.ServerServices
import io.github.joelpasapera.lanshare.core.site.SiteDefinition
import io.github.joelpasapera.lanshare.core.testkit.Check
import io.github.joelpasapera.lanshare.core.testkit.Suite
import io.github.joelpasapera.lanshare.core.testkit.TempTree
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/** Respuesta leída a mano del socket, sin cliente HTTP de por medio. */
class RawResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
    fun header(name: String): String? = headers[name.lowercase()]
    val text: String get() = String(body, Charsets.UTF_8)
}

/** Cliente HTTP/1.1 mínimo sobre un socket: permite keep-alive y peticiones encadenadas. */
class RawClient(host: String, port: Int) : AutoCloseable {
    val socket = Socket()
    private val input: InputStream

    /** Respuestas "100 Continue" recibidas (se saltan al leer). */
    var continues = 0
        private set

    init {
        socket.connect(InetSocketAddress(host, port), 3000)
        socket.soTimeout = 5000
        input = socket.getInputStream()
    }

    fun send(raw: String) {
        socket.getOutputStream().write(raw.toByteArray(Charsets.ISO_8859_1))
        socket.getOutputStream().flush()
    }

    fun request(method: String, path: String, vararg headers: String, host: String = "127.0.0.1"): RawResponse {
        val sb = StringBuilder("$method $path HTTP/1.1\r\nHost: $host\r\n")
        for (h in headers) sb.append(h).append("\r\n")
        sb.append("\r\n")
        send(sb.toString())
        return read(method == "HEAD")
    }

    private fun readLine(): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (line.size() == 0) null else String(line.toByteArray(), Charsets.ISO_8859_1)
            if (b == '\n'.code) break
            if (b != '\r'.code) line.write(b)
        }
        return String(line.toByteArray(), Charsets.ISO_8859_1)
    }

    fun read(head: Boolean = false): RawResponse {
        while (true) {
            val statusLine = readLine() ?: throw EOFException("Conexión cerrada")
            val status = statusLine.split(' ')[1].toInt()
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine() ?: throw EOFException("Cabecera cortada")
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                headers[line.substring(0, colon).lowercase()] = line.substring(colon + 1).trim()
            }
            if (status == 100) {
                continues++
                continue
            }
            val length = headers["content-length"]?.toInt() ?: 0
            val body = ByteArray(if (head || status == 304 || status == 204) 0 else length)
            var read = 0
            while (read < body.size) {
                val n = input.read(body, read, body.size - read)
                if (n < 0) throw EOFException("Cuerpo cortado")
                read += n
            }
            return RawResponse(status, headers, body)
        }
    }

    /** true si el servidor cerró la conexión. */
    fun isClosedByServer(): Boolean = try {
        input.read() < 0
    } catch (e: Exception) {
        true
    }

    override fun close() {
        socket.close()
    }
}

class ServerIntegrationTests : Suite("Servidor: sockets reales") {

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private class Harness(
        val t: TempTree,
        options: ServerOptions = ServerOptions(),
        lan: Boolean = false,
        start: String? = "index.html",
    ) : AutoCloseable {
        val log = LogBus()
        val engine = ServerEngine(ServerServices(log), HttpServerConfig(idleTimeoutMs = 800, maxHeadBytes = 8 * 1024))
        val port: Int = ServerSocket(0).use { it.localPort }
        val site = SiteDefinition("demo", "Demo", port, lan, LocalFileTree(t.root, "demo"), start?.let { RelativePath.parse(it) })
        init {
            val status = engine.apply(listOf(site), options)
            check(status.single().running) { "no arrancó: ${status.single().error}" }
        }
        fun client(host: String = "127.0.0.1") = RawClient(host, port)
        override fun close() = engine.stop()
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    init {
        test("GET de la raíz: index.html con validadores y cabeceras de seguridad") {
            TempTree().use { t ->
                t.file("index.html", "<!doctype html><title>Hola</title>")
                Harness(t).use { h ->
                    h.client().use { c ->
                        val r = c.request("GET", "/")
                        Check.equal(200, r.status, "estado")
                        Check.equal("<!doctype html><title>Hola</title>", r.text, "cuerpo")
                        Check.equal("text/html; charset=utf-8", r.header("Content-Type"), "tipo")
                        Check.equal("nosniff", r.header("X-Content-Type-Options"), "nosniff")
                        Check.equal("no-cache", r.header("Cache-Control"), "revalidar")
                        Check.notNull(r.header("ETag"), "ETag")
                        Check.notNull(r.header("Last-Modified"), "Last-Modified")
                        Check.equal("bytes", r.header("Accept-Ranges"), "rangos")
                    }
                }
            }
        }

        test("keep-alive: HEAD, 304 y GET seguidos por la misma conexión") {
            TempTree().use { t ->
                t.file("index.html", "x".repeat(1000))
                Harness(t).use { h ->
                    h.client().use { c ->
                        val head = c.request("HEAD", "/index.html")
                        Check.equal("1000", head.header("Content-Length"), "HEAD declara la longitud")
                        val etag = head.header("ETag")!!
                        val cached = c.request("GET", "/index.html", "If-None-Match: $etag")
                        Check.equal(304, cached.status, "304")
                        val full = c.request("GET", "/index.html")
                        Check.equal(1000, full.body.size, "GET tras 304 en la misma conexión")
                    }
                }
            }
        }

        test("peticiones encadenadas se responden en orden") {
            TempTree().use { t ->
                t.file("a.txt", "AAA")
                t.file("b.txt", "BB")
                Harness(t).use { h ->
                    h.client().use { c ->
                        c.send("GET /a.txt HTTP/1.1\r\nHost: x\r\n\r\nGET /b.txt HTTP/1.1\r\nHost: x\r\n\r\n")
                        Check.equal("AAA", c.read().text, "primera")
                        Check.equal("BB", c.read().text, "segunda")
                    }
                }
            }
        }

        test("rangos: 206 con Content-Range, bytes exactos, y 416 fuera del archivo") {
            TempTree().use { t ->
                val data = ByteArray(300_000) { (it * 31 % 251).toByte() }
                t.file("video.mp4", data)
                Harness(t).use { h ->
                    h.client().use { c ->
                        val r = c.request("GET", "/video.mp4", "Range: bytes=1000-200999")
                        Check.equal(206, r.status, "206")
                        Check.equal("bytes 1000-200999/300000", r.header("Content-Range"), "Content-Range")
                        Check.equal(sha(data.copyOfRange(1000, 201000)), sha(r.body), "bytes del rango")
                        val tail = c.request("GET", "/video.mp4", "Range: bytes=-10")
                        Check.isTrue(data.copyOfRange(299_990, 300_000).contentEquals(tail.body), "sufijo")
                        val bad = c.request("GET", "/video.mp4", "Range: bytes=400000-")
                        Check.equal(416, bad.status, "416")
                        Check.equal("bytes */300000", bad.header("Content-Range"), "Content-Range de 416")
                        Check.equal("text/html; charset=utf-8", bad.header("Content-Type"), "la página de error no hereda video/mp4")
                        Check.equal(null, bad.header("ETag"), "ni el ETag del vídeo")
                        Check.equal(null, bad.header("Last-Modified"), "ni su fecha")
                        val stale = c.request("GET", "/video.mp4", "Range: bytes=0-9", "If-Range: \"otro\"")
                        Check.equal(200, stale.status, "If-Range caducado sirve todo")
                    }
                }
            }
        }

        test("redirección de carpeta, listado y 404") {
            TempTree().use { t ->
                t.file("index.html", "i")
                t.file("fotos/a b.jpg", "x")
                Harness(t).use { h ->
                    h.client().use { c ->
                        val r = c.request("GET", "/fotos?x=1")
                        Check.equal(301, r.status, "301")
                        Check.equal("/fotos/?x=1", r.header("Location"), "Location")
                        val listing = c.request("GET", "/fotos/")
                        Check.contains(listing.text, "href=\"a%20b.jpg\"", "enlace codificado")
                        val missing = c.request("GET", "/no-existe.js")
                        Check.equal(404, missing.status, "404")
                        Check.contains(missing.text, "No encontrado", "página de error")
                    }
                }
            }
        }

        test("SEGURIDAD: path traversal da 403 y queda en el registro como seguridad") {
            TempTree().use { t ->
                t.file("index.html", "i")
                Harness(t).use { h ->
                    h.client().use { c ->
                        Check.equal(403, c.request("GET", "/../../etc/passwd").status, "403")
                        Check.equal(403, c.request("GET", "/%2e%2e/%2e%2e/etc/passwd").status, "codificado")
                    }
                    Check.isTrue(h.log.snapshot().any { it.level == LogLevel.SECURITY }, "línea de seguridad")
                }
            }
        }

        test("SEGURIDAD: línea mal formada da 400 y cierra; cabecera enorme da 431") {
            TempTree().use { t ->
                t.file("index.html", "i")
                Harness(t).use { h ->
                    h.client().use { c ->
                        c.send("GET  / HTTP/1.1\r\nHost: x\r\n\r\n")
                        Check.equal(400, c.read().status, "400")
                        Check.isTrue(c.isClosedByServer(), "conexión cerrada")
                    }
                    h.client().use { c ->
                        c.send("GET / HTTP/1.1\r\nHost: x\r\nX: " + "a".repeat(10_000) + "\r\n\r\n")
                        Check.equal(431, c.read().status, "431")
                    }
                }
            }
        }

        test("SEGURIDAD: una cabecera a medias recibe 408 y se cierra al agotar el tiempo") {
            TempTree().use { t ->
                t.file("index.html", "i")
                Harness(t).use { h ->
                    h.client().use { c ->
                        c.send("GET / HTTP/1.1\r\nHost:")
                        val started = System.nanoTime()
                        Check.equal(408, c.read().status, "408")
                        Check.isTrue(c.isClosedByServer(), "cerrada")
                        Check.isTrue((System.nanoTime() - started) / 1_000_000 < 4000, "dentro del plazo")
                    }
                }
            }
        }

        test("consola remota: inyecta la etiqueta, sirve el script y recibe lotes del mismo origen") {
            TempTree().use { t ->
                t.file("index.html", "<!doctype html><html><head><title>x</title></head><body></body></html>")
                Harness(t, ServerOptions(remoteConsole = true)).use { h ->
                    h.client().use { c ->
                        val page = c.request("GET", "/")
                        Check.contains(page.text, "<head><script src=\"/__consola.js\"></script><title>", "etiqueta inyectada")
                        Check.equal(page.body.size.toString(), page.header("Content-Length"), "longitud recalculada")
                        Check.notContains(page.header("Accept-Ranges") ?: "", "bytes", "sin rangos sobre la versión transformada")
                        val script = c.request("GET", "/__consola.js")
                        Check.contains(script.text, "__lanshareConsole", "script")
                        val body = """{"p":"/","d":0,"e":[{"l":"error","m":"Fallo de prueba","s":"/app.js:3:9","k":"","n":1}]}"""
                        val origin = "http://127.0.0.1:${h.port}"
                        c.send("POST /__consola HTTP/1.1\r\nHost: 127.0.0.1:${h.port}\r\nOrigin: $origin\r\n" +
                            "Content-Type: text/plain\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body")
                        Check.equal(204, c.read().status, "204")
                        c.send("POST /__consola HTTP/1.1\r\nHost: 127.0.0.1:${h.port}\r\nOrigin: https://malo.com\r\n" +
                            "Sec-Fetch-Site: cross-site\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body")
                        Check.equal(403, c.read().status, "otro origen")
                    }
                    Check.isTrue(h.log.snapshot().any { it.level == LogLevel.JS_ERROR && it.text.contains("Fallo de prueba  @ /app.js:3:9") }, "en el registro")
                }
            }
        }

        test("consola desactivada: ni inyección ni punto de entrada") {
            TempTree().use { t ->
                t.file("index.html", "<head></head>")
                Harness(t).use { h ->
                    h.client().use { c ->
                        Check.equal("<head></head>", c.request("GET", "/").text, "página intacta")
                        Check.equal(404, c.request("GET", "/__consola.js").status, "sin script")
                    }
                }
            }
        }

        test("aislamiento de origen y CORS cuando el sitio los pide") {
            TempTree().use { t ->
                t.file("index.html", "i")
                val log = LogBus()
                val engine = ServerEngine(ServerServices(log))
                val port = freePort()
                engine.apply(listOf(SiteDefinition("s", "S", port, false, LocalFileTree(t.root), RelativePath.parse("index.html"),
                    cors = true, crossOriginIsolation = true)), ServerOptions())
                try {
                    RawClient("127.0.0.1", port).use { c ->
                        val r = c.request("GET", "/")
                        Check.equal("same-origin", r.header("Cross-Origin-Opener-Policy"), "COOP")
                        Check.equal("require-corp", r.header("Cross-Origin-Embedder-Policy"), "COEP")
                        Check.equal("*", r.header("Access-Control-Allow-Origin"), "CORS")
                        val pre = c.request("OPTIONS", "/", "Access-Control-Request-Headers: x-a")
                        Check.equal(204, pre.status, "preflight")
                        Check.equal("x-a", pre.header("Access-Control-Allow-Headers"), "cabeceras pedidas")
                    }
                } finally {
                    engine.stop()
                }
            }
        }

        test("gzip solo para clientes de la red, con caché y Vary") {
            val lanIp = LanAddresses.discover().firstOrNull()?.address
            if (lanIp == null) {
                println("      (sin interfaz de red local: prueba de gzip omitida)")
                return@test
            }
            TempTree().use { t ->
                val css = "body{color:red}\n".repeat(400)
                t.file("index.html", "i")
                t.file("app.css", css)
                Harness(t, lan = true).use { h ->
                    h.client(lanIp).use { c ->
                        val first = c.request("GET", "/app.css", "Accept-Encoding: gzip, br", host = "$lanIp:${h.port}")
                        Check.equal("gzip", first.header("Content-Encoding"), "comprimido")
                        Check.equal("Accept-Encoding", first.header("Vary"), "Vary")
                        Check.equal(css, String(GZIPInputStream(first.body.inputStream()).readBytes()), "descomprime igual")
                        c.request("GET", "/app.css", "Accept-Encoding: gzip", host = "$lanIp:${h.port}")
                    }
                    h.client().use { c ->
                        val local = c.request("GET", "/app.css", "Accept-Encoding: gzip")
                        Check.equal(null, local.header("Content-Encoding"), "en 127.0.0.1 no se comprime")
                    }
                    Check.isTrue(h.log.snapshot().any { it.text.contains("(caché)") }, "segunda vez desde la caché")
                }
            }
        }

        test("alcance local: el puerto no responde por la IP de la red") {
            val lanIp = LanAddresses.discover().firstOrNull()?.address ?: return@test
            TempTree().use { t ->
                t.file("index.html", "i")
                Harness(t, lan = false).use { h ->
                    Check.throws<Exception>("conexión por la IP de red") { RawClient(lanIp, h.port).use { it.request("GET", "/") } }
                }
            }
        }

        test("Expect: 100-continue recibe la respuesta provisional") {
            TempTree().use { t ->
                t.file("index.html", "i")
                Harness(t, ServerOptions(remoteConsole = true)).use { h ->
                    h.client().use { c ->
                        c.send("POST /__consola HTTP/1.1\r\nHost: 127.0.0.1:${h.port}\r\nExpect: 100-continue\r\nContent-Length: 2\r\n\r\n")
                        c.send("{}")
                        Check.equal(204, c.read().status, "respuesta final tras el 100")
                    }
                }
            }
        }

        test("en caliente: cambiar el archivo de inicio no reabre el puerto") {
            TempTree().use { t ->
                t.file("index.html", "uno")
                t.file("otra.html", "dos")
                Harness(t).use { h ->
                    h.client().use { c ->
                        Check.equal("uno", c.request("GET", "/").text, "antes")
                        h.engine.apply(listOf(SiteDefinition("demo", "Demo", h.port, false, LocalFileTree(t.root), RelativePath.parse("otra.html"))), ServerOptions())
                        Check.equal("dos", c.request("GET", "/").text, "después, por la misma conexión")
                    }
                }
            }
        }

        test("puerto ocupado por otra aplicación: error claro y el resto sigue") {
            TempTree().use { t ->
                t.file("index.html", "i")
                ServerSocket().use { blocker ->
                    blocker.bind(InetSocketAddress("127.0.0.1", 0))
                    val busy = blocker.localPort
                    val free = freePort()
                    val engine = ServerEngine(ServerServices(LogBus()))
                    try {
                        val st = engine.apply(listOf(
                            SiteDefinition("a", "A", busy, false, LocalFileTree(t.root), RelativePath.parse("index.html")),
                            SiteDefinition("b", "B", free, false, LocalFileTree(t.root), RelativePath.parse("index.html")),
                        ), ServerOptions())
                        Check.isFalse(st[0].running, "el ocupado no arranca")
                        Check.contains(st[0].error ?: "", "ya lo usa otra aplicación", "mensaje")
                        Check.isTrue(st[1].running, "el otro sí")
                    } finally {
                        engine.stop()
                    }
                }
            }
        }

        test("carga concurrente: 24 clientes descargan 1 MB a la vez sin errores") {
            TempTree().use { t ->
                val data = ByteArray(1_048_576) { (it * 7 + 3).toByte() }
                t.file("index.html", "i")
                t.file("big.bin", data)
                val expected = sha(data)
                Harness(t).use { h ->
                    val pool = Executors.newFixedThreadPool(24)
                    try {
                        val futures = (1..24).map {
                            pool.submit(Callable { h.client().use { c -> sha(c.request("GET", "/big.bin").body) } })
                        }
                        for (f in futures) Check.equal(expected, f.get(20, TimeUnit.SECONDS), "contenido")
                    } finally {
                        pool.shutdownNow()
                    }
                }
            }
        }

        test("detener cierra los puertos") {
            TempTree().use { t ->
                t.file("index.html", "i")
                val h = Harness(t)
                h.close()
                Check.throws<Exception>("conexión tras detener") { RawClient("127.0.0.1", h.port).use { it.request("GET", "/") } }
            }
        }
    }
}
