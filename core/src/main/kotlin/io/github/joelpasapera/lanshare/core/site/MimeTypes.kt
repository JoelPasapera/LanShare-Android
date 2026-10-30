package io.github.joelpasapera.lanshare.core.site

/**
 * Extensión de archivo → Content-Type. De solo lectura tras la carga, así que
 * es seguro entre hilos.
 *
 * Lo crítico para aplicaciones web modernas: .mjs como JavaScript (los
 * módulos se rechazan con cualquier otro tipo), .wasm como application/wasm
 * (sin él no hay compilación en flujo) y .webmanifest para instalar PWAs.
 */
object MimeTypes {
    const val FALLBACK = "application/octet-stream"

    private val map: Map<String, String> = HashMap<String, String>(128).apply {
        val utf8 = "; charset=utf-8"
        put("html", "text/html$utf8")
        put("htm", "text/html$utf8")
        put("xhtml", "application/xhtml+xml$utf8")
        put("css", "text/css$utf8")
        put("js", "text/javascript$utf8")
        put("mjs", "text/javascript$utf8")
        put("cjs", "text/javascript$utf8")
        put("json", "application/json$utf8")
        put("map", "application/json$utf8")
        put("jsonld", "application/ld+json$utf8")
        put("webmanifest", "application/manifest+json$utf8")
        put("wasm", "application/wasm")
        put("wgsl", "text/wgsl$utf8")
        put("txt", "text/plain$utf8")
        put("md", "text/markdown$utf8")
        put("csv", "text/csv$utf8")
        put("tsv", "text/tab-separated-values$utf8")
        put("xml", "application/xml$utf8")
        put("rss", "application/rss+xml$utf8")
        put("atom", "application/atom+xml$utf8")
        put("yaml", "application/yaml$utf8")
        put("yml", "application/yaml$utf8")
        put("vtt", "text/vtt$utf8")
        put("srt", "application/x-subrip$utf8")
        put("ics", "text/calendar$utf8")
        put("png", "image/png")
        put("apng", "image/apng")
        put("jpg", "image/jpeg")
        put("jpeg", "image/jpeg")
        put("gif", "image/gif")
        put("svg", "image/svg+xml")
        put("webp", "image/webp")
        put("avif", "image/avif")
        put("jxl", "image/jxl")
        put("heic", "image/heic")
        put("ico", "image/x-icon")
        put("bmp", "image/bmp")
        put("ktx2", "image/ktx2")
        put("mp3", "audio/mpeg")
        put("ogg", "audio/ogg")
        put("oga", "audio/ogg")
        put("opus", "audio/ogg")
        put("wav", "audio/wav")
        put("flac", "audio/flac")
        put("m4a", "audio/mp4")
        put("aac", "audio/aac")
        put("mid", "audio/midi")
        put("midi", "audio/midi")
        put("mp4", "video/mp4")
        put("m4v", "video/mp4")
        put("webm", "video/webm")
        put("ogv", "video/ogg")
        put("mov", "video/quicktime")
        put("mkv", "video/x-matroska")
        put("glb", "model/gltf-binary")
        put("gltf", "model/gltf+json")
        put("obj", "model/obj")
        put("stl", "model/stl")
        put("pdf", "application/pdf")
        put("zip", "application/zip")
        put("gz", "application/gzip")
        put("tar", "application/x-tar")
        put("7z", "application/x-7z-compressed")
        put("epub", "application/epub+zip")
        put("apk", "application/vnd.android.package-archive")
        put("doc", "application/msword")
        put("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
        put("xls", "application/vnd.ms-excel")
        put("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        put("ppt", "application/vnd.ms-powerpoint")
        put("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation")
        put("odt", "application/vnd.oasis.opendocument.text")
        put("ttf", "font/ttf")
        put("otf", "font/otf")
        put("woff", "font/woff")
        put("woff2", "font/woff2")
        put("eot", "application/vnd.ms-fontobject")
    }

    /** [extension] en minúsculas y sin punto, como lo da RelativePath.extension. */
    fun forExtension(extension: String): String = map[extension] ?: FALLBACK

    fun isHtml(contentType: String): Boolean = contentType.startsWith("text/html")

    /** Merece la pena comprimir con gzip: texto y formatos que no vienen ya comprimidos. */
    fun isCompressible(contentType: String): Boolean {
        val base = contentType.substringBefore(';')
        return base.startsWith("text/") ||
            base == "application/json" || base == "application/manifest+json" ||
            base == "application/ld+json" || base == "application/xml" || base == "application/xhtml+xml" ||
            base == "application/rss+xml" || base == "application/atom+xml" || base == "application/yaml" ||
            base == "application/wasm" || base == "image/svg+xml" || base == "model/gltf+json" ||
            base == "application/x-subrip" || base == "model/obj" || base == "image/x-icon"
    }
}
