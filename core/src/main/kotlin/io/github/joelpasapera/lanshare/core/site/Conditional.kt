package io.github.joelpasapera.lanshare.core.site

import io.github.joelpasapera.lanshare.core.http.HttpDate

/**
 * Validadores de caché (RFC 9110 §8.8 y §13).
 *
 * El ETag sale del tamaño y de la marca de modificación, no de un hash del
 * contenido: calcularlo obligaría a leer el archivo entero en cada petición,
 * justo el trabajo que un 304 quiere ahorrar.
 */
object EntityTag {

    /**
     * [variant] distingue representaciones del mismo archivo (con la consola
     * inyectada, comprimida): sin ella, un cliente podría reutilizar bytes
     * gzip como si fueran texto plano.
     */
    fun build(size: Long, versionStamp: Long, variant: String): String {
        val sb = StringBuilder(40)
        sb.append('"').append(java.lang.Long.toHexString(size)).append('-')
            .append(java.lang.Long.toHexString(versionStamp))
        if (variant.isNotEmpty()) sb.append('-').append(variant)
        return sb.append('"').toString()
    }

    /**
     * El cliente ya tiene una copia válida. If-None-Match manda sobre
     * If-Modified-Since cuando llegan las dos. La comparación es débil: "W/"
     * no impide el 304.
     */
    fun clientHasFreshCopy(ifNoneMatch: String?, ifModifiedSince: String?, etag: String, lastModifiedMillis: Long): Boolean {
        if (ifNoneMatch != null) return listed(ifNoneMatch, etag)
        return notModifiedSince(ifModifiedSince, lastModifiedMillis)
    }

    /** [etag] aparece en If-None-Match ("*" vale por cualquiera; comparación débil). */
    fun listed(ifNoneMatch: String, etag: String): Boolean {
        val trimmed = ifNoneMatch.trim()
        if (trimmed == "*") return true
        for (candidate in trimmed.split(',')) {
            var tag = candidate.trim()
            if (tag.startsWith("W/")) tag = tag.substring(2)
            if (tag == etag) return true
        }
        return false
    }

    fun notModifiedSince(ifModifiedSince: String?, lastModifiedMillis: Long): Boolean {
        if (ifModifiedSince == null || lastModifiedMillis <= 0) return false
        val since = HttpDate.parse(ifModifiedSince) ?: return false
        // HTTP solo transmite segundos: sin truncar, nunca coincidiría.
        return lastModifiedMillis / 1000 * 1000 <= since
    }
}

/** Rango de bytes inclusivo [start, end]. */
class ByteRange(val start: Long, val end: Long) {
    val length: Long get() = end - start + 1
}

/** Resultado de interpretar "Range: bytes=..." (RFC 9110 §14). */
sealed class RangeOutcome {
    /** Sin rango utilizable: se sirve el recurso entero. */
    object Whole : RangeOutcome()
    class Partial(val range: ByteRange) : RangeOutcome()
    object Unsatisfiable : RangeOutcome()
}

/**
 * Solo un rango por petición. Las listas ("0-50,100-150") se ignoran a
 * propósito: la norma permite responder con el recurso entero, y es lo que
 * piden los reproductores de vídeo, que siempre usan un único rango.
 */
object RangeParser {

    fun parse(header: String?, size: Long): RangeOutcome {
        if (header == null) return RangeOutcome.Whole
        val text = header.trim()
        if (!text.startsWith("bytes", ignoreCase = true)) return RangeOutcome.Whole
        var i = 5
        i = skipSpaces(text, i)
        if (i >= text.length || text[i] != '=') return RangeOutcome.Whole
        val spec = text.substring(i + 1).trim()
        if (spec.indexOf(',') >= 0) return RangeOutcome.Whole

        val dash = spec.indexOf('-')
        if (dash < 0) return RangeOutcome.Whole
        val rawStart = spec.substring(0, dash).trim()
        val rawEnd = spec.substring(dash + 1).trim()
        if (rawStart.isEmpty() && rawEnd.isEmpty()) return RangeOutcome.Whole
        if (!digits(rawStart) || !digits(rawEnd)) return RangeOutcome.Whole

        val start: Long
        var end: Long
        if (rawStart.isEmpty()) {
            val suffix = rawEnd.toLong()
            if (suffix <= 0 || size == 0L) return RangeOutcome.Unsatisfiable
            start = maxOf(0L, size - suffix)
            end = size - 1
        } else {
            start = rawStart.toLong()
            end = if (rawEnd.isEmpty()) size - 1 else rawEnd.toLong()
            if (rawEnd.isNotEmpty() && end < start) return RangeOutcome.Whole
        }

        if (end >= size) end = size - 1
        if (size == 0L || start >= size) return RangeOutcome.Unsatisfiable
        return RangeOutcome.Partial(ByteRange(start, end))
    }

    private fun digits(s: String): Boolean = s.length <= 18 && s.all { it in '0'..'9' }

    private fun skipSpaces(s: String, from: Int): Int {
        var i = from
        while (i < s.length && (s[i] == ' ' || s[i] == '\t')) i++
        return i
    }
}
