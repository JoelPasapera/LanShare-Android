package io.github.joelpasapera.lanshare.core.site

import io.github.joelpasapera.lanshare.core.http.HttpEndpoint
import io.github.joelpasapera.lanshare.core.http.HttpServer
import io.github.joelpasapera.lanshare.core.http.HttpServerConfig
import io.github.joelpasapera.lanshare.core.http.HttpServerEvents
import io.github.joelpasapera.lanshare.core.log.LogLevel
import java.net.BindException
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

/** Estado de un sitio tras aplicar la configuración. */
class SiteStatus(val id: String, val port: Int, val lan: Boolean, val running: Boolean, val error: String?)

/**
 * Fachada del servidor: recibe la lista de sitios que deben estar en marcha y
 * deja el servidor exactamente así, cambiando solo lo que cambió.
 *
 * Editar un sitio que ya sirve (archivo de inicio, opciones) cambia su
 * manejador en caliente: ni se corta una descarga ni se cierra una pestaña.
 * Solo un cambio de puerto o de alcance reabre su puerto.
 */
class ServerEngine(
    val services: ServerServices,
    private val config: HttpServerConfig = HttpServerConfig(),
) {
    private var server: HttpServer? = null
    private var statuses: List<SiteStatus> = emptyList()
    private val listeners = CopyOnWriteArrayList<(List<SiteStatus>) -> Unit>()

    private val events = object : HttpServerEvents {
        override fun onProtocolError(port: Int, client: InetAddress, status: Int, message: String) {
            services.log.write(LogLevel.WARN, port, (client.hostAddress ?: "?") + "  " + status + "  · " + message)
        }

        override fun onHandlerFailure(port: Int, client: InetAddress, error: Throwable) {
            services.log.write(LogLevel.ERROR, port, (client.hostAddress ?: "?") + "  Fallo interno: " +
                error.javaClass.simpleName + (error.message?.let { ": $it" } ?: ""))
        }

        override fun onListenerFailure(port: Int, error: Throwable) {
            services.log.write(LogLevel.ERROR, port, "El puerto dejó de aceptar conexiones: " + (error.message ?: error.javaClass.simpleName))
        }

        override fun onOverloaded(port: Int, client: InetAddress) {
            services.log.write(LogLevel.WARN, port, (client.hostAddress ?: "?") + "  503  · Demasiadas conexiones a la vez")
        }

        override fun onForeignPeer(port: Int, client: InetAddress) {
            services.log.write(
                LogLevel.SECURITY, port,
                (client.hostAddress ?: "?") + "  Conexión rechazada: no viene de la red local (datos móviles, IPv6 pública o VPN)",
            )
        }
    }

    @get:Synchronized
    val isRunning: Boolean get() = server != null && statuses.any { it.running }

    @Synchronized
    fun currentStatuses(): List<SiteStatus> = statuses

    /** Deja en marcha exactamente [sites]. Lista vacía equivale a [stop]. */
    @Synchronized
    fun apply(sites: List<SiteDefinition>, options: ServerOptions): List<SiteStatus> {
        if (sites.isEmpty()) {
            stop()
            return statuses
        }
        val active = server ?: HttpServer(events, config).also { server = it }

        val endpoints = sites.map { HttpEndpoint(it.port, it.lan, SiteHandler(it, options, services)) }
        val results = active.apply(endpoints).associateBy { it.port }

        val previous = statuses.associateBy { it.id }
        statuses = sites.map { site ->
            val error = results[site.port]?.error
            if (error != null) {
                SiteStatus(site.id, site.port, site.lan, false, describeBindError(site.port, error))
            } else {
                SiteStatus(site.id, site.port, site.lan, true, null)
            }
        }

        for (status in statuses) {
            val before = previous[status.id]
            val scope = if (status.lan) "en la red local" else "solo en este dispositivo"
            if (status.running && (before == null || !before.running || before.port != status.port || before.lan != status.lan)) {
                services.log.write(LogLevel.INFO, status.port, "Sirviendo ${siteName(sites, status.id)} en el puerto ${status.port}, $scope")
            } else if (!status.running && status.error != before?.error) {
                services.log.write(LogLevel.ERROR, status.port, "${siteName(sites, status.id)}: ${status.error}")
            }
        }
        notifyListeners()
        return statuses
    }

    @Synchronized
    fun stop() {
        val active = server ?: return
        active.shutdown()
        server = null
        if (statuses.isNotEmpty()) services.log.write(LogLevel.INFO, 0, "Servidor detenido")
        statuses = emptyList()
        notifyListeners()
    }

    val connectionCount: Int get() = server?.connectionCount ?: 0

    fun addListener(listener: (List<SiteStatus>) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (List<SiteStatus>) -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        val snapshot = statuses
        for (listener in listeners) listener(snapshot)
    }

    private fun siteName(sites: List<SiteDefinition>, id: String): String = sites.firstOrNull { it.id == id }?.name ?: id

    companion object {
        fun describeBindError(port: Int, error: Exception): String = when {
            error is BindException && error.message?.contains("in use", ignoreCase = true) == true ->
                "El puerto $port ya lo usa otra aplicación"
            error is BindException -> "No se pudo abrir el puerto $port: ${error.message}"
            error.message?.contains("denied", ignoreCase = true) == true ->
                "Android no permite el puerto $port (usa uno a partir de 1024)"
            else -> "No se pudo abrir el puerto $port: ${error.message ?: error.javaClass.simpleName}"
        }
    }
}
