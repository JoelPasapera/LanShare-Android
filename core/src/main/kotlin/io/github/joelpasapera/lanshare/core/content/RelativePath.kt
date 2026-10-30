package io.github.joelpasapera.lanshare.core.content

/**
 * Ruta relativa a la raíz de un sitio, ya validada: una lista de segmentos sin
 * vacíos, sin "." ni "..", sin separadores ni caracteres de control.
 *
 * Es la única forma en que una ruta llega a un [ContentTree]. Como no se puede
 * construir una instancia inválida, ninguna implementación necesita volver a
 * comprobar si la ruta escapa de la raíz: el tipo ya lo garantiza.
 */
class RelativePath private constructor(val segments: List<String>) {

    val isRoot: Boolean get() = segments.isEmpty()

    /** Último segmento, o cadena vacía en la raíz. */
    val name: String get() = if (segments.isEmpty()) "" else segments[segments.size - 1]

    /** Extensión en minúsculas sin el punto, o cadena vacía. */
    val extension: String
        get() {
            val n = name
            val dot = n.lastIndexOf('.')
            return if (dot > 0 && dot < n.length - 1) n.substring(dot + 1).lowercase() else ""
        }

    fun child(segment: String): RelativePath {
        require(isValidSegment(segment)) { "Segmento no válido: $segment" }
        return RelativePath(segments + segment)
    }

    fun resolve(other: RelativePath): RelativePath =
        if (other.isRoot) this else RelativePath(segments + other.segments)

    fun parent(): RelativePath? =
        if (segments.isEmpty()) null else RelativePath(segments.subList(0, segments.size - 1).toList())

    /** Algún segmento empieza por punto (".git", ".env"), salvo ".well-known". */
    fun touchesHiddenEntry(): Boolean =
        segments.any { it.startsWith('.') && it != WELL_KNOWN }

    override fun toString(): String = segments.joinToString("/")

    override fun equals(other: Any?): Boolean = other is RelativePath && other.segments == segments

    override fun hashCode(): Int = segments.hashCode()

    companion object {
        private const val WELL_KNOWN = ".well-known"

        val ROOT = RelativePath(emptyList())

        /**
         * Un segmento es válido si no está vacío, no es "." ni "..", y no
         * contiene separadores ni caracteres de control (incluido el nulo).
         */
        fun isValidSegment(segment: String): Boolean {
            if (segment.isEmpty() || segment == "." || segment == "..") return false
            for (c in segment) {
                if (c == '/' || c == '\\' || c < ' ' || c == '\u007f') return false
            }
            return true
        }

        fun of(segments: List<String>): RelativePath? =
            if (segments.all(::isValidSegment)) RelativePath(segments.toList()) else null

        /**
         * Interpreta una ruta escrita por la aplicación ("js/app.js"), no una
         * URL: no decodifica escapes. Ignora las barras repetidas y la inicial.
         */
        fun parse(path: String): RelativePath? {
            val parts = path.split('/').filter { it.isNotEmpty() }
            return of(parts)
        }
    }
}
