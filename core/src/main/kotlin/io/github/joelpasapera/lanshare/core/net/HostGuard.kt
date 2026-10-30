package io.github.joelpasapera.lanshare.core.net

/**
 * Comprobaciones de origen. Portado del UploadGuard de LanShare para Windows.
 *
 * - [isLocalHostName] se aplica a todas las peticiones: frena el DNS
 *   rebinding, con el que una web hace que su dominio apunte al móvil y, como
 *   para el navegador sigue siendo "su" origen, lee lo que se sirve.
 * - [isSameOrigin], además, a los puntos que aceptan escritura (la consola
 *   remota): una petición POST con cuerpo de texto es "simple", el navegador
 *   la envía a otro origen sin preguntar y CORS solo impide leer la respuesta.
 */
object HostGuard {
    /**
     * Dominios que no se pueden registrar en Internet: reservados para redes
     * locales (.local, .home.arpa, .internal...) o nombres de uso doméstico
     * que no existen en la raíz del DNS (.lan, .home, .localdomain).
     */
    private val LOCAL_SUFFIXES = arrayOf(".localhost", ".local", ".lan", ".home.arpa", ".internal", ".localdomain", ".home")

    fun isSameOrigin(origin: String?, host: String?, secFetchSite: String?): Boolean {
        // DNS rebinding: una web puede hacer que su dominio resuelva a la IP del
        // móvil y entonces Origin y Host coinciden. Solo se admiten nombres que
        // no se pueden controlar desde Internet.
        if (!isLocalHostName(host)) return false

        if (!secFetchSite.isNullOrEmpty()) {
            if (!secFetchSite.equals("same-origin", ignoreCase = true) &&
                !secFetchSite.equals("none", ignoreCase = true)
            ) return false
        }

        if (origin.isNullOrEmpty()) return true
        if (origin == "null") return false
        if (host.isNullOrEmpty()) return false
        return origin.trimEnd('/').equals("http://$host", ignoreCase = true)
    }

    /**
     * La cabecera Host nombra a este equipo de una forma que no se puede
     * suplantar con DNS: una IP literal, un nombre sin puntos, localhost o un
     * dominio reservado para redes locales.
     */
    fun isLocalHostName(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        var name = host.trim()

        if (name.startsWith("[")) {
            val close = name.indexOf(']')
            return close > 1 && isIpv6Literal(name.substring(1, close))
        }

        val colon = name.lastIndexOf(':')
        if (colon >= 0) name = name.substring(0, colon)
        name = name.trimEnd('.')
        if (name.isEmpty()) return false

        if (isIpv4Literal(name)) return true
        if (name.indexOf('.') < 0) return true
        return LOCAL_SUFFIXES.any { name.endsWith(it, ignoreCase = true) }
    }

    fun isIpv4Literal(name: String): Boolean {
        val parts = name.split('.')
        if (parts.size != 4) return false
        for (p in parts) {
            if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return false
            if (p.toInt() > 255) return false
        }
        return true
    }

    private fun isIpv6Literal(text: String): Boolean {
        if (text.isEmpty() || text.indexOf(':') < 0) return false
        return text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' || it == '%' }
    }
}
