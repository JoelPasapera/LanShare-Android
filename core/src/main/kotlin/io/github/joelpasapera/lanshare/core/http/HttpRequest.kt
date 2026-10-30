package io.github.joelpasapera.lanshare.core.http

import java.io.InputStream
import java.net.InetAddress

/** Petición ya analizada. El cuerpo se lee de [body] y está acotado a [contentLength]. */
class HttpRequest internal constructor(
    val method: String,
    /** Destino tal como llegó en la línea de petición. */
    val target: String,
    /** Parte de ruta del destino, aún con los escapes %XX. Empieza por '/'. */
    val rawPath: String,
    /** Consulta sin el '?', o cadena vacía. */
    val query: String,
    val version: HttpVersion,
    val headers: HttpHeaders,
    val remoteAddress: InetAddress,
    val localPort: Int,
    val contentLength: Long,
    val body: InputStream,
) {
    val isHead: Boolean get() = method == "HEAD"

    /** La petición viene del propio dispositivo (127.0.0.1, ::1). */
    val isLoopback: Boolean get() = remoteAddress.isLoopbackAddress

    val clientIp: String get() = remoteAddress.hostAddress ?: "?"

    /**
     * Autoridad del destino en forma absoluta ("http://equipo:puerto/ruta"),
     * que según RFC 9112 §3.2.2 prevalece sobre Host; null en la forma
     * habitual "/ruta".
     */
    val targetAuthority: String?
        get() {
            if (target.startsWith("/") || target == "*") return null
            val scheme = target.indexOf("://")
            if (scheme <= 0) return null
            var end = scheme + 3
            while (end < target.length && target[end] != '/' && target[end] != '?' && target[end] != '#') end++
            return target.substring(scheme + 3, end)
        }

    /** El cliente quiere mantener la conexión abierta tras la respuesta. */
    val wantsKeepAlive: Boolean
        get() = when (version) {
            HttpVersion.HTTP_1_1 -> !headers.hasToken("Connection", "close")
            HttpVersion.HTTP_1_0 -> headers.hasToken("Connection", "keep-alive")
        }
}

enum class HttpVersion(val text: String) {
    HTTP_1_0("HTTP/1.0"),
    HTTP_1_1("HTTP/1.1"),
}
