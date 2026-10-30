package io.github.joelpasapera.lanshare.core.clients

/**
 * Única responsabilidad: interpretar la cadena User-Agent. Portado de LanShare para Windows.
 *
 * La cadena la escribe el cliente, así que se trata como hostil: se recorta a
 * [MAX_LENGTH] y las expresiones usan cuantificadores posesivos, que no
 * retroceden. Con la versión anterior, "Android;" seguido de unos miles de
 * espacios costaba segundos de CPU por petición (ReDoS).
 */
object UaParser {
    /** Ningún navegador real envía más; lo que sobre no aporta nada. */
    const val MAX_LENGTH = 512

    /** Literal congelado que Chrome 110+ envía en Android: ni versión ni modelo son reales. */
    private const val FROZEN_ANDROID = "Android 10; K"

    private val ANDROID_VERSION = Regex("""Android\s++([\d.]++)""")
    private val ANDROID_MODEL = Regex("""Android[^;)]*+;\s*+(?:[a-z]{2}(?:-[a-zA-Z]{2})?;\s*+)?([^;)]++)""")
    private val IPHONE = Regex("""CPU iPhone OS (\d++)[_.](\d++)""")
    private val IPAD = Regex("""CPU OS (\d++)[_.](\d++)""")
    private val MAC = Regex("""Mac OS X (\d++)[_.](\d++)""")

    /** La cadena tal como se analiza y se guarda: recortada a [MAX_LENGTH]. */
    fun clip(ua: String): String = if (ua.length <= MAX_LENGTH) ua else ua.substring(0, MAX_LENGTH)

    fun browser(raw: String?): String {
        if (raw.isNullOrEmpty()) return "Desconocido"
        val ua = clip(raw)
        // El orden importa: Edge, Opera y Samsung incluyen "Chrome/" en su UA.
        return when {
            "Edg/" in ua -> join("Edge", versionAfter(ua, "Edg/"))
            "EdgA/" in ua -> join("Edge Android", versionAfter(ua, "EdgA/"))
            "EdgiOS/" in ua -> join("Edge iOS", versionAfter(ua, "EdgiOS/"))
            "OPR/" in ua -> join("Opera", versionAfter(ua, "OPR/"))
            "SamsungBrowser/" in ua -> join("Samsung Internet", versionAfter(ua, "SamsungBrowser/"))
            "YaBrowser/" in ua -> join("Yandex", versionAfter(ua, "YaBrowser/"))
            "Vivaldi/" in ua -> join("Vivaldi", versionAfter(ua, "Vivaldi/"))
            "FxiOS/" in ua -> join("Firefox iOS", versionAfter(ua, "FxiOS/"))
            "Firefox/" in ua -> join("Firefox", versionAfter(ua, "Firefox/"))
            "CriOS/" in ua -> join("Chrome iOS", versionAfter(ua, "CriOS/"))
            "; wv)" in ua -> join("WebView", versionAfter(ua, "Chrome/"))
            "Chrome/" in ua -> join("Chrome", versionAfter(ua, "Chrome/"))
            "Version/" in ua && "Safari/" in ua -> join("Safari", versionAfter(ua, "Version/"))
            "curl/" in ua -> join("curl", versionAfter(ua, "curl/"))
            "Wget" in ua -> "wget"
            "PostmanRuntime" in ua -> "Postman"
            "python-requests" in ua -> "python-requests"
            "Dart/" in ua -> "Dart / Flutter"
            "okhttp" in ua -> "OkHttp (app nativa)"
            else -> "Otro"
        }
    }

    fun device(raw: String?): String {
        if (raw.isNullOrEmpty()) return "Desconocido"
        val ua = clip(raw)
        if ("Android TV" in ua) return "Android TV"
        if ("Android" in ua) {
            if (FROZEN_ANDROID in ua) return "Android (versión y modelo ocultos)"
            val version = ANDROID_VERSION.find(ua)?.groupValues?.get(1) ?: ""
            // El grupo llega hasta ';' o ')'; " Build/..." se quita aparte, sin retroceso.
            val model = ANDROID_MODEL.find(ua)?.groupValues?.get(1)?.substringBefore(" Build/")?.trim() ?: ""
            if (model == "K" || model == "Android" || model.isEmpty()) return join("Android", version) + " (modelo oculto)"
            return join("Android", version) + " · " + model
        }
        if ("Windows Phone" in ua) return "Windows Phone"
        if ("iPhone" in ua) {
            val m = IPHONE.find(ua)
            return if (m != null) "iPhone · iOS ${m.groupValues[1]}.${m.groupValues[2]}" else "iPhone"
        }
        if ("iPad" in ua) {
            val m = IPAD.find(ua)
            return if (m != null) "iPad · iPadOS ${m.groupValues[1]}.${m.groupValues[2]}" else "iPad"
        }
        if ("iPod" in ua) return "iPod touch"
        if ("CrOS" in ua) return "ChromeOS"
        if ("Windows NT 10.0" in ua) return "Windows 10/11"
        if ("Windows NT 6.3" in ua) return "Windows 8.1"
        if ("Windows NT 6.2" in ua) return "Windows 8"
        if ("Windows NT 6.1" in ua) return "Windows 7"
        if ("Windows NT" in ua) return "Windows (antiguo)"
        if ("Mac OS X" in ua) {
            // Safari congela macOS en 10.15.7 desde Big Sur.
            val m = MAC.find(ua)
            return if (m != null) "macOS ${m.groupValues[1]}.${m.groupValues[2]}" else "macOS"
        }
        if ("SMART-TV" in ua || "Tizen" in ua || "Web0S" in ua) return "Smart TV"
        if ("PlayStation" in ua) return "PlayStation"
        if ("Nintendo" in ua) return "Nintendo"
        if ("Linux" in ua || "X11" in ua) return "Linux"
        return "Desconocido"
    }

    /** Versión mayor que sigue a un token, sin expresiones regulares. */
    private fun versionAfter(ua: String, token: String): String {
        var i = ua.indexOf(token)
        if (i < 0) return ""
        i += token.length
        var j = i
        while (j < ua.length && (ua[j].isDigit() || ua[j] == '.')) j++
        val v = ua.substring(i, j)
        val dot = v.indexOf('.')
        return if (dot > 0) v.substring(0, dot) else v
    }

    private fun join(name: String, version: String): String = if (version.isNotEmpty()) "$name $version" else name
}
