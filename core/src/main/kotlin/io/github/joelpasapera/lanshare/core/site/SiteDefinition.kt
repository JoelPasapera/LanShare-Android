package io.github.joelpasapera.lanshare.core.site

import io.github.joelpasapera.lanshare.core.clients.ClientRegistry
import io.github.joelpasapera.lanshare.core.console.ConsoleRelay
import io.github.joelpasapera.lanshare.core.content.ContentTree
import io.github.joelpasapera.lanshare.core.content.RelativePath
import io.github.joelpasapera.lanshare.core.log.LogBus

/** Un sitio en marcha: qué carpeta, en qué puerto y con qué alcance. */
class SiteDefinition(
    val id: String,
    val name: String,
    val port: Int,
    /** true: toda la red local; false: solo este dispositivo (127.0.0.1). */
    val lan: Boolean,
    val tree: ContentTree,
    /** Archivo de inicio; null para servir la carpeta con su listado. */
    val startFile: RelativePath?,
    /** Access-Control-Allow-Origin: * para que otras páginas lo consuman. */
    val cors: Boolean = false,
    /**
     * Cross-Origin-Opener-Policy y Cross-Origin-Embedder-Policy: habilitan
     * SharedArrayBuffer, que exigen WebAssembly con hilos y algunas librerías
     * de audio. Solo surten efecto en contexto seguro (127.0.0.1 lo es; una IP
     * de la red local por HTTP, no).
     */
    val crossOriginIsolation: Boolean = false,
)

/** Opciones comunes a todos los sitios. */
class ServerOptions(
    val remoteConsole: Boolean = false,
    val directoryListing: Boolean = true,
    val spaFallback: Boolean = true,
    /** gzip para los clientes de la red local (en 127.0.0.1 no compensa). */
    val compressForLan: Boolean = true,
)

/** Servicios compartidos por todos los sitios: registro, clientes, consola y versiones gzip. */
class ServerServices(
    val log: LogBus,
    val clients: ClientRegistry = ClientRegistry(),
    val console: ConsoleRelay = ConsoleRelay(log),
    val gzip: GzipVariants = GzipVariants.forHeap(),
) {
    /** El contenido de un sitio cambió por completo (reinstalado): nada de lo cacheado vale. */
    fun forgetSite(siteId: String) {
        gzip.forget(SiteHandler.cachePrefix(siteId))
    }
}
