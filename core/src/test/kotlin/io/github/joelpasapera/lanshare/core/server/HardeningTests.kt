package io.github.joelpasapera.lanshare.core.server

import io.github.joelpasapera.lanshare.core.cache.ByteBudget
import io.github.joelpasapera.lanshare.core.clients.ClientRegistry
import io.github.joelpasapera.lanshare.core.clients.UaParser
import io.github.joelpasapera.lanshare.core.compress.ByteSink
import io.github.joelpasapera.lanshare.core.compress.GzipEncoder
import io.github.joelpasapera.lanshare.core.content.ContentTree
import io.github.joelpasapera.lanshare.core.content.DirEntry
import io.github.joelpasapera.lanshare.core.content.LocalFileTree
import io.github.joelpasapera.lanshare.core.content.OpenedFile
import io.github.joelpasapera.lanshare.core.content.Probe
import io.github.joelpasapera.lanshare.core.content.RelativePath
import io.github.joelpasapera.lanshare.core.http.HttpEndpoint
import io.github.joelpasapera.lanshare.core.http.HttpParseException
import io.github.joelpasapera.lanshare.core.http.HttpServer
import io.github.joelpasapera.lanshare.core.http.HttpServerConfig
import io.github.joelpasapera.lanshare.core.http.HttpServerEvents
import io.github.joelpasapera.lanshare.core.http.RequestParser
import io.github.joelpasapera.lanshare.core.log.LogBus
import io.github.joelpasapera.lanshare.core.log.LogLevel
import io.github.joelpasapera.lanshare.core.net.HostGuard
import io.github.joelpasapera.lanshare.core.net.LanAddresses
import io.github.joelpasapera.lanshare.core.net.NetworkView
import io.github.joelpasapera.lanshare.core.net.PeerPolicy
import io.github.joelpasapera.lanshare.core.net.Subnet
import io.github.joelpasapera.lanshare.core.site.GzipVariants
import io.github.joelpasapera.lanshare.core.site.ServerEngine
import io.github.joelpasapera.lanshare.core.site.ServerOptions
import io.github.joelpasapera.lanshare.core.site.ServerServices
import io.github.joelpasapera.lanshare.core.site.SiteDefinition
import io.github.joelpasapera.lanshare.core.site.SiteHandler
import io.github.joelpasapera.lanshare.core.testkit.Check
import io.github.joelpasapera.lanshare.core.testkit.Suite
import io.github.joelpasapera.lanshare.core.testkit.TempTree
import java.io.EOFException
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.Random
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream

/** Lo que encontró la revisión de seguridad del núcleo, pieza por pieza. */
class HardeningUnitTests : Suite("Endurecimiento: piezas") {
    init {
        test("SEGURIDAD: un User-Agent hostil no cuesta tiempo (ReDoS) y los reales se siguen entendiendo") {
            val evil = "Mozilla/5.0 (Linux; Android;" + " ".repeat(30_000) + "x"
            val started = System.nanoTime()
            UaParser.device(evil)
            UaParser.browser(evil)
            val ms = (System.nanoTime() - started) / 1_000_000
            Check.isTrue(ms < 250, "tardó $ms ms")
            Check.equal("Android 13 · SM-S911B",
                UaParser.device("Mozilla/5.0 (Linux; Android 13; SM-S911B Build/TP1A.220624.014; wv) AppleWebKit/537.36"), "modelo antes de Build/")
            Check.equal("Android 4.0.3 · LG-L160L",
                UaParser.device("Mozilla/5.0 (Linux; U; Android 4.0.3; ko-kr; LG-L160L Build/IML74K) AppleWebKit/534.30"), "con idioma")
            Check.equal("Android 9 · moto g(7",
                UaParser.device("Mozilla/5.0 (Linux; Android 9; moto g(7) power) AppleWebKit/537.36"), "igual que antes con paréntesis")
        }

        test("clientes: el User-Agent se recorta y se analiza fuera del candado") {
            val registry = ClientRegistry()
            registry.record("10.0.0.9", false, 8080, "Mozilla/5.0 (Linux; Android 14; Pixel 8) " + "z".repeat(10_000), 0, 1)
            Check.equal("Android 14 · Pixel 8", registry.snapshot().single().device, "dispositivo")
        }

        test("SEGURIDAD: solo la red local llega a un puerto «en la red»") {
            for (ok in listOf("10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.5", "169.254.10.20", "127.0.0.1", "::1", "fe80::1", "fd12:3456::1")) {
                Check.isTrue(PeerPolicy.isLocalNetwork(InetAddress.getByName(ok)), "$ok es de la red local")
            }
            for (bad in listOf("8.8.8.8", "100.64.0.1", "100.127.255.254", "172.32.0.1", "192.0.2.10", "2001:db8::1", "2800:200::1")) {
                Check.isFalse(PeerPolicy.isLocalNetwork(InetAddress.getByName(bad)), "$bad no es de la red local")
            }
            val mapped = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, -1)
            Check.isTrue(PeerPolicy.isLocalNetwork(Inet6Address.getByAddress(null, mapped + byteArrayOf(192.toByte(), 168.toByte(), 1, 5), -1)), "::ffff:192.168.1.5")
            Check.isFalse(PeerPolicy.isLocalNetwork(Inet6Address.getByAddress(null, mapped + byteArrayOf(8, 8, 8, 8), -1)), "::ffff:8.8.8.8")
        }

