package io.github.joelpasapera.lanshare.core.http

import io.github.joelpasapera.lanshare.core.net.PeerPolicy
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Atiende una petición. Si lanza antes de responder, el servidor contesta 500. */
fun interface HttpHandler {
    fun handle(request: HttpRequest, response: HttpResponse)
}

/** Un puerto que escuchar: solo en este dispositivo o en toda la red local. */
class HttpEndpoint(val port: Int, val lan: Boolean, val handler: HttpHandler)

/** Lo que el servidor no puede atribuir a un manejador concreto. */
interface HttpServerEvents {
    fun onProtocolError(port: Int, client: InetAddress, status: Int, message: String) {}
    fun onHandlerFailure(port: Int, client: InetAddress, error: Throwable) {}
    fun onListenerFailure(port: Int, error: Throwable) {}
    fun onOverloaded(port: Int, client: InetAddress) {}

    /** Alguien de fuera de la red local llamó a un puerto abierto "en la red". Como mucho un aviso por minuto y equipo. */
    fun onForeignPeer(port: Int, client: InetAddress) {}
}

class HttpServerConfig(
    val maxHeadBytes: Int = 32 * 1024,
    /** Espera máxima por cada lectura: una conexión ociosa se cierra al cumplirse. */
    val idleTimeoutMs: Int = 15_000,
    /** Con el pool casi lleno, las conexiones ociosas se cierran mucho antes. */
    val busyIdleTimeoutMs: Int = 2_000,
    /** Tiempo total para recibir una cabecera completa desde su primer byte, llegue como llegue. */
    val headTimeoutMs: Int = 20_000,
    /** Lo mismo para un cuerpo (solo la consola remota los recibe, de pocos KB). */
    val bodyTimeoutMs: Int = 20_000,
    /** Una escritura que no avanza en este tiempo (el cliente dejó de leer) cierra la conexión. */
    val writeStallTimeoutMs: Int = 30_000,
    val maxThreads: Int = 64,
    /** Conexiones simultáneas por equipo de la red (el propio teléfono no tiene tope). */
    val maxConnectionsPerClient: Int = 16,
    val backlog: Int = 64,
    /** Cuerpo sin leer que se descarta para reutilizar la conexión; más allá, se cierra. */
    val maxDrainBytes: Long = 256 * 1024,
    /** Tras un error, cuánto se sigue leyendo y descartando antes de cerrar del todo. */
    val lingerMs: Int = 1_000,
)

/**
 * Servidor HTTP/1.1 multipuerto escrito desde cero sobre sockets bloqueantes.
 *
 * Modelo: un hilo de aceptación por puerto y un único pool acotado para todas
 * las conexiones. Cada conexión vive en un hilo del pool mientras dure (con
 * keep-alive y peticiones encadenadas) y se cierra al quedar ociosa. Para un
 * servidor personal con unos pocos dispositivos es el modelo más simple que
 * aprovecha varios núcleos sin la complejidad de un reactor, y el disco
 * (en SAF, una llamada entre procesos por archivo) se espera en paralelo.
 *
 * Como el pool es compartido, nadie debe poder retener sus hilos: plazos por
 * lectura y totales por cabecera y cuerpo, cupo de conexiones por equipo, un
 * vigilante que corta las escrituras que no avanzan y, en los puertos "en la
 * red", solo equipos de la red local ([PeerPolicy]).
 *
 * Responsabilidad única: transporte. Qué se sirve en cada puerto lo decide su
 * [HttpHandler], que se puede cambiar en caliente sin cortar conexiones.
 */
