package io.github.joelpasapera.lanshare.core.net

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException

/** Red a la que el teléfono está conectado directamente (dirección de su interfaz y prefijo). */
class Subnet(address: InetAddress, val prefixLength: Int) {
    private val network: ByteArray = address.address

    fun contains(candidate: ByteArray): Boolean {
        if (candidate.size != network.size) return false
        var bits = prefixLength
        var i = 0
        while (bits >= 8) {
            if (candidate[i] != network[i]) return false
            bits -= 8
            i++
        }
        if (bits == 0) return true
        val mask = (0xFF shl (8 - bits)) and 0xFF
        return (candidate[i].toInt() and mask) == (network[i].toInt() and mask)
    }
}

/** Lo que el teléfono sabe de sus redes en un momento dado. */
class NetworkView(val ownAddresses: Set<InetAddress>, val subnets: List<Subnet>)

/**
 * Quién puede conectarse a un puerto abierto "en la red local".
 *
 * Ese puerto escucha en todas las interfaces (en Android, en "::", también
 * por IPv6). Sin filtro, un equipo podría llegar por la dirección IPv6 global
 * de los datos móviles, por la de un router sin cortafuegos IPv6 o desde el
 * otro lado de una VPN, y la interfaz no lo diría nunca: solo muestra
 * direcciones de la red local. Por eso se admite únicamente:
 *
 * - el propio dispositivo (bucle local o cualquiera de sus direcciones);
 * - redes privadas IPv4 (RFC 1918) y de enlace local (169.254/16);
 * - IPv6 de enlace local (fe80::/10) y local única (fc00::/7);
 * - cualquier equipo de una red a la que el teléfono esté conectado
 *   directamente por Wi-Fi, Ethernet, punto de acceso o USB, aunque use
 *   direcciones públicas (hay universidades que las dan en su Wi-Fi). Las
 *   redes de los datos móviles y de las VPN no cuentan.
 *
 * Fuera quedan, a propósito, 100.64/10 (CGNAT: la red del operador) y
 * cualquier otra dirección pública.
 */
class PeerPolicy(
    private val view: () -> NetworkView = ::currentView,
    private val refreshNanos: Long = 5_000_000_000L,
) {
    @Volatile
    private var snapshot = NetworkView(emptySet(), emptyList())

    @Volatile
    private var loadedAt = 0L

    @Volatile
    private var loaded = false

    fun allows(address: InetAddress): Boolean {
        if (isLocalNetwork(address)) return true
        if (matches(snapshot, address)) return true
        // Las redes cambian (otra Wi-Fi, el punto de acceso): se vuelven a mirar,
        // como mucho una vez cada [refreshNanos], aunque llegue una avalancha.
        val now = System.nanoTime()
        if (loaded && now - loadedAt < refreshNanos) return false
        synchronized(this) {
            if (!loaded || now - loadedAt >= refreshNanos) {
                snapshot = view()
                loadedAt = now
                loaded = true
            }
        }
        return matches(snapshot, address)
    }

    private fun matches(view: NetworkView, address: InetAddress): Boolean {
        if (address in view.ownAddresses) return true
        val bytes = comparable(address)
        return view.subnets.any { it.contains(bytes) }
    }

    companion object {
        fun isLocalNetwork(address: InetAddress): Boolean {
            if (address.isLoopbackAddress) return true
            val b = address.address
            if (address is Inet4Address || b.size == 4) return isLocalIpv4(b, 0)
            if (address !is Inet6Address || b.size != 16) return false
            if (isIpv4Mapped(b)) return isLocalIpv4(b, 12)
            val first = b[0].toInt() and 0xFF
            val second = b[1].toInt() and 0xFF
            if (first == 0xFE && (second and 0xC0) == 0x80) return true // fe80::/10, enlace local
            if (first == 0xFE && (second and 0xC0) == 0xC0) return true // fec0::/10, sitio local (obsoleto)
            return (first and 0xFE) == 0xFC // fc00::/7, direcciones locales únicas
        }

        private fun isLocalIpv4(b: ByteArray, at: Int): Boolean {
            val o0 = b[at].toInt() and 0xFF
            val o1 = b[at + 1].toInt() and 0xFF
            return o0 == 10 ||
                o0 == 127 ||
                (o0 == 172 && o1 in 16..31) ||
                (o0 == 192 && o1 == 168) ||
                (o0 == 169 && o1 == 254)
        }

        /** ::ffff:a.b.c.d, como llega un cliente IPv4 a un socket de doble pila. */
        private fun isIpv4Mapped(b: ByteArray): Boolean {
            for (i in 0 until 10) if (b[i].toInt() != 0) return false
            return b[10].toInt() == -1 && b[11].toInt() == -1
        }

        /** Bytes comparables con las subredes: un IPv4 mapeado en IPv6 se compara como IPv4. */
        private fun comparable(address: InetAddress): ByteArray {
            val b = address.address
            return if (b.size == 16 && isIpv4Mapped(b)) b.copyOfRange(12, 16) else b
        }

        /** Direcciones propias y subredes de las interfaces activas que no son datos móviles ni VPN. */
        fun currentView(): NetworkView {
            val own = HashSet<InetAddress>()
            val subnets = ArrayList<Subnet>()
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces() ?: return NetworkView(own, subnets)
                for (nif in interfaces) {
                    val (up, loopback) = try {
                        Pair(nif.isUp, nif.isLoopback)
                    } catch (e: SocketException) {
                        Pair(false, false)
                    }
                    if (!up) continue
                    val kind = LanAddresses.classify(nif.name)
                    val direct = !loopback && kind != LinkKind.CELLULAR && kind != LinkKind.VPN
                    for (ia in nif.interfaceAddresses) {
                        val address = ia.address ?: continue
                        own.add(address)
                        val prefix = ia.networkPrefixLength.toInt()
                        // Prefijos absurdamente cortos (una /0 abriría a todo Internet) no cuentan.
                        val sane = if (address is Inet4Address) prefix in 8..32 else prefix in 32..128
                        if (direct && sane) subnets.add(Subnet(address, prefix))
                    }
                }
            } catch (e: SocketException) {
                // Sin interfaces legibles, solo cuenta lo que ya es local por definición.
            }
            return NetworkView(own, subnets)
        }
    }
}