        test("PeerPolicy: el propio equipo vale por cualquiera de sus direcciones, consultadas como mucho cada pocos segundos") {
            var calls = 0
            val own = InetAddress.getByName("203.0.113.7")
            val policy = PeerPolicy(view = { calls++; NetworkView(setOf(own), emptyList()) }, refreshNanos = 60_000_000_000L)
            Check.isTrue(policy.allows(own), "dirección propia")
            for (i in 1..50) Check.isFalse(policy.allows(InetAddress.getByName("198.51.100.$i")), "ajena $i")
            Check.equal(1, calls, "una sola consulta de interfaces para 51 conexiones")
        }

        test("PeerPolicy: una Wi-Fi con direcciones públicas (algunas universidades) sigue siendo la red local") {
            val wifi4 = Subnet(InetAddress.getByName("128.2.10.5"), 16)
            val wifi6 = Subnet(InetAddress.getByName("2001:db8:1:2::5"), 64)
            val odd = Subnet(InetAddress.getByName("128.2.10.5"), 21)
            val policy = PeerPolicy(view = { NetworkView(emptySet(), listOf(wifi4, wifi6)) })
            Check.isTrue(policy.allows(InetAddress.getByName("128.2.99.1")), "misma subred IPv4")
            Check.isFalse(policy.allows(InetAddress.getByName("128.3.0.1")), "otra subred IPv4")
            Check.isTrue(policy.allows(InetAddress.getByName("2001:db8:1:2::99")), "misma /64")
            Check.isFalse(policy.allows(InetAddress.getByName("2001:db8:1:3::1")), "otra /64")
            Check.isTrue(odd.contains(InetAddress.getByName("128.2.15.200").address), "/21 por dentro")
            Check.isFalse(odd.contains(InetAddress.getByName("128.2.16.1").address), "/21 por fuera")
        }

        test("SEGURIDAD: Host repetido o con caracteres raros da 400; nombres locales sí, dominios de Internet no") {
            Check.throws<HttpParseException>("dos Host") { RequestParser.parse("GET / HTTP/1.1\r\nHost: a\r\nHost: b".toByteArray()) }
            Check.throws<HttpParseException>("Host con espacio") { RequestParser.parse("GET / HTTP/1.1\r\nHost: a b".toByteArray()) }
            Check.throws<HttpParseException>("Host con barra") { RequestParser.parse("GET / HTTP/1.1\r\nHost: a/b".toByteArray()) }
            for (ok in listOf("127.0.0.1:8080", "localhost", "[::1]:8080", "192.168.1.5:8080", "movil.local", "router.home", "nas.localdomain", "x")) {
                Check.isTrue(HostGuard.isLocalHostName(ok), "local: $ok")
            }
            for (bad in listOf("evil.com", "evil.com:8080", "127.0.0.1.nip.io", "home.evil.com")) {
                Check.isFalse(HostGuard.isLocalHostName(bad), "de Internet: $bad")
            }
        }

