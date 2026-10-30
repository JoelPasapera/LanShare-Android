package io.github.joelpasapera.lanshare.core.clients

import java.util.concurrent.CopyOnWriteArrayList

/** Fotografía inmutable de un cliente, para la interfaz. */
class ClientSnapshot(
    val ip: String,
    val isThisDevice: Boolean,
    val device: String,
    val browser: String,
    val firstSeenMillis: Long,
    val lastSeenMillis: Long,
    val requests: Long,
    val bytesSent: Long,
    val ports: List<Int>,
)

/**
 * Dispositivos que han pedido algo, identificados por IP. Versión reducida
 * del registro de LanShare para Windows: en Android no hay ARP legible ni
 * NetBIOS, y el escaneo de puertos no tiene sentido desde un móvil; queda lo
 * que dice el propio navegador.
 */
class ClientRegistry(private val maxClients: Int = 256) {

    private class Client(val ip: String, val isThisDevice: Boolean, now: Long) {
        val firstSeen = now
        var lastSeen = now
        var requests = 0L
        var bytes = 0L
        var userAgent: String? = null
        var device = "Desconocido"
        var browser = "Desconocido"
        val ports = java.util.TreeSet<Int>()
    }

    /** En orden de acceso: el primero es el que lleva más tiempo sin pedir nada. */
    private val clients = LinkedHashMap<String, Client>(64, 0.75f, true)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /**
     * Anota una petición. El User-Agent se analiza fuera del candado: todas las
     * conexiones pasan por aquí, y la interfaz lee [snapshot] desde el hilo
     * principal, así que dentro solo se hacen asignaciones.
     */
    fun record(ip: String, isLoopback: Boolean, port: Int, userAgent: String?, bytesSent: Long, nowMillis: Long = System.currentTimeMillis()) {
        val ua = userAgent?.takeIf { it.isNotEmpty() }?.let(UaParser::clip)
        var parsed: Array<String>? = null
        if (ua != null) {
            val known = synchronized(clients) { clients[ip]?.userAgent }
            if (ua != known) parsed = arrayOf(UaParser.device(ua), UaParser.browser(ua))
        }

        synchronized(clients) {
            var client = clients[ip]
            if (client == null) {
                if (clients.size >= maxClients) evictOldest()
                client = Client(ip, isLoopback, nowMillis)
                clients[ip] = client
            }
            client.lastSeen = nowMillis
            client.requests++
            client.bytes += bytesSent
            client.ports.add(port)
            if (parsed != null) {
                client.userAgent = ua
                client.device = parsed[0]
                client.browser = parsed[1]
            }
        }
        for (listener in listeners) listener()
    }

    private fun evictOldest() {
        val eldest = clients.entries.iterator()
        if (eldest.hasNext()) {
            eldest.next()
            eldest.remove()
        }
    }

    fun snapshot(): List<ClientSnapshot> = synchronized(clients) {
        clients.values.sortedByDescending { it.lastSeen }.map {
            ClientSnapshot(it.ip, it.isThisDevice, it.device, it.browser, it.firstSeen, it.lastSeen, it.requests, it.bytes, it.ports.toList())
        }
    }

    val size: Int get() = synchronized(clients) { clients.size }

    fun clear() {
        synchronized(clients) { clients.clear() }
        for (listener in listeners) listener()
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }
}
