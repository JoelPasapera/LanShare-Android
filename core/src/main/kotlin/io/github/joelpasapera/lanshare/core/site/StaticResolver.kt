package io.github.joelpasapera.lanshare.core.site

import io.github.joelpasapera.lanshare.core.content.ContentTree
import io.github.joelpasapera.lanshare.core.content.DirEntry
import io.github.joelpasapera.lanshare.core.content.OpenedFile
import io.github.joelpasapera.lanshare.core.content.Probe
import io.github.joelpasapera.lanshare.core.content.RelativePath
import io.github.joelpasapera.lanshare.core.http.UrlPath

/** Qué hacer con una ruta. Si es [ServeFile], quien la recibe debe cerrar el archivo. */
sealed class Resolution {
    class ServeFile(val path: RelativePath, val file: OpenedFile) : Resolution()
    class Listing(val path: RelativePath, val entries: List<DirEntry>) : Resolution()
    /** [permanent]: 301 (la barra final de una carpeta); si no, 302 (el archivo de inicio puede cambiar). */
    class Redirect(val location: String, val permanent: Boolean) : Resolution()
    class NotFound(val reason: String) : Resolution()
}

/**
 * Traduce una ruta ya validada en una decisión de servicio. Portado del
 * StaticFileResolver de LanShare para Windows, con una diferencia: en lugar
 * de preguntar "¿existe?" y después abrir, se abre directamente y el árbol
 * dice qué había. En SAF eso ahorra una llamada entre procesos por archivo.
 *
 * Reglas:
 * - La raíz sirve el archivo de inicio. Si está en una subcarpeta, se
 *   redirige a él para que sus rutas relativas resuelvan bien.
 * - Una carpeta sin barra final se redirige con ella (si no, el navegador
 *   resolvería mal las rutas relativas del documento).
 * - Una carpeta con barra final sirve su índice o, si no lo tiene, su listado.
 * - Una ruta sin extensión que no existe sirve el archivo de inicio
 *   (aplicaciones de página única con enrutado propio). Con extensión no:
 *   un .js que falta debe dar 404, no HTML.
 * - Lo que empieza por punto (.git, .env) no existe hacia fuera.
 */
class StaticResolver(
    private val tree: ContentTree,
    /** null: se sirve como carpeta, sin índice ni repliegue, siempre con listado. */
    private val startFile: RelativePath?,
    private val allowListing: Boolean,
    private val spaFallback: Boolean,
) {
    private val indexNames: List<String> = buildList {
        if (startFile != null && startFile.segments.size == 1) add(startFile.name)
        if (startFile != null && !contains(DEFAULT_INDEX)) add(DEFAULT_INDEX)
    }

    fun resolve(path: RelativePath, trailingSlash: Boolean, query: String): Resolution {
        if (path.touchesHiddenEntry()) return Resolution.NotFound("Archivo oculto")

        if (path.isRoot) {
            if (startFile != null && startFile.segments.size > 1) {
                return Resolution.Redirect(UrlPath.encodePath(startFile, false), permanent = false)
            }
            return directory(path)
        }

        return when (val probe = tree.probe(path)) {
            is Probe.File -> Resolution.ServeFile(path, probe.file)
            is Probe.Directory -> {
                if (!trailingSlash) {
                    var location = UrlPath.encodePath(path, true)
                    if (query.isNotEmpty()) location += "?$query"
                    Resolution.Redirect(location, permanent = true)
                } else {
                    directory(path)
                }
            }
            is Probe.Missing -> fallback(path, trailingSlash)
        }
    }

    private fun directory(path: RelativePath): Resolution {
        for (name in indexNames) {
            when (val probe = tree.probe(path.child(name))) {
                is Probe.File -> return Resolution.ServeFile(path.child(name), probe.file)
                else -> Unit
            }
        }
        if (allowListing || startFile == null) {
            val entries = tree.list(path) ?: return Resolution.NotFound("No se pudo leer la carpeta")
            return Resolution.Listing(path, entries)
        }
        return Resolution.NotFound("Carpeta sin ${indexNames.firstOrNull() ?: DEFAULT_INDEX}")
    }

    private fun fallback(path: RelativePath, trailingSlash: Boolean): Resolution {
        if (startFile != null && spaFallback && !trailingSlash && path.extension.isEmpty()) {
            val probe = tree.probe(startFile)
            if (probe is Probe.File) return Resolution.ServeFile(startFile, probe.file)
        }
        return Resolution.NotFound("Archivo no encontrado")
    }

    companion object {
        const val DEFAULT_INDEX = "index.html"
    }
}