        test("Accept-Encoding: q=0 en cualquier forma desactiva gzip") {
            for (h in listOf("gzip", "gzip, deflate, br", "GZIP;q=0.5", "x-gzip", "deflate, *;q=0.1", "br;q=1, gzip;q=0.001")) {
                Check.isTrue(SiteHandler.acceptsGzip(h), "acepta: $h")
            }
            for (h in listOf(null, "", "identity", "br", "gzip;q=0", "gzip;Q=0", "gzip; q=0.000", "gzip;x=1;q=0", "*;q=0", "gzip;q=0, *", "gzip;q=abc", "gzip;q=2")) {
                Check.isFalse(SiteHandler.acceptsGzip(h), "no acepta: $h")
            }
        }

        test("gzip propio: lo que comprime lo descomprime java.util.zip, también vacío y a trozos") {
            for (size in listOf(0, 1, 1000, 200_000)) {
                val data = ByteArray(size) { (it % 97 + it / 1000).toByte() }
                val sink = ByteSink(16, Int.MAX_VALUE - 16)
                val encoder = GzipEncoder(sink)
                var off = 0
                while (off < size) {
                    val n = minOf(777, size - off)
                    encoder.write(data, off, n)
                    off += n
                }
                encoder.close()
                Check.isTrue(GZIPInputStream(sink.toByteArray().inputStream()).readBytes().contentEquals(data), "$size bytes")
            }
            val hola = "hola".toByteArray()
            Check.isTrue(GZIPInputStream(GzipEncoder.compress(hola).inputStream()).readBytes().contentEquals(hola), "compress")
        }

        test("presupuesto de bytes: nunca se pasa, ni con muchos hilos") {
            val budget = ByteBudget(1000)
            Check.isTrue(budget.tryAcquire(600), "600")
            Check.isFalse(budget.tryAcquire(500), "no caben 500 más")
            budget.release(600)
            val over = AtomicInteger()
            val threads = (1..8).map {
                Thread {
                    repeat(20_000) {
                        if (budget.tryAcquire(300)) {
                            if (budget.inUse > 1000) over.incrementAndGet()
                            budget.release(300)
                        }
                    }
                }.apply { start() }
            }
            threads.forEach { it.join() }
            Check.equal(0, over.get(), "nunca por encima del tope")
            Check.equal(0L, budget.inUse, "todo devuelto")
        }

        test("SEGURIDAD: gzip sin presupuesto o ya en curso se sirve sin comprimir; lo que no compensa se recuerda") {
            val text = "body{color:red}\n".repeat(4000).toByteArray()
            val size = text.size.toLong()
            val tight = GzipVariants(cacheBytes = 1L shl 20, inFlightBytes = 1000)
            Check.equal(null, tight.obtain("k", size, false) { it.write(text) }, "sin presupuesto")

            val g = GzipVariants(cacheBytes = 1L shl 20, inFlightBytes = 8L shl 20)
            var inner: GzipVariants.Compressed? = GzipVariants.Compressed(ByteArray(0), false)
            val outer = g.obtain("k", size, false) { out ->
                inner = g.obtain("k", size, false) { it.write(text) }
                out.write(text)
            }
            Check.equal(null, inner, "la misma clave en curso: sin esperar y sin comprimir dos veces")
            Check.notNull(outer, "la primera sí")
            Check.isTrue(GZIPInputStream(outer!!.bytes.inputStream()).readBytes().contentEquals(text), "descomprime igual")
            Check.isTrue(g.obtain("k", size, true) { error("no debe comprimir") }!!.fromCache, "después, de la caché (también para HEAD)")
            Check.equal(null, g.obtain("otra", size, true) { error("HEAD no comprime") }, "HEAD sin caché: sin comprimir")

            val noise = ByteArray(50_000).also { Random(7).nextBytes(it) }
            var attempts = 0
            repeat(3) {
                Check.equal(null, g.obtain("ruido", noise.size.toLong(), false) { attempts++; it.write(noise) }, "ruido")
            }
            Check.equal(1, attempts, "lo que no compensa solo se intenta una vez")
            Check.equal(0L, g.bytesInFlight, "presupuesto devuelto")

            g.forget("k")
            Check.equal(null, g.obtain("k", size, true) { }, "olvidado al reinstalar")
        }
    }
}

