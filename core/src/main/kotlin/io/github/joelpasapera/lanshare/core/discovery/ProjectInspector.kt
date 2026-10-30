package io.github.joelpasapera.lanshare.core.discovery

import io.github.joelpasapera.lanshare.core.content.ContentTree
import io.github.joelpasapera.lanshare.core.content.OpenedFile
import io.github.joelpasapera.lanshare.core.content.Probe
import io.github.joelpasapera.lanshare.core.content.RelativePath
import io.github.joelpasapera.lanshare.core.json.Json
import io.github.joelpasapera.lanshare.core.json.JsonException
import java.nio.ByteBuffer
import java.util.Locale

/** Lo que se sabe de una carpeta recién elegida. */
class Inspection(
    /** Páginas .html de la raíz, con index.html primero. */
    val pages: List<String>,
    /** Subcarpetas con su propio index.html: cada una puede ser un proyecto. */
    val subprojects: List<String>,
) {
    val isProject: Boolean get() = pages.isNotEmpty()

    /** Archivo de inicio que se propone: index.html si existe; si no, la única página; si no, ninguno. */
    val suggestedStart: String?
        get() = when {
            pages.contains("index.html") -> "index.html"
            pages.size == 1 -> pages[0]
            else -> pages.firstOrNull()
        }
}

/** Icono de la aplicación web y su color de fondo (del manifiesto, si lo hay). */
class WebAppIcon(val path: RelativePath, val backgroundColor: Int?)

/**
 * Examina el contenido de un proyecto: qué páginas tiene, si es una carpeta
 * de proyectos, y cuál es su icono. Todo pasa por el [ContentTree], así que
 * funciona igual en una carpeta SAF que en una importada.
 */
object ProjectInspector {
    private const val MAX_MANIFEST_BYTES = 256 * 1024
    private const val MAX_HTML_SCAN_BYTES = 256 * 1024
    private val RASTER = setOf("png", "jpg", "jpeg", "webp")

    fun inspect(tree: ContentTree, maxSubprojects: Int = 200): Inspection {
        val root = tree.list(RelativePath.ROOT) ?: return Inspection(emptyList(), emptyList())
        val pages = root.filter { !it.isDirectory && isHtml(it.name) }
            .map { it.name }
            .sortedWith(compareBy<String>({ !it.equals("index.html", true) }, { it.lowercase(Locale.ROOT) }))

        val subprojects = ArrayList<String>()
        if (pages.isEmpty()) {
            for (entry in root.sortedBy { it.name.lowercase(Locale.ROOT) }) {
                if (!entry.isDirectory || entry.name.startsWith('.') || subprojects.size >= maxSubprojects) continue
                val probe = tree.probe(RelativePath.ROOT.child(entry.name).child("index.html"))
                if (probe is Probe.File) {
                    probe.file.close()
                    subprojects.add(entry.name)
                }
            }
        }
        return Inspection(pages, subprojects)
    }

    private fun isHtml(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return (lower.endsWith(".html") || lower.endsWith(".htm")) && !name.startsWith('.')
    }

    /**
     * Mejor icono rasterizado: el del manifiesto (el mayor que no pase de 512
     * px, propósito "any"), los &lt;link rel="icon"&gt; de la página de inicio o
     * los nombres habituales. Los SVG se descartan: Android no los decodifica.
     */
    fun findIcon(tree: ContentTree, startFile: RelativePath?): WebAppIcon? {
        val html = startFile?.let { readText(tree, it, MAX_HTML_SCAN_BYTES) }
        val htmlDir = startFile?.parent() ?: RelativePath.ROOT

        val manifestPath = html?.let { linkHref(it, "manifest") }?.let { resolveRef(htmlDir, it) }
        val manifestCandidates = listOfNotNull(
            manifestPath,
            RelativePath.parse("manifest.webmanifest"), RelativePath.parse("manifest.json"),
            RelativePath.parse("site.webmanifest"),
        )
        for (candidate in manifestCandidates) {
            val icon = iconFromManifest(tree, candidate)
            if (icon != null) return icon
        }

        if (html != null) {
            for (rel in listOf("apple-touch-icon", "icon", "shortcut icon")) {
                val href = linkHref(html, rel) ?: continue
                val path = resolveRef(htmlDir, href) ?: continue
                if (path.extension in RASTER && exists(tree, path)) return WebAppIcon(path, null)
            }
        }

        for (name in COMMON_ICONS) {
            val path = RelativePath.parse(name) ?: continue
            if (exists(tree, path)) return WebAppIcon(path, null)
        }
        return null
    }

