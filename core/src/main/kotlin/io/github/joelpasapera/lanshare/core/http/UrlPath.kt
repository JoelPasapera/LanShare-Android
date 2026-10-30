package io.github.joelpasapera.lanshare.core.http

import io.github.joelpasapera.lanshare.core.content.RelativePath
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Ruta de URL ya decodificada y validada, o el motivo por el que se rechaza. */
sealed class DecodedPath {
    class Ok(val path: RelativePath, val trailingSlash: Boolean) : DecodedPath()
    class Rejected(val status: Int, val reason: String) : DecodedPath()
}

/**
 * Convierte la ruta de una URL en una [RelativePath] segura.
 *
 * El orden importa: primero se separa por '/' y DESPUÉS se decodifica cada
 * segmento. Así "%2F" no puede fabricar un separador y "%2e%2e" se ve como lo
 * que es, "..", que se rechaza: los navegadores normalizan los puntos antes de
 * enviar, así que un ".." en bruto solo lo manda quien intenta salir de la raíz.
 */
object UrlPath {

    fun decode(rawPath: String): DecodedPath {
        if (!rawPath.startsWith("/")) return DecodedPath.Rejected(400, "Ruta no absoluta")

        val trailingSlash = rawPath.endsWith("/")
        val segments = ArrayList<String>(8)

        for (raw in rawPath.split('/')) {
            if (raw.isEmpty()) continue
            val segment = percentDecode(raw) ?: return DecodedPath.Rejected(400, "Escapes %XX no válidos en la ruta")
            when {
                segment == "." -> continue
                segment == ".." -> return DecodedPath.Rejected(403, "Path traversal bloqueado")
                !RelativePath.isValidSegment(segment) ->
                    return DecodedPath.Rejected(400, "Ruta con caracteres prohibidos")
                else -> segments.add(segment)
            }
        }

        val path = RelativePath.of(segments) ?: return DecodedPath.Rejected(400, "Ruta no válida")
        return DecodedPath.Ok(path, trailingSlash || path.isRoot)
    }

    /** Decodifica %XX como UTF-8 estricto. '+' se queda como está: en una ruta no es un espacio. */
    fun percentDecode(text: String): String? {
        if (text.indexOf('%') < 0) return text
        val bytes = ByteArray(text.length)
        var n = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '%') {
                if (i + 2 >= text.length) return null
                val hi = hexValue(text[i + 1])
                val lo = hexValue(text[i + 2])
                if (hi < 0 || lo < 0) return null
                bytes[n++] = ((hi shl 4) or lo).toByte()
                i += 3
            } else {
                if (c.code > 0x7f) return null
                bytes[n++] = c.code.toByte()
                i++
            }
        }
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, 0, n))
                .toString()
        } catch (e: CharacterCodingException) {
            null
        }
    }

    /** Codifica un segmento para un href: todo salvo los caracteres no reservados de RFC 3986. */
    fun encodeSegment(segment: String): String {
        val sb = StringBuilder(segment.length + 8)
        for (b in segment.toByteArray(StandardCharsets.UTF_8)) {
            val v = b.toInt() and 0xFF
            val c = v.toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_' || c == '~') {
                sb.append(c)
            } else {
                sb.append('%').append(HEX[v shr 4]).append(HEX[v and 0xF])
            }
        }
        return sb.toString()
    }

    /** Ruta absoluta codificada a partir de segmentos, con barra final si se pide. */
    fun encodePath(path: RelativePath, trailingSlash: Boolean): String {
        val sb = StringBuilder("/")
        for ((i, s) in path.segments.withIndex()) {
            if (i > 0) sb.append('/')
            sb.append(encodeSegment(s))
        }
        if (trailingSlash && !path.isRoot) sb.append('/')
        return sb.toString()
    }

    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    private val HEX = "0123456789ABCDEF".toCharArray()
}