/** Lo mismo, con sockets reales. */
class HardeningServerTests : Suite("Endurecimiento: servidor") {

    private class Site(
        tree: ContentTree,
        options: ServerOptions = ServerOptions(),
        config: HttpServerConfig = HttpServerConfig(idleTimeoutMs = 2_000),
        lan: Boolean = false,
    ) : AutoCloseable {
        val log = LogBus()
        val engine = ServerEngine(ServerServices(log), config)
        val port: Int = ServerSocket(0).use { it.localPort }

        init {
            val st = engine.apply(listOf(SiteDefinition("s", "S", port, lan, tree, RelativePath.parse("index.html"))), options)
            check(st.single().running) { st.single().error ?: "" }
        }

        fun client(host: String = "127.0.0.1") = RawClient(host, port)

        fun logged(text: String): Boolean = log.snapshot().any { it.text.contains(text) }

        override fun close() = engine.stop()
    }

    /** Árbol con un archivo que deja de poder leerse a partir de [failAt] bytes (permiso retirado, tarjeta fuera). */
    private class FailingTree(private val failAt: Long, private val size: Long) : ContentTree {
        override val displayName = "falla"

        override fun probe(path: RelativePath): Probe {
            if (path.name != "roto.bin") return Probe.Missing
            return Probe.File(object : OpenedFile {
                override val size = this@FailingTree.size
                override val lastModifiedMillis = 1_000L
                override val versionStamp = 1L
                override fun read(dst: ByteBuffer, position: Long): Int {
                    if (position >= failAt) throw IOException("tarjeta extraída")
                    var n = 0
                    while (dst.hasRemaining() && position + n < failAt) {
                        dst.put(7)
                        n++
                    }
                    return n
                }
                override fun close() {}
            })
        }

        override fun list(path: RelativePath): List<DirEntry>? = null
    }

    private fun lanIpOrNull(): String? = LanAddresses.discover().firstOrNull()?.address