    private fun iconFromManifest(tree: ContentTree, manifest: RelativePath): WebAppIcon? {
        val text = readText(tree, manifest, MAX_MANIFEST_BYTES) ?: return null
        val root = try {
            Json.parse(text, 16)
        } catch (e: JsonException) {
            return null
        } as? Map<*, *> ?: return null

        val icons = root["icons"] as? List<*> ?: return null
        val dir = manifest.parent() ?: RelativePath.ROOT
        var best: RelativePath? = null
        var bestScore = Int.MIN_VALUE
        for (item in icons) {
            val icon = item as? Map<*, *> ?: continue
            val src = icon["src"] as? String ?: continue
            val path = resolveRef(dir, src) ?: continue
            val type = (icon["type"] as? String ?: "").lowercase(Locale.ROOT)
            if (path.extension !in RASTER && !type.startsWith("image/png") && !type.startsWith("image/webp")) continue
            val purpose = (icon["purpose"] as? String ?: "any").lowercase(Locale.ROOT)
            val size = largestSize(icon["sizes"] as? String ?: "")
            var score = if (size in 1..512) size else if (size > 512) 512 - (size - 512) / 8 else 96
            if (!purpose.split(' ').contains("any")) score -= 400
            if (score > bestScore && exists(tree, path)) {
                bestScore = score
                best = path
            }
        }
        val chosen = best ?: return null
        return WebAppIcon(chosen, parseColor(root["background_color"] as? String) ?: parseColor(root["theme_color"] as? String))
    }

    private fun largestSize(sizes: String): Int {
        var best = 0
        for (token in sizes.lowercase(Locale.ROOT).split(' ')) {
            val x = token.indexOf('x')
            if (x <= 0) continue
            val w = token.substring(0, x).toIntOrNull() ?: continue
            if (w > best) best = w
        }
        return best
    }

    /** href del primer &lt;link&gt; cuyo rel contiene [rel]. Basta con un análisis tolerante. */
    fun linkHref(html: String, rel: String): String? {
        var from = 0
        val lower = html.lowercase(Locale.ROOT)
        while (true) {
            val start = lower.indexOf("<link", from)
            if (start < 0) return null
            val end = lower.indexOf('>', start)
            if (end < 0) return null
            val tag = html.substring(start, end)
            val relValue = attribute(tag, "rel")?.lowercase(Locale.ROOT)
            if (relValue != null && (relValue == rel || relValue.split(' ').contains(rel))) {
                val href = attribute(tag, "href")
                if (!href.isNullOrBlank()) return href
            }
            from = end
        }
    }

    private fun attribute(tag: String, name: String): String? {
        val lower = tag.lowercase(Locale.ROOT)
        var from = 0
        while (true) {
            val at = lower.indexOf(name, from)
            if (at < 0) return null
            val before = if (at == 0) ' ' else lower[at - 1]
            var i = at + name.length
            from = i
            if (!before.isWhitespace()) continue
            while (i < tag.length && tag[i].isWhitespace()) i++
            if (i >= tag.length || tag[i] != '=') continue
            i++
            while (i < tag.length && tag[i].isWhitespace()) i++
            if (i >= tag.length) return null
            val quote = tag[i]
            return if (quote == '"' || quote == '\'') {
                val close = tag.indexOf(quote, i + 1)
                if (close < 0) null else tag.substring(i + 1, close)
            } else {
                var j = i
                while (j < tag.length && !tag[j].isWhitespace()) j++
                tag.substring(i, j)
            }
        }
    }

    /** Referencia relativa o absoluta del propio sitio; null para URLs externas o datos. */
    fun resolveRef(baseDir: RelativePath, ref: String): RelativePath? {
        var r = ref.trim()
        if (r.isEmpty() || r.startsWith("data:") || r.startsWith("//") || r.contains("://")) return null
        r = r.substringBefore('#').substringBefore('?')
        val absolute = r.startsWith("/")
        val segments = ArrayList<String>(if (absolute) emptyList() else baseDir.segments)
        for (part in r.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (segments.isEmpty()) return null else segments.removeAt(segments.size - 1)
                else -> segments.add(part)
            }
        }
        return RelativePath.of(segments)
    }

    fun parseColor(text: String?): Int? {
        if (text == null) return null
        val t = text.trim()
        if (!t.startsWith("#")) return null
        val hex = t.substring(1)
        val full = when (hex.length) {
            3 -> hex.map { "$it$it" }.joinToString("")
            6 -> hex
            else -> return null
        }
        val rgb = full.toIntOrNull(16) ?: return null
        return (0xFF shl 24) or rgb
    }

    private fun exists(tree: ContentTree, path: RelativePath): Boolean {
        val probe = tree.probe(path)
        if (probe is Probe.File) {
            probe.file.close()
            return true
        }
        return false
    }

    private fun readText(tree: ContentTree, path: RelativePath, max: Int): String? {
        val probe = tree.probe(path) as? Probe.File ?: return null
        return probe.file.use { readUpTo(it, max) }
    }

    private fun readUpTo(file: OpenedFile, max: Int): String {
        val size = minOf(file.size, max.toLong()).toInt()
        val buffer = ByteBuffer.allocate(size)
        var position = 0L
        while (buffer.hasRemaining()) {
            val n = file.read(buffer, position)
            if (n <= 0) break
            position += n
        }
        return String(buffer.array(), 0, buffer.position(), Charsets.UTF_8)
    }

    private val COMMON_ICONS = listOf(
        "apple-touch-icon.png", "icon-512.png", "icon-192.png", "icons/icon-512.png", "icons/icon-192.png",
        "icons/icon-512x512.png", "icons/icon-192x192.png", "logo.png", "icon.png", "favicon.png",
        "img/icon.png", "assets/icon.png",
    )
}
