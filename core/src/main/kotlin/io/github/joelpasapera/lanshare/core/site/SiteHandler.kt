package io.github.joelpasapera.lanshare.core.site

import io.github.joelpasapera.lanshare.core.compress.GzipEncoder
import io.github.joelpasapera.lanshare.core.console.ConsoleLine
import io.github.joelpasapera.lanshare.core.console.ConsoleScript
import io.github.joelpasapera.lanshare.core.content.ContentReadException
import io.github.joelpasapera.lanshare.core.content.OpenedFile
import io.github.joelpasapera.lanshare.core.content.RelativePath
import io.github.joelpasapera.lanshare.core.http.DecodedPath
import io.github.joelpasapera.lanshare.core.http.HttpDate
import io.github.joelpasapera.lanshare.core.http.HttpHandler
import io.github.joelpasapera.lanshare.core.http.HttpRequest
import io.github.joelpasapera.lanshare.core.http.HttpResponse
import io.github.joelpasapera.lanshare.core.http.HttpStatus
import io.github.joelpasapera.lanshare.core.http.UrlPath
import io.github.joelpasapera.lanshare.core.log.LogLevel
import io.github.joelpasapera.lanshare.core.net.HostGuard
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * Atiende un sitio: archivos, listados, redirecciones, la consola remota y el
 * registro de cada petición.
 *
 * Responsabilidad única: la política HTTP de un sitio estático. Dónde están
 * los bytes lo resuelve el [io.github.joelpasapera.lanshare.core.content.ContentTree];
 * el transporte, el servidor.
 *
 * Memoria: los archivos se envían en trozos, también con la consola
 * inyectada. Solo gzip necesita el resultado entero en memoria, y eso lo
 * administra [GzipVariants] con presupuesto propio.
 */
