package io.github.joelpasapera.lanshare.core.server

import io.github.joelpasapera.lanshare.core.content.LocalFileTree
import io.github.joelpasapera.lanshare.core.content.RelativePath
import io.github.joelpasapera.lanshare.core.http.HttpServerConfig
import io.github.joelpasapera.lanshare.core.log.LogBus
import io.github.joelpasapera.lanshare.core.net.LanAddresses
import io.github.joelpasapera.lanshare.core.site.ServerEngine
import io.github.joelpasapera.lanshare.core.site.ServerOptions
import io.github.joelpasapera.lanshare.core.site.ServerServices
import io.github.joelpasapera.lanshare.core.site.SiteDefinition
import io.github.joelpasapera.lanshare.core.testkit.Check
import io.github.joelpasapera.lanshare.core.testkit.Suite
import io.github.joelpasapera.lanshare.core.testkit.TempTree
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/** Lo que un equipo de la red podría hacer para acaparar el servidor. */
class RobustnessTests : Suite("Servidor: resistencia") {

    private fun engine(t: TempTree, config: HttpServerConfig, lan: Boolean): Pair<ServerEngine, Int> {
        t.file("index.html", "ok")
        val port = ServerSocket(0).use { it.localPort }
        val engine = ServerEngine(ServerServices(LogBus()), config)
        val st = engine.apply(listOf(SiteDefinition("s", "S", port, lan, LocalFileTree(t.root), RelativePath.parse("index.html"))), ServerOptions())
        check(st.single().running) { st.single().error ?: "" }
        return Pair(engine, port)
    }

    private fun statusLine(host: String, port: Int): String = Socket().use { s ->
        s.connect(InetSocketAddress(host, port), 2000)
        s.soTimeout = 5000
        s.getOutputStream().write("GET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".toByteArray())
        s.getInputStream().readBytes().toString(Charsets.ISO_8859_1).lineSequence().first()
    }

    init {
        test("SEGURIDAD: un equipo no puede ocupar más de su cupo de conexiones") {
            val lanIp = LanAddresses.discover().firstOrNull()?.address
            if (lanIp == null) {
                println("      (sin interfaz de red local: prueba omitida)")
                return@test
            }
            TempTree().use { t ->
                val (engine, port) = engine(t, HttpServerConfig(maxThreads = 32, maxConnectionsPerClient = 4), lan = true)
                val idle = (1..4).map { Socket(lanIp, port) }
                try {
                    Thread.sleep(200)
                    Check.contains(statusLine(lanIp, port), "503", "la quinta conexión del mismo equipo")
                    Check.contains(statusLine("127.0.0.1", port), "200", "el propio teléfono no tiene cupo")
                } finally {
                    idle.forEach { it.close() }
                    engine.stop()
                }
            }
        }

        test("SEGURIDAD: con el pool casi lleno, las conexiones ociosas ceden su hilo enseguida") {
            TempTree().use { t ->
                val config = HttpServerConfig(maxThreads = 8, idleTimeoutMs = 20_000, busyIdleTimeoutMs = 300)
                val (engine, port) = engine(t, config, lan = false)
                val idle = (1..8).map { Socket("127.0.0.1", port) }
                try {
                    Thread.sleep(200)
                    Check.contains(statusLine("127.0.0.1", port), "503", "pool lleno")
                    // Las ociosas abiertas cuando el pool ya estaba al 75 % esperan poco.
                    Thread.sleep(800)
                    Check.contains(statusLine("127.0.0.1", port), "200", "hilos recuperados sin esperar 20 s")
                } finally {
                    idle.forEach { it.close() }
                    engine.stop()
                }
            }
        }

        test("SEGURIDAD: saltos de línea sueltos antes de la petición no esquivan el plazo total") {
            TempTree().use { t ->
                val (engine, port) = engine(t, HttpServerConfig(headTimeoutMs = 600, idleTimeoutMs = 5_000), lan = false)
                try {
                    Socket("127.0.0.1", port).use { s ->
                        s.soTimeout = 5000
                        val out = s.getOutputStream()
                        val started = System.nanoTime()
                        var closed = false
                        while ((System.nanoTime() - started) / 1_000_000 < 3000) {
                            try {
                                out.write("\r\n".toByteArray())
                                out.flush()
                            } catch (e: Exception) {
                                closed = true
                                break
                            }
                            Thread.sleep(100)
                        }
                        if (!closed) {
                            closed = try {
                                s.getInputStream().read() < 0
                            } catch (e: Exception) {
                                true
                            }
                        }
                        Check.isTrue(closed, "el servidor cerró la conexión")
                        Check.isTrue((System.nanoTime() - started) / 1_000_000 < 3000, "dentro del plazo")
                    }
                } finally {
                    engine.stop()
                }
            }
        }

        test("SEGURIDAD: una cabecera enviada byte a byte recibe 408 al pasar el plazo total") {
            TempTree().use { t ->
                val (engine, port) = engine(t, HttpServerConfig(headTimeoutMs = 600, idleTimeoutMs = 5_000), lan = false)
                try {
                    Socket("127.0.0.1", port).use { s ->
                        s.soTimeout = 5000
                        val out = s.getOutputStream()
                        val input = s.getInputStream()
                        val started = System.nanoTime()
                        for (c in "GET / HTTP/1.1\r\nHost: x\r\nX-Lento: " + "a".repeat(60)) {
                            if (input.available() > 0) break
                            try {
                                out.write(c.code)
                                out.flush()
                            } catch (e: Exception) {
                                break
                            }
                            Thread.sleep(50)
                            if ((System.nanoTime() - started) / 1_000_000 > 3000) break
                        }
                        val statusLine = input.bufferedReader(Charsets.ISO_8859_1).readLine() ?: ""
                        Check.contains(statusLine, "408", "respuesta del servidor")
                        Check.isTrue((System.nanoTime() - started) / 1_000_000 < 3000, "dentro del plazo")
                    }
                } finally {
                    engine.stop()
                }
            }
        }
    }
}
