package io.github.joelpasapera.lanshare.core.http

/**
 * Cabeceras de una petición. Una petición trae del orden de quince, así que
 * dos listas paralelas y una búsqueda lineal ganan a cualquier tabla hash: no
 * hay que calcular códigos ni crear nodos, y todo cabe en caché.
 *
 * Los nombres se guardan en minúsculas al insertarlos; la búsqueda compara
 * contra el nombre ya en minúsculas, sin crear cadenas nuevas.
 */
class HttpHeaders {
    private val names = ArrayList<String>(16)
    private val values = ArrayList<String>(16)

    val size: Int get() = names.size

    internal fun add(name: String, value: String) {
        names.add(name.lowercase())
        values.add(value)
    }

    /** Primer valor de la cabecera, o null. [name] puede venir en cualquier caja. */
    operator fun get(name: String): String? {
        for (i in names.indices) {
            if (names[i].equals(name, ignoreCase = true)) return values[i]
        }
        return null
    }

    /** Todos los valores de la cabecera, en orden de llegada. */
    fun all(name: String): List<String> {
        val result = ArrayList<String>(1)
        for (i in names.indices) {
            if (names[i].equals(name, ignoreCase = true)) result.add(values[i])
        }
        return result
    }

    fun contains(name: String): Boolean = get(name) != null

    /** Si alguna aparición de la cabecera contiene [token] como elemento de lista (",", sin caja). */
    fun hasToken(name: String, token: String): Boolean {
        for (i in names.indices) {
            if (!names[i].equals(name, ignoreCase = true)) continue
            for (part in values[i].split(',')) {
                if (part.trim().equals(token, ignoreCase = true)) return true
            }
        }
        return false
    }

    fun forEach(action: (name: String, value: String) -> Unit) {
        for (i in names.indices) action(names[i], values[i])
    }
}