    init {
        test("SEGURIDAD: DNS rebinding: un nombre de Internet en Host recibe 421 y nada del sitio") {
            TempTree().use { t ->
                t.file("index.html", "inicio")
                t.file("notas/secreto.txt", "x")
                Site(LocalFileTree(t.root)).use { s ->
                    s.client().use { c ->
                        val r = c.request("GET", "/notas/", host = "atacante.example:${s.port}")
                        Check.equal(421, r.status, "421")
                        Check.notContains(r.text, "secreto", "sin el listado")
                        Check.equal(200, c.request("GET", "/", host = "localhost:${s.port}").status, "localhost")
                        Check.equal(200, c.request("GET", "/", host = "mi-movil.local").status, "mDNS")
                        c.send("GET http://atacante.example/ HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
                        Check.equal(421, c.read().status, "forma absoluta con autoridad ajena")
                    }
                    Check.isTrue(s.log.snapshot().any { it.level == LogLevel.SECURITY && it.text.contains("DNS rebinding") }, "en el registro")
                }
            }
        }

        test("HTTP/1.0 con keep-alive: se confirma y la conexión sigue; sin él, se cierra") {
            TempTree().use { t ->
                t.file("index.html", "hola")
                Site(LocalFileTree(t.root)).use { s ->
                    s.client().use { c ->
                        c.send("GET /index.html HTTP/1.0\r\nConnection: keep-alive\r\n\r\n")
                        val first = c.read()
                        Check.equal("keep-alive", first.header("Connection"), "confirmado")
                        c.send("GET /index.html HTTP/1.0\r\n\r\n")
                        val second = c.read()
                        Check.equal("hola", second.text, "segunda petición por la misma conexión")
                        Check.equal("close", second.header("Connection"), "sin keep-alive")
                        Check.isTrue(c.isClosedByServer(), "cerrada")
                    }
                }
            }
        }

        test("Expect: 100-continue sin leer el cuerpo: respuesta final sin «100» y conexión cerrada") {
            TempTree().use { t ->
                t.file("index.html", "i")
                Site(LocalFileTree(t.root)).use { s ->
                    s.client().use { c ->
                        c.send("POST /index.html HTTP/1.1\r\nHost: x\r\nExpect: 100-continue\r\nContent-Length: 1000000\r\n\r\n")
                        val r = c.read()
                        Check.equal(405, r.status, "405")
                        Check.equal(0, c.continues, "no invita a subir un mega para nada")
                        Check.equal("close", r.header("Connection"), "el cuerpo pendiente impide reutilizarla")
                    }
                }
            }
        }

        test("un lote de consola enorme recibe 413 legible aunque el cliente siga enviando") {
            TempTree().use { t ->
                t.file("index.html", "i")
                Site(LocalFileTree(t.root), ServerOptions(remoteConsole = true)).use { s ->
                    s.client().use { c ->
                        val origin = "http://127.0.0.1:${s.port}"
                        c.send("POST /__consola HTTP/1.1\r\nHost: 127.0.0.1:${s.port}\r\nOrigin: $origin\r\nContent-Length: 400000\r\n\r\n")
                        val writer = Thread {
                            try {
                                c.send("a".repeat(400_000))
                            } catch (e: Exception) {
                                // El servidor puede cerrar antes de recibirlo todo.
                            }
                        }.apply { start() }
                        Check.equal(413, c.read().status, "413")
                        writer.join(5_000)
                    }
                }
            }
        }

        test("archivo ilegible: 500 si aún no empezó la respuesta; si ya empezó, conexión cortada") {
            TempTree().use { t ->
                Site(FailingTree(failAt = 0, size = 200_000)).use { s ->
                    s.client().use { c ->
                        val r = c.request("GET", "/roto.bin")
                        Check.equal(500, r.status, "500")
                        Check.equal("text/html; charset=utf-8", r.header("Content-Type"), "página de error, no application/octet-stream")
                    }
                    Check.isTrue(s.logged("No se pudo leer el archivo: tarjeta extraída"), "registro: culpa del archivo, no del cliente")
                }
                Site(FailingTree(failAt = 150_000, size = 200_000)).use { s ->
                    s.client().use { c ->
                        Check.throws<EOFException>("cuerpo cortado") { c.request("GET", "/roto.bin") }
                    }
                    Check.isTrue(s.logged("No se pudo leer el archivo"), "registro")
                }
            }
        }

        test("SEGURIDAD: un manejador que se queda sin memoria no tumba el servidor") {
            val failures = AtomicInteger()
            val events = object : HttpServerEvents {
                override fun onHandlerFailure(port: Int, client: InetAddress, error: Throwable) {
                    failures.incrementAndGet()
                }
            }
            val server = HttpServer(events)
            val port = ServerSocket(0).use { it.localPort }
            try {
                server.apply(listOf(HttpEndpoint(port, false) { request, response ->
                    if (request.rawPath == "/oom") throw OutOfMemoryError("simulado")
                    response.sendText(200, "vivo")
                }))
                RawClient("127.0.0.1", port).use { c ->
                    val r = c.request("GET", "/oom")
                    Check.equal(500, r.status, "500")
                    Check.equal("text/html; charset=utf-8", r.header("Content-Type"), "página de error")
                }
                RawClient("127.0.0.1", port).use { c -> Check.equal("vivo", c.request("GET", "/").text, "sigue atendiendo") }
                Check.equal(1, failures.get(), "avisado")
            } finally {
                server.shutdown()
            }
        }

        test("SEGURIDAD: un cliente que deja de leer libera su hilo al vencer el plazo de escritura") {
            TempTree().use { t ->
                t.file("index.html", "i")
                t.file("grande.bin", ByteArray(24 * 1024 * 1024))
                Site(LocalFileTree(t.root), config = HttpServerConfig(writeStallTimeoutMs = 300)).use { s ->
                    Socket().use { lazy ->
                        lazy.receiveBufferSize = 4096
                        lazy.connect(InetSocketAddress("127.0.0.1", s.port), 2000)
                        lazy.getOutputStream().write("GET /grande.bin HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                        val deadline = System.nanoTime() + 6_000_000_000L
                        while (!s.logged("dejó de leer") && System.nanoTime() < deadline) Thread.sleep(50)
                        Check.isTrue(s.logged("El cliente dejó de leer la respuesta"), "cortado por el vigilante")
                    }
                }
            }
        }

        test("SEGURIDAD: un puerto «en la red» rechaza a quien no es de la red local") {
            val lanIp = lanIpOrNull()
            if (lanIp == null || PeerPolicy.isLocalNetwork(InetAddress.getByName(lanIp))) {
                println("      (sin una dirección fuera de las redes privadas en este equipo: prueba omitida)")
                return@test
            }
            // Aquí la dirección de "red" del equipo de pruebas no es privada (192.0.2.x):
            // sin contarla como propia ni como subred conectada, hace de equipo ajeno.
            val foreign = AtomicInteger()
            val events = object : HttpServerEvents {
                override fun onForeignPeer(port: Int, client: InetAddress) {
                    foreign.incrementAndGet()
                }
            }
            val server = HttpServer(events, HttpServerConfig(), PeerPolicy(view = { NetworkView(emptySet(), emptyList()) }))
            val port = ServerSocket(0).use { it.localPort }
            try {
                server.apply(listOf(HttpEndpoint(port, true) { _, response -> response.sendText(200, "ok") }))
                RawClient(lanIp, port).use { c ->
                    Check.throws<Exception>("cerrada sin responder") { c.request("GET", "/") }
                }
                RawClient(lanIp, port).use { c ->
                    Check.throws<Exception>("otra vez") { c.request("GET", "/") }
                }
                RawClient("127.0.0.1", port).use { c -> Check.equal("ok", c.request("GET", "/").text, "el propio teléfono sí") }
                Check.equal(1, foreign.get(), "un solo aviso por equipo y minuto")
            } finally {
                server.shutdown()
            }
        }

        test("gzip: HEAD no comprime solo para medir; GET sí, y después HEAD usa lo comprimido") {
            val lanIp = lanIpOrNull()
            if (lanIp == null) {
                println("      (sin interfaz de red local: prueba omitida)")
                return@test
            }
            TempTree().use { t ->
                val css = "body{color:red}\n".repeat(400)
                t.file("index.html", "i")
                t.file("app.css", css)
                Site(LocalFileTree(t.root), lan = true).use { s ->
                    s.client(lanIp).use { c ->
                        val host = "$lanIp:${s.port}"
                        val head = c.request("HEAD", "/app.css", "Accept-Encoding: gzip", host = host)
                        Check.equal(null, head.header("Content-Encoding"), "HEAD en frío: sin comprimir")
                        Check.equal(css.length.toString(), head.header("Content-Length"), "longitud real")
                        val get = c.request("GET", "/app.css", "Accept-Encoding: gzip", host = host)
                        Check.equal("gzip", get.header("Content-Encoding"), "GET comprime")
                        val again = c.request("HEAD", "/app.css", "Accept-Encoding: gzip", host = host)
                        Check.equal("gzip", again.header("Content-Encoding"), "HEAD ya caliente")
                        Check.equal(get.body.size.toString(), again.header("Content-Length"), "misma longitud que el GET")
                        val revalidate = c.request("GET", "/app.css", "Accept-Encoding: gzip", "If-None-Match: ${get.header("ETag")}", host = host)
                        Check.equal(304, revalidate.status, "304 para la versión comprimida")
                    }
                }
            }
        }

        test("consola en una página grande: se inyecta sin cargarla en memoria y la longitud cuadra") {
            TempTree().use { t ->
                val page = "<!DOCTYPE html>\n<html>\n<head>\n<title>x</title>\n</head>\n<body>" + "<p>línea</p>\n".repeat(150_000) + "</body></html>"
                t.file("index.html", page)
                Site(LocalFileTree(t.root), ServerOptions(remoteConsole = true)).use { s ->
                    s.client().use { c ->
                        val r = c.request("GET", "/")
                        Check.equal(200, r.status, "200")
                        Check.equal(r.body.size.toString(), r.header("Content-Length"), "longitud")
                        Check.isTrue(r.text.startsWith("<!DOCTYPE html>\n<html>\n<head><script src=\"/__consola.js\"></script>\n<title>"), "tras <head>")
                        Check.isTrue(r.text.endsWith("</body></html>"), "completa")
                    }
                }
            }
        }
    }
}