class HttpServer(
    private val events: HttpServerEvents,
    private val config: HttpServerConfig = HttpServerConfig(),
    private val peers: PeerPolicy = PeerPolicy(),
) {
    class BindResult(val port: Int, val error: IOException?)

    /** Qué hacer con la conexión tras una respuesta. */
    private enum class Next { KEEP_OPEN, CLOSE, CLOSE_LINGERING }

    private val listeners = HashMap<Int, Listener>()
    private val live: MutableSet<Connection> = ConcurrentHashMap.newKeySet()
    private val activeConnections = AtomicInteger()
    private val perClient = ConcurrentHashMap<InetAddress, AtomicInteger>()
    private val foreignNoticed = ConcurrentHashMap<InetAddress, Long>()
    private var watchdog: Thread? = null

    @Volatile
    private var shut = false

    private val pool = ThreadPoolExecutor(
        0, config.maxThreads, 30, TimeUnit.SECONDS, SynchronousQueue(),
        NamedThreadFactory("lanshare-http"),
    )

    val connectionCount: Int get() = activeConnections.get()

    @get:Synchronized
    val boundPorts: Set<Int> get() = listeners.keys.toSet()

    /**
     * Deja escuchando exactamente [endpoints]. Los puertos que ya escuchaban
     * con el mismo alcance solo cambian de manejador, sin cortar a nadie; los
     * que sobran o cambian de alcance se cierran. Devuelve el resultado de
     * cada puerto pedido (por ejemplo, ocupado por otra aplicación).
     */
    @Synchronized
    fun apply(endpoints: List<HttpEndpoint>): List<BindResult> {
        check(!shut) { "Servidor apagado" }
        startWatchdog()
        val wanted = endpoints.associateBy { it.port }

        val iterator = listeners.entries.iterator()
        while (iterator.hasNext()) {
            val (port, listener) = iterator.next()
            val endpoint = wanted[port]
            if (endpoint == null || endpoint.lan != listener.lan) {
                listener.close()
                iterator.remove()
            }
        }

        val results = ArrayList<BindResult>(endpoints.size)
        for (endpoint in endpoints) {
            val existing = listeners[endpoint.port]
            if (existing != null) {
                existing.handler = endpoint.handler
                results.add(BindResult(endpoint.port, null))
                continue
            }
            val listener = Listener(endpoint.port, endpoint.lan, endpoint.handler)
            try {
                listener.bind()
                listeners[endpoint.port] = listener
                results.add(BindResult(endpoint.port, null))
            } catch (e: IOException) {
                listener.close()
                results.add(BindResult(endpoint.port, e))
            }
        }
        return results
    }

    @Synchronized
    fun stop() {
        for (listener in listeners.values) listener.close()
        listeners.clear()
    }

    /** Detiene todo y libera el pool. El objeto no se puede reutilizar. */
    fun shutdown() {
        stop()
        synchronized(this) {
            shut = true
            watchdog?.interrupt()
            watchdog = null
        }
        pool.shutdownNow()
    }

    private inner class Listener(val port: Int, val lan: Boolean, @Volatile var handler: HttpHandler) {
        private val serverSocket = ServerSocket()

        @Volatile
        var closed = false
            private set

        fun bind() {
            serverSocket.reuseAddress = true
            val address = if (lan) {
                InetSocketAddress(port)
            } else {
                // 127.0.0.1 explícito: en Android getLoopbackAddress() puede ser ::1
                // y entonces http://127.0.0.1 no llegaría.
                InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port)
            }
            serverSocket.bind(address, config.backlog)
            val thread = Thread({ acceptLoop() }, "lanshare-accept-$port")
            thread.isDaemon = true
            thread.start()
        }

        /** Nunca sale por una excepción: en Android, un hilo que muere así cierra la app. */
        private fun acceptLoop() {
            while (!closed) {
                val socket = try {
                    serverSocket.accept()
                } catch (e: Throwable) {
                    if (closed) return
                    quietly { events.onListenerFailure(port, e) }
                    try {
                        Thread.sleep(200)
                    } catch (ie: InterruptedException) {
                        return
                    }
                    continue
                }
                try {
                    admit(socket)
                } catch (e: Throwable) {
                    closeQuietly(socket)
                }
            }
        }

        private fun admit(socket: Socket) {
            val remote = socket.inetAddress
            if (lan && !peers.allows(remote)) {
                closeQuietly(socket)
                noticeForeign(port, remote)
                return
            }
            socket.tcpNoDelay = true
            socket.soTimeout = config.idleTimeoutMs

            if (!acquireClientSlot(remote)) {
                events.onOverloaded(port, remote)
                rejectBusy(socket)
                return
            }
            try {
                pool.execute { serve(socket) }
            } catch (e: RejectedExecutionException) {
                releaseClientSlot(remote)
                events.onOverloaded(port, remote)
                rejectBusy(socket)
            }
        }

        private fun serve(socket: Socket) {
            activeConnections.incrementAndGet()
            var connection: Connection? = null
            var next = Next.CLOSE
            try {
                val c = Connection(socket, this, WatchedOutput(socket.getOutputStream()))
                connection = c
                live.add(c)
                // close() pudo recorrer las conexiones justo antes de que esta entrara.
                if (closed) return
                val input = HttpInput(socket.getInputStream(), config.maxHeadBytes) { ms -> socket.soTimeout = ms }
                val output = BufferedOutputStream(c.output, 64 * 1024)
                while (!closed) {
                    next = serveOne(socket, input, output)
                    if (next != Next.KEEP_OPEN) break
                }
            } catch (e: IOException) {
                // El cliente se fue, el socket se cerró al detener o venció un plazo.
                next = Next.CLOSE
            } catch (e: Throwable) {
                // Incluido OutOfMemoryError: se pierde esta conexión, no la app.
                next = Next.CLOSE
                quietly { events.onHandlerFailure(port, socket.inetAddress, e) }
            } finally {
                if (next == Next.CLOSE_LINGERING) closeLingering(socket) else closeQuietly(socket)
                connection?.let { live.remove(it) }
                releaseClientSlot(socket.inetAddress)
                activeConnections.decrementAndGet()
            }
        }

        /** Atiende una petición y dice qué hacer con la conexión. */
        private fun serveOne(socket: Socket, input: HttpInput, output: OutputStream): Next {
            // Con el pool casi lleno, una conexión ociosa cede su hilo enseguida:
            // así unas cuantas pestañas abiertas (o un equipo malintencionado)
            // no dejan a los demás con 503 durante todo el tiempo de espera.
            val busy = activeConnections.get() * 4 >= config.maxThreads * 3
            val idle = if (busy) config.busyIdleTimeoutMs else config.idleTimeoutMs

            val head = try {
                input.readHead(config.maxHeadBytes, idle, config.headTimeoutMs) ?: return Next.CLOSE
            } catch (e: SocketTimeoutException) {
                // Sin nada pendiente era una conexión ociosa (o abierta de forma
                // especulativa por el navegador): se cierra sin más.
                if (input.pendingBytes == 0) return Next.CLOSE
                writeError(output, 408, "La cabecera tardó demasiado en llegar")
                events.onProtocolError(port, socket.inetAddress, 408, "Cabecera incompleta al agotarse el plazo")
                return Next.CLOSE_LINGERING
            } catch (e: HeadTooLargeException) {
                writeError(output, 431, "Cabecera demasiado grande")
                events.onProtocolError(port, socket.inetAddress, 431, "Cabecera demasiado grande")
                return Next.CLOSE_LINGERING
            }

            val parsed = try {
                RequestParser.parse(head)
            } catch (e: HttpParseException) {
                writeError(output, e.status, e.message)
                events.onProtocolError(port, socket.inetAddress, e.status, e.message ?: "")
                return Next.CLOSE_LINGERING
            }

            // "Expect: 100-continue": el "100" se envía cuando el manejador empieza
            // a leer el cuerpo, no antes. Si responde sin leerlo (405, 403, 413),
            // el cliente no llega a subir nada.
            val expectContinue = parsed.version == HttpVersion.HTTP_1_1 && parsed.contentLength > 0 &&
                parsed.headers["Expect"].equals("100-continue", ignoreCase = true)
            val body = input.body(
                parsed.contentLength, config.idleTimeoutMs, config.bodyTimeoutMs,
                if (expectContinue) ({ output.write(CONTINUE); output.flush() }) else null,
            )

            val request = HttpRequest(
                parsed.method, parsed.target, parsed.rawPath, parsed.query, parsed.version,
                parsed.headers, socket.inetAddress, socket.localPort, parsed.contentLength, body,
            )
            val response = HttpResponse(output, request.isHead)
            if (!request.wantsKeepAlive) response.closeConnection = true
            else if (request.version == HttpVersion.HTTP_1_0) response.echoKeepAlive = true
            if (expectContinue) {
                // Sin "100", el cliente puede no enviar el cuerpo nunca o enviarlo
                // igualmente: la conexión ya no tiene un final claro.
                response.beforeCommit = { if (!body.started) response.closeConnection = true }
            }

            try {
                handler.handle(request, response)
            } catch (e: IOException) {
                // Escritura cortada a mitad, o el cliente dejó de enviar el cuerpo.
                return Next.CLOSE
            } catch (e: Throwable) {
                quietly { events.onHandlerFailure(port, socket.inetAddress, e) }
                if (response.isCommitted) return Next.CLOSE
                return try {
                    response.closeConnection = true
                    response.sendErrorPage(500, null)
                    response.flush()
                    Next.CLOSE_LINGERING
                } catch (e2: Throwable) {
                    Next.CLOSE
                }
            }

            if (!response.isCommitted) {
                response.closeConnection = true
                response.sendEmpty(500)
            }
            response.flush()

            if (response.closeConnection || !response.isBodyComplete) {
                return if (body.isFullyRead) Next.CLOSE else Next.CLOSE_LINGERING
            }
            return if (drain(body)) Next.KEEP_OPEN else Next.CLOSE_LINGERING
        }

        /** Descarta el cuerpo que el manejador no leyó, si es razonable. */
        private fun drain(body: HttpInput.Body): Boolean {
            if (body.isFullyRead) return true
            val scratch = ByteArray(8192)
            var total = 0L
            while (true) {
                val n = body.read(scratch)
                if (n < 0) return true
                total += n
                if (total > config.maxDrainBytes) return false
            }
        }

        private fun rejectBusy(socket: Socket) {
            try {
                writeError(socket.getOutputStream(), 503, "Demasiadas conexiones a la vez; reintenta en un momento")
            } catch (e: IOException) {
                // Nada.
            } finally {
                closeQuietly(socket)
            }
        }

        fun close() {
            closed = true
            closeQuietly(serverSocket)
            for (c in live) if (c.listener === this) closeQuietly(c.socket)
        }
    }

    private class Connection(val socket: Socket, val listener: Listener, val output: WatchedOutput)

    /**
     * Flujo de salida que anota cuándo empezó la escritura en curso. Java no
     * tiene plazo de escritura en sockets: un cliente que deja de leer (una
     * pestaña congelada, un vídeo en pausa con el búfer lleno) bloquearía el
     * hilo para siempre. El vigilante cierra el socket si una escritura lleva
     * demasiado sin avanzar, y eso la desbloquea.
     *
     * Las escrituras se trocean para que "sin avanzar" signifique de verdad
     * eso: un trozo pequeño que no entra en el búfer del sistema.
     */
    private class WatchedOutput(private val out: OutputStream) : OutputStream() {
        @Volatile
        private var writingSince = IDLE

        @Volatile
        var abandoned = false

        fun blockedFor(now: Long): Long {
            val since = writingSince
            return if (since == IDLE) 0 else now - since
        }

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var from = off
            val until = off + len
            while (from < until) {
                val n = minOf(WRITE_CHUNK, until - from)
                writingSince = System.nanoTime()
                try {
                    out.write(b, from, n)
                } catch (e: IOException) {
                    if (abandoned) throw IOException("El cliente dejó de leer la respuesta", e)
                    throw e
                } finally {
                    writingSince = IDLE
                }
                from += n
            }
        }

        override fun flush() = out.flush()

        override fun close() = out.close()

        companion object {
            const val IDLE = Long.MIN_VALUE
            const val WRITE_CHUNK = 16 * 1024
        }
    }

    @Synchronized
    private fun startWatchdog() {
        if (watchdog != null) return
        watchdog = Thread({ watchWrites() }, "lanshare-vigilante").apply {
            isDaemon = true
            start()
        }
    }

    private fun watchWrites() {
        val limit = config.writeStallTimeoutMs * 1_000_000L
        val period = (config.writeStallTimeoutMs / 4).coerceIn(25, 2_000).toLong()
        while (!shut) {
            try {
                Thread.sleep(period)
            } catch (e: InterruptedException) {
                return
            }
            val now = System.nanoTime()
            for (c in live) {
                if (c.output.blockedFor(now) > limit) {
                    c.output.abandoned = true
                    closeQuietly(c.socket)
                }
            }
        }
    }

    private fun writeError(output: OutputStream, status: Int, detail: String?) {
        try {
            val response = HttpResponse(output, headOnly = false)
            response.closeConnection = true
            response.sendErrorPage(status, detail)
            response.flush()
        } catch (e: IOException) {
            // El cliente ya no escucha.
        }
    }

    /**
     * Cierre "con espera": se envía el final (FIN) tras la respuesta y se
     * sigue leyendo y descartando un momento. Si se cerrara con datos del
     * cliente aún sin leer, el sistema respondería con RST y el cliente podría
     * perder la respuesta de error antes de leerla.
     */
    private fun closeLingering(socket: Socket) {
        try {
            socket.shutdownOutput()
            socket.soTimeout = config.lingerMs.coerceAtLeast(1)
            val input = socket.getInputStream()
            val scratch = ByteArray(4096)
            val deadline = System.nanoTime() + config.lingerMs * 1_000_000L
            var total = 0
            while (total < LINGER_MAX_BYTES && System.nanoTime() - deadline < 0) {
                val n = input.read(scratch)
                if (n < 0) break
                total += n
            }
        } catch (e: IOException) {
            // Plazo agotado o cliente ido: ya se puede cerrar.
        } finally {
            closeQuietly(socket)
        }
    }

    private fun noticeForeign(port: Int, client: InetAddress) {
        val now = System.nanoTime()
        val last = foreignNoticed[client]
        if (last != null && now - last < FOREIGN_NOTICE_NANOS) return
        if (foreignNoticed.size >= 256) foreignNoticed.clear()
        foreignNoticed[client] = now
        quietly { events.onForeignPeer(port, client) }
    }

    /** Reserva una conexión para [client]; false si ya tiene el máximo. */
    private fun acquireClientSlot(client: InetAddress): Boolean {
        if (client.isLoopbackAddress) return true
        val count = perClient.compute(client) { _, c -> (c ?: AtomicInteger()).also { it.incrementAndGet() } }!!.get()
        if (count <= config.maxConnectionsPerClient) return true
        releaseClientSlot(client)
        return false
    }

    private fun releaseClientSlot(client: InetAddress) {
        if (client.isLoopbackAddress) return
        perClient.computeIfPresent(client) { _, c -> if (c.decrementAndGet() <= 0) null else c }
    }

    private class NamedThreadFactory(private val prefix: String) : ThreadFactory {
        private val counter = AtomicInteger()
        override fun newThread(r: Runnable): Thread {
            val thread = Thread(r, prefix + "-" + counter.incrementAndGet())
            thread.isDaemon = true
            return thread
        }
    }

    private companion object {
        val CONTINUE: ByteArray = "HTTP/1.1 100 Continue\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1)
        const val LINGER_MAX_BYTES = 1024 * 1024
        const val FOREIGN_NOTICE_NANOS = 60_000_000_000L

        fun closeQuietly(closeable: Closeable) {
            try {
                closeable.close()
            } catch (e: IOException) {
                // Nada.
            }
        }

        /** Avisos que no pueden tumbar el hilo que los da (por ejemplo, sin memoria). */
        inline fun quietly(block: () -> Unit) {
            try {
                block()
            } catch (t: Throwable) {
                // Nada: el aviso es lo de menos.
            }
        }
    }
}