class SiteHandler(
    private val site: SiteDefinition,
    private val options: ServerOptions,
    private val services: ServerServices,
) : HttpHandler {

    private val resolver = StaticResolver(site.tree, site.startFile, options.directoryListing, options.spaFallback)
    private val keyPrefix = cachePrefix(site.id)

    private class Outcome(val level: LogLevel?, val note: String)

    override fun handle(request: HttpRequest, response: HttpResponse) {
        val started = System.nanoTime()
        var outcome = Outcome(LogLevel.ERROR, "Respuesta interrumpida")
        try {
            applyCommonHeaders(response)
            outcome = dispatch(request, response)
        } catch (e: IOException) {
            // Lo normal: el navegador cancela (un vídeo al saltar, una pestaña cerrada).
            outcome = Outcome(LogLevel.WARN, "Conexión cortada" + (e.message?.let { ": $it" } ?: ""))
            throw e
        } catch (e: ContentReadException) {
            outcome = Outcome(LogLevel.ERROR, "No se pudo leer el archivo: " + (e.message ?: e.javaClass.simpleName))
            if (!response.isCommitted) {
                response.closeConnection = true
                response.sendErrorPage(500, "No se pudo leer el archivo. ¿Se retiró el permiso de la carpeta o se quitó la tarjeta?")
            }
            // Si ya había empezado, la respuesta queda incompleta y el servidor cierra la conexión.
        } catch (e: RuntimeException) {
            outcome = Outcome(LogLevel.ERROR, "Fallo interno: " + e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
            throw e
        } finally {
            services.clients.record(request.clientIp, request.isLoopback, site.port, request.headers["User-Agent"], response.bodyBytes)
            val level = outcome.level
            if (level != null) {
                val millis = (System.nanoTime() - started) / 1_000_000
                services.log.write(level, site.port, RequestLog.line(request, response.status, response.bodyBytes, millis, outcome.note))
            }
        }
    }

    private fun applyCommonHeaders(response: HttpResponse) {
        // Revalidar siempre: un 304 cuesta casi nada y nunca se ve una versión vieja.
        response.header("Cache-Control", "no-cache")
        response.header("X-Content-Type-Options", "nosniff")
        if (site.cors) response.header("Access-Control-Allow-Origin", "*")
        if (site.crossOriginIsolation) {
            response.header("Cross-Origin-Opener-Policy", "same-origin")
            response.header("Cross-Origin-Embedder-Policy", "require-corp")
        }
    }

    private fun dispatch(request: HttpRequest, response: HttpResponse): Outcome {
        // DNS rebinding: una web que hace apuntar su dominio a este móvil llega
        // aquí con su propio nombre en Host. Solo se atienden nombres que no se
        // pueden controlar desde Internet (IP, localhost, .local...).
        val host = request.targetAuthority ?: request.headers["Host"]
        if (!host.isNullOrEmpty() && !HostGuard.isLocalHostName(host)) {
            response.sendErrorPage(421, "Este servidor no atiende el nombre «$host». Ábrelo con su IP o con localhost.")
            return Outcome(LogLevel.SECURITY, "Nombre ajeno «$host» en la petición (posible DNS rebinding)")
        }

        val method = request.method

        if (method == "OPTIONS") {
            response.header("Allow", ALLOW)
            if (site.cors) {
                response.header("Access-Control-Allow-Methods", ALLOW)
                response.header("Access-Control-Allow-Headers", request.headers["Access-Control-Request-Headers"] ?: "*")
                response.header("Access-Control-Max-Age", "600")
            }
            response.sendEmpty(204)
            return Outcome(LogLevel.INFO, "")
        }

        when (request.rawPath) {
            ConsoleScript.ENDPOINT -> {
                if (!options.remoteConsole) {
                    response.sendErrorPage(404, "Consola remota desactivada")
                    return Outcome(LogLevel.WARN, "Consola remota desactivada")
                }
                val result = services.console.handle(request, response, site.port)
                return when (result.status) {
                    204 -> Outcome(null, "")
                    403 -> Outcome(LogLevel.SECURITY, result.note)
                    else -> Outcome(LogLevel.WARN, result.note)
                }
            }
            ConsoleScript.PATH -> if (options.remoteConsole && (method == "GET" || method == "HEAD")) {
                serveConsoleScript(request, response)
                return Outcome(null, "")
            }
        }

        if (method != "GET" && method != "HEAD") {
            response.header("Allow", ALLOW)
            response.sendErrorPage(405, "Método $method no permitido")
            return Outcome(LogLevel.WARN, "Método no permitido")
        }

        val decoded = UrlPath.decode(request.rawPath)
        if (decoded is DecodedPath.Rejected) {
            response.sendErrorPage(decoded.status, decoded.reason)
            return Outcome(if (decoded.status == 403) LogLevel.SECURITY else LogLevel.WARN, decoded.reason)
        }
        decoded as DecodedPath.Ok

        return when (val resolution = resolver.resolve(decoded.path, decoded.trailingSlash, request.query)) {
            is Resolution.Redirect -> {
                response.header("Location", resolution.location)
                response.sendEmpty(if (resolution.permanent) 301 else 302)
                Outcome(LogLevel.INFO, "→ " + resolution.location)
            }
            is Resolution.NotFound -> {
                response.sendErrorPage(404, resolution.reason)
                Outcome(LogLevel.WARN, resolution.reason)
            }
            is Resolution.Listing -> serveListing(request, response, resolution.path, resolution)
            is Resolution.ServeFile -> resolution.file.use { serveFile(request, response, resolution.path, it) }
        }
    }

    private fun serveFile(request: HttpRequest, response: HttpResponse, path: RelativePath, file: OpenedFile): Outcome {
        val contentType = MimeTypes.forExtension(path.extension)
        val inject = options.remoteConsole && MimeTypes.isHtml(contentType)
        val compressible = MimeTypes.isCompressible(contentType)
        val gzipAllowed = compressible && !request.isLoopback && options.compressForLan &&
            file.size in MIN_GZIP_BYTES..GzipVariants.MAX_SOURCE_BYTES &&
            request.headers["Range"] == null && acceptsGzip(request.headers["Accept-Encoding"])

        // El ETag distingue las representaciones (con consola, comprimida): sin
        // eso, un cliente podría reutilizar bytes gzip como si fueran texto.
        val plainTag = EntityTag.build(file.size, file.versionStamp, if (inject) "c" else "")
        val gzipTag = EntityTag.build(file.size, file.versionStamp, if (inject) "cz" else "z")

        response.header("Content-Type", contentType)
        if (file.lastModifiedMillis > 0) response.header("Last-Modified", HttpDate.format(file.lastModifiedMillis))
        if (compressible) response.header("Vary", "Accept-Encoding")

        // 304 sin leer ni comprimir nada. Vale cualquiera de las dos versiones
        // que el cliente acepte: las dos son el mismo contenido.
        val ifNoneMatch = request.headers["If-None-Match"]
        val fresh = when {
            ifNoneMatch != null -> when {
                EntityTag.listed(ifNoneMatch, plainTag) -> plainTag
                gzipAllowed && EntityTag.listed(ifNoneMatch, gzipTag) -> gzipTag
                else -> null
            }
            EntityTag.notModifiedSince(request.headers["If-Modified-Since"], file.lastModifiedMillis) ->
                if (gzipAllowed) gzipTag else plainTag
            else -> null
        }
        if (fresh != null) {
            response.header("ETag", fresh)
            response.sendEmpty(304)
            return Outcome(LogLevel.INFO, "")
        }

        if (gzipAllowed) {
            val key = keyPrefix + path + '\u0000' + gzipTag
            // HEAD no comprime nada solo para medirlo: si no estaba hecho, describe la versión sin comprimir.
            val compressed = services.gzip.obtain(key, file.size, cachedOnly = request.isHead) { out ->
                if (inject) FileBodies.write(file, CONSOLE_TAG, injectionPoint(file), out)
                else FileBodies.copy(file, 0, file.size, out)
            }
            if (compressed != null) {
                response.header("ETag", gzipTag)
                response.header("Content-Encoding", "gzip")
                response.send(200, null, compressed.bytes)
                val note = "gzip " + percent(compressed.bytes.size.toLong(), file.size) + (if (compressed.fromCache) " (caché)" else "")
                return Outcome(LogLevel.INFO, note)
            }
            // No compensa, no hay memoria libre para comprimir o ya se está comprimiendo: tal cual.
        }

        response.header("ETag", plainTag)
        if (inject) {
            // Con la consola, la longitud cambia y los rangos no se ofrecen. El
            // punto de inserción se busca antes de la cabecera: si el archivo no
            // se puede leer, aún se puede contestar 500.
            val at = if (request.isHead) 0L else injectionPoint(file)
            val body = response.begin(200, file.size + CONSOLE_TAG.size)
            if (!request.isHead) FileBodies.write(file, CONSOLE_TAG, at, body)
            return Outcome(LogLevel.INFO, "")
        }

        response.header("Accept-Ranges", "bytes")
        val rangeHeader = if (rangeStillValid(request, plainTag, file)) request.headers["Range"] else null
        var range: ByteRange? = null
        when (val parsed = RangeParser.parse(rangeHeader, file.size)) {
            is RangeOutcome.Whole -> Unit
            is RangeOutcome.Partial -> range = parsed.range
            is RangeOutcome.Unsatisfiable -> {
                response.clearRepresentation()
                response.header("Content-Range", "bytes */" + file.size)
                response.send(416, HTML_UTF8, HttpStatus.errorPage(416, "Rango fuera del archivo").toByteArray(StandardCharsets.UTF_8))
                return Outcome(LogLevel.WARN, "Rango fuera del archivo")
            }
        }

        val start = range?.start ?: 0L
        val length = range?.length ?: file.size
        val status = if (range != null) 206 else 200
        if (range != null) response.header("Content-Range", "bytes ${range.start}-${range.end}/${file.size}")
        if (request.isHead || length == 0L) response.begin(status, length)
        else FileBodies.send(file, start, length) { response.begin(status, length) }
        return Outcome(LogLevel.INFO, if (range != null) "bytes ${range.start}-${range.end}" else "")
    }

    /** If-Range: el rango solo vale si el cliente tiene la versión actual (RFC 9110 §13.1.5). */
    private fun rangeStillValid(request: HttpRequest, etag: String, file: OpenedFile): Boolean {
        val ifRange = request.headers["If-Range"] ?: return true
        if (ifRange.startsWith("\"") || ifRange.startsWith("W/")) return ifRange == etag
        val date = HttpDate.parse(ifRange) ?: return false
        return file.lastModifiedMillis > 0 && file.lastModifiedMillis / 1000 * 1000 == date
    }

    private fun injectionPoint(file: OpenedFile): Long = HtmlInjector.injectionPoint(FileWindow(file), file.size)

    private fun serveListing(request: HttpRequest, response: HttpResponse, path: RelativePath, listing: Resolution.Listing): Outcome {
        val html = DirectoryPage.build(site.name, path, listing.entries).toByteArray(StandardCharsets.UTF_8)
        response.header("Content-Type", HTML_UTF8)
        response.header("Vary", "Accept-Encoding")
        if (!request.isLoopback && options.compressForLan && html.size >= MIN_GZIP_BYTES &&
            acceptsGzip(request.headers["Accept-Encoding"])
        ) {
            response.header("Content-Encoding", "gzip")
            response.send(200, null, GzipEncoder.compress(html))
        } else {
            response.send(200, null, html)
        }
        return Outcome(LogLevel.INFO, "listado")
    }

    private fun serveConsoleScript(request: HttpRequest, response: HttpResponse) {
        response.header("Content-Type", "text/javascript; charset=utf-8")
        response.header("ETag", CONSOLE_ETAG)
        if (request.headers["If-None-Match"] == CONSOLE_ETAG) {
            response.sendEmpty(304)
            return
        }
        response.send(200, null, CONSOLE_SOURCE)
    }

    companion object {
        private const val ALLOW = "GET, HEAD, POST, OPTIONS"
        private const val HTML_UTF8 = "text/html; charset=utf-8"
        private const val MIN_GZIP_BYTES = 1024L

        private val CONSOLE_TAG = ConsoleScript.TAG.toByteArray(StandardCharsets.UTF_8)
        private val CONSOLE_SOURCE = ConsoleScript.SOURCE.toByteArray(StandardCharsets.UTF_8)
        private val CONSOLE_ETAG = "\"consola-" + Integer.toHexString(ConsoleScript.SOURCE.hashCode()) + "\""

        /** Prefijo de las claves de caché de un sitio: permite olvidarlas todas de una vez. */
        fun cachePrefix(siteId: String): String = siteId + '\u0000'

        /**
         * Accept-Encoding admite gzip (RFC 9110 §12.5.3): por nombre o, si no
         * aparece, por "*", y siempre con q &gt; 0. Los parámetros se leen sin
         * distinguir caja y en cualquier posición ("gzip;Q=0", "gzip;x=1;q=0").
         * Un q ilegible cuenta como 0: sin comprimir nunca se falla.
         */
        internal fun acceptsGzip(accept: String?): Boolean {
            if (accept.isNullOrEmpty()) return false
            var gzipQ = -1.0
            var anyQ = -1.0
            for (element in accept.split(',')) {
                val parts = element.split(';')
                val coding = parts[0].trim()
                if (coding.isEmpty()) continue
                var q = 1.0
                for (k in 1 until parts.size) {
                    val param = parts[k]
                    val eq = param.indexOf('=')
                    if (eq < 0 || !param.substring(0, eq).trim().equals("q", ignoreCase = true)) continue
                    val value = param.substring(eq + 1).trim().toDoubleOrNull()
                    q = if (value == null || value.isNaN() || value < 0.0 || value > 1.0) 0.0 else value
                }
                when {
                    coding.equals("gzip", ignoreCase = true) || coding.equals("x-gzip", ignoreCase = true) -> gzipQ = maxOf(gzipQ, q)
                    coding == "*" -> anyQ = maxOf(anyQ, q)
                }
            }
            return if (gzipQ >= 0.0) gzipQ > 0.0 else anyQ > 0.0
        }

        private fun percent(compressed: Long, original: Long): String =
            if (original <= 0) "" else "−" + (100 - compressed * 100 / original) + " %"
    }
}

/** Formato de una línea de petición en el registro. */
object RequestLog {
    fun line(request: HttpRequest, status: Int, bytes: Long, millis: Long, note: String): String {
        val sb = StringBuilder(128)
        sb.append(request.clientIp).append("  ")
            .append(request.method).append(' ')
            .append(ConsoleLine.flatten(request.target, 300)).append("  ")
            .append(status)
        if (bytes > 0) sb.append("  ").append(DirectoryPage.formatSize(bytes))
        sb.append("  ").append(millis).append(" ms")
        if (note.isNotEmpty()) sb.append("  · ").append(ConsoleLine.flatten(note, 300))
        return sb.toString()
    }
}
