package io.github.joelpasapera.lanshare.core.net

import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException

/** Tipo de red de una interfaz, deducido de su nombre (convenciones de Android y Linux). */
enum class LinkKind(val label: String, val score: Int) {
    WIFI("Wi-Fi", 100),
    HOTSPOT("Punto de acceso", 90),
    ETHERNET("Ethernet", 85),
    USB_TETHER("USB", 70),
    WIFI_DIRECT("Wi-Fi Direct", 50),
    OTHER("Otra red", 40),
    VPN("VPN", 20),
    CELLULAR("Datos móviles", 0),
}

/** Dirección IPv4 por la que otros equipos pueden llegar a este. */
class LanAddress(val address: String, val interfaceName: String, val kind: LinkKind) {
    override fun toString(): String = "$address ($interfaceName, ${kind.label})"
}

/**
 * Direcciones de red local ordenadas por probabilidad de que otro equipo
 * llegue por ellas. Mismo problema que resolvió LanShare para Windows con
 * WSL y las máquinas virtuales: la primera interfaz que se enumera no es
 * necesariamente la buena. En el móvil los candidatos falsos son los datos
 * móviles (CGNAT, inalcanzables desde la red local) y las VPN.
 */
object LanAddresses {

    fun discover(): List<LanAddress> {
        val found = ArrayList<LanAddress>()
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
        } catch (e: SocketException) {
            emptyList()
        }
        for (nif in interfaces) {
            val up = try {
                nif.isUp && !nif.isLoopback
            } catch (e: SocketException) {
                false
            }
            if (!up) continue
            for (address in nif.inetAddresses.toList()) {
                if (address !is Inet4Address) continue
                val text = address.hostAddress ?: continue
                found.add(LanAddress(text, nif.name, classify(nif.name)))
            }
        }
        return rank(found)
    }

    /** Ordena y filtra: fuera enlace local, bucle y datos móviles. */
    fun rank(candidates: List<LanAddress>): List<LanAddress> =
        candidates
            .filter { usable(it.address) && it.kind != LinkKind.CELLULAR }
            .distinctBy { it.address }
            .sortedWith(compareByDescending<LanAddress> { score(it) }.thenBy { it.interfaceName })

    private fun score(a: LanAddress): Int = a.kind.score + if (isPrivate(a.address)) 10 else 0

    fun classify(name: String): LinkKind {
        val n = name.lowercase()
        return when {
            n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("pdp") || n.startsWith("v4-rmnet") ||
                n.startsWith("clat") || n.startsWith("wwan") || n.startsWith("seth") -> LinkKind.CELLULAR
            n.startsWith("tun") || n.startsWith("ppp") || n.startsWith("ipsec") || n.startsWith("wg") ||
                n.startsWith("tap") -> LinkKind.VPN
            n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("softap") || n.startsWith("wifi_ap") ||
                n == "wlan1" -> LinkKind.HOTSPOT
            n.startsWith("p2p") -> LinkKind.WIFI_DIRECT
            n.startsWith("wlan") || n.startsWith("wl") -> LinkKind.WIFI
            n.startsWith("rndis") || n.startsWith("usb") || n.startsWith("ncm") -> LinkKind.USB_TETHER
            n.startsWith("eth") || n.startsWith("en") -> LinkKind.ETHERNET
            else -> LinkKind.OTHER
        }
    }

    private fun octets(address: String): IntArray? {
        val parts = address.split('.')
        if (parts.size != 4) return null
        val result = IntArray(4)
        for (i in 0 until 4) {
            val v = parts[i].toIntOrNull() ?: return null
            if (v !in 0..255) return null
            result[i] = v
        }
        return result
    }

    private fun usable(address: String): Boolean {
        val o = octets(address) ?: return false
        if (o[0] == 127 || o[0] == 0) return false
        if (o[0] == 169 && o[1] == 254) return false
        return true
    }

    fun isPrivate(address: String): Boolean {
        val o = octets(address) ?: return false
        return o[0] == 10 || (o[0] == 172 && o[1] in 16..31) || (o[0] == 192 && o[1] == 168)
    }
}
