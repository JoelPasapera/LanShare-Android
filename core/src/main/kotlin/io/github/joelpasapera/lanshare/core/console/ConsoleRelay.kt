package io.github.joelpasapera.lanshare.core.console

import io.github.joelpasapera.lanshare.core.http.HttpRequest
import io.github.joelpasapera.lanshare.core.http.HttpResponse
import io.github.joelpasapera.lanshare.core.log.LogBus
import io.github.joelpasapera.lanshare.core.log.LogLevel
import io.github.joelpasapera.lanshare.core.net.HostGuard
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Atiende POST /__consola: recibe los lotes del script y los escribe en el
 * registro, una línea por mensaje y con el puerto del sitio.
 *
 * Cualquier equipo de la red puede llamar aquí: el cuerpo tiene tope, el
 * origen se comprueba y cada cliente tiene un ritmo máximo (una página con un
 * error dentro de requestAnimationFrame produce sesenta por segundo y ahogaría
 * el registro). Lo descartado se cuenta y se avisa. Portado de LanShare.
 */
class ConsoleRelay(
    private val log: LogBus,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    /** Resultado para la línea de petición (que el manejador normalmente no escribe). */
    class Outcome(val status: Int, val note: String)

    private class ClientState(nowMs: Long) {
        val bucket = TokenBucket(BURST, PER_SECOND, nowMs)
        var dropped = 0L
    }

    private val clients = HashMap<String, ClientState>()

    fun handle(request: HttpRequest, response: HttpResponse, port: Int): Outcome {
        if (request.method != "POST") {
            response.header("Allow", "POST")
            response.sendEmpty(405)
            return Outcome(405, "/__consola solo acepta POST")
        }
        if (!HostGuard.isSameOrigin(request.headers["Origin"], request.headers["Host"], request.headers["Sec-Fetch-Site"])) {
            response.sendEmpty(403)
            return Outcome(403, "Consola remota desde otro origen")
        }
        if (request.contentLength > MAX_BODY_BYTES) {
            response.closeConnection = true
            response.sendEmpty(413)
            return Outcome(413, "Lote de consola demasiado grande")
        }

        val body = readUtf8(request)
        val report = ConsoleReport.parse(body)
        if (report == null) {
            response.sendEmpty(400)
            return Outcome(400, "Lote de consola ilegible")
        }

        val host = request.headers["Host"]
        relay(report, request.clientIp, port, if (host.isNullOrEmpty()) "" else "http://$host", clock())
        response.sendEmpty(204)
        return Outcome(204, "")
    }

    /** Escribe el lote respetando el ritmo del cliente. Devuelve los mensajes escritos. */
    fun relay(report: ConsoleReport, clientIp: String, port: Int, origin: String, nowMs: Long): Int {
        val state = stateFor(clientIp, nowMs)
        val granted: Int
        var droppedNow = 0L
        synchronized(state) {
            granted = state.bucket.take(report.entries.size, nowMs)
            state.dropped += report.dropped + (report.entries.size - granted).toLong()
            // El aviso de descartados también gasta ritmo: si no, una página
            // desbocada llenaría el registro de avisos.
            if (state.dropped > 0 && state.bucket.take(1, nowMs) == 1) {
                droppedNow = state.dropped
                state.dropped = 0
            }
        }

        for (i in 0 until granted) {
            val entry = report.entries[i]
            log.write(ConsoleLine.level(entry.level), port, ConsoleLine.format(entry, clientIp, report.page, origin))
        }
        if (droppedNow > 0) {
            log.write(
                LogLevel.JS_WARN, port,
                ConsoleLine.flatten(clientIp, 64) + "  " + droppedNow +
                    (if (droppedNow == 1L) " mensaje de consola omitido" else " mensajes de consola omitidos") +
                    " por llegar demasiados seguidos",
            )
        }
        return granted
    }

    private fun stateFor(clientIp: String, nowMs: Long): ClientState = synchronized(clients) {
        clients[clientIp] ?: run {
            if (clients.size >= MAX_TRACKED_CLIENTS) clients.clear()
            ClientState(nowMs).also { clients[clientIp] = it }
        }
    }

    private fun readUtf8(request: HttpRequest): String {
        val out = ByteArrayOutputStream(minOf(request.contentLength, MAX_BODY_BYTES.toLong()).toInt())
        val buf = ByteArray(8192)
        while (true) {
            val n = request.body.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return String(out.toByteArray(), StandardCharsets.UTF_8)
    }

    companion object {
        const val MAX_BODY_BYTES = 64 * 1024
        const val BURST = 40
        const val PER_SECOND = 10.0
        private const val MAX_TRACKED_CLIENTS = 512
    }
}
