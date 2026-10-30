package io.github.joelpasapera.lanshare.core.console

import io.github.joelpasapera.lanshare.core.log.LogLevel

/**
 * Convierte un mensaje de consola en una sola línea del registro:
 * `/index.html  TypeError: x no es una función  @ /js/app.js:42:7 <- /js/main.js:10:3  (x5)`
 *
 * El texto llega de la red, así que además de acortarlo se neutraliza: sin
 * saltos de línea (una entrada, una línea), sin caracteres de control y sin
 * los de dirección bidireccional, que permitirían que una línea del registro
 * se leyera distinta de lo que dice. Portado de LanShare para Windows.
 */
object ConsoleLine {
    const val MAX_TEXT_LENGTH = 600
    const val MAX_PAGE_LENGTH = 160
    const val MAX_LOCATION_LENGTH = 200
    const val MAX_FRAMES = 3

    fun level(level: String): LogLevel = when (level) {
        "error" -> LogLevel.JS_ERROR
        "warn" -> LogLevel.JS_WARN
        "info" -> LogLevel.JS_INFO
        "debug" -> LogLevel.JS_DEBUG
        else -> LogLevel.JS_LOG
    }

    /** [origin] es "http://host:puerto" del sitio, para acortar las rutas de la pila. */
    fun format(entry: ConsoleEntry, client: String, page: String, origin: String): String {
        val line = StringBuilder(160)
        line.append(flatten(client, 64))
        val where = flatten(page, MAX_PAGE_LENGTH)
        if (where.isNotEmpty()) line.append(' ').append(where)
        line.append("  ").append(flatten(entry.text, MAX_TEXT_LENGTH))
        val frames = frames(entry.where, entry.stack, origin)
        if (frames.isNotEmpty()) line.append("  @ ").append(frames)
        if (entry.count > 1) line.append("  (x").append(entry.count).append(')')
        return line.toString()
    }

    /** De dónde viene el mensaje: los primeros marcos de la pila, o la ubicación de la llamada. */
    fun frames(where: String, stack: String, origin: String): String {
        val frames = stackLocations(stack, origin, MAX_FRAMES)
        if (frames.isEmpty()) return flatten(shorten(where, origin), MAX_LOCATION_LENGTH)
        return frames.joinToString(" <- ")
    }

    /**
     * Ubicaciones de una pila de V8 ("    at f (url:1:2)"), Gecko o
     * JavaScriptCore ("f@url:1:2"). La primera línea de V8 repite el mensaje y
     * se descarta: solo cuentan las líneas con forma de marco.
     */
    fun stackLocations(stack: String, origin: String, max: Int): List<String> {
        val found = ArrayList<String>()
        if (stack.isEmpty()) return found
        for (line in stack.split('\n')) {
            if (found.size >= max) break
            if (!isFrame(line)) continue
            val location = locate(line) ?: continue
            found.add(flatten(shorten(location, origin), MAX_LOCATION_LENGTH))
        }
        return found
    }

    private fun isFrame(line: String): Boolean {
        var i = 0
        while (i < line.length && (line[i] == ' ' || line[i] == '\t')) i++
        val v8 = i + 3 <= line.length && line[i] == 'a' && line[i + 1] == 't' &&
            (line[i + 2] == ' ' || line[i + 2] == '\t')
        return v8 || line.indexOf('@') >= 0
    }

    /** "url:línea:columna" al final de un marco, o null. Se recorre desde el final porque la URL también lleva ':'. */
    fun locate(line: String): String? {
        var end = line.length
        while (end > 0 && (line[end - 1].isWhitespace() || line[end - 1] == ')')) end--

        var i = end
        while (i > 0 && line[i - 1] in '0'..'9') i--
        if (i == end || i == 0 || line[i - 1] != ':') return null

        val lineEnd = --i
        while (i > 0 && line[i - 1] in '0'..'9') i--
        if (i == lineEnd || i == 0 || line[i - 1] != ':') return null

        val urlEnd = --i
        while (i > 0 && !line[i - 1].isWhitespace() && line[i - 1] != '(' && line[i - 1] != '@') i--
        if (i == urlEnd) return null

        return line.substring(i, end)
    }

    /** Quita el origen: "http://192.168.1.5:8080/js/a.js:1:2" pasa a "/js/a.js:1:2". */
    fun shorten(location: String, origin: String): String {
        if (location.isEmpty()) return ""
        if (origin.isEmpty() || location.length <= origin.length) return location
        if (!location.startsWith(origin, ignoreCase = true)) return location
        val rest = location.substring(origin.length)
        return if (rest.isNotEmpty() && rest[0] == '/') rest else location
    }

    /**
     * Una sola línea, sin caracteres de control ni de dirección, y como mucho
     * [max] caracteres más "...". Los saltos se ven como " | ".
     */
    fun flatten(text: String, max: Int): String {
        if (text.isEmpty() || max <= 0) return ""
        val sb = StringBuilder(minOf(text.length, max) + 4)
        var pendingBreak = false
        for (c in text) {
            if (c == '\r' || c == '\n' || c == '\u0085' || c == ' ' || c == ' ') {
                pendingBreak = sb.isNotEmpty()
                continue
            }
            if (pendingBreak) {
                sb.append(" | ")
                pendingBreak = false
            }
            sb.append(if (isUnsafe(c)) ' ' else c)
            if (sb.length > max) {
                var cut = max
                if (Character.isHighSurrogate(sb[cut - 1])) cut--
                sb.setLength(cut)
                sb.append("...")
                return sb.toString()
            }
        }
        return sb.toString()
    }

    private fun isUnsafe(c: Char): Boolean {
        if (c < ' ' || c == '\u007f') return true
        if (c in '\u0080'..'\u009f') return true
        if (c in '‪'..'‮') return true
        if (c in '⁦'..'⁩') return true
        return c == '‎' || c == '‏' || c == '؜'
    }
}
