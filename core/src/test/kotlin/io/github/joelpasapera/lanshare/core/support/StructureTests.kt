package io.github.joelpasapera.lanshare.core.support

import io.github.joelpasapera.lanshare.core.cache.LruCache
import io.github.joelpasapera.lanshare.core.clients.ClientRegistry
import io.github.joelpasapera.lanshare.core.clients.UaParser
import io.github.joelpasapera.lanshare.core.content.RelativePath
import io.github.joelpasapera.lanshare.core.importing.ArchiveUrls
import io.github.joelpasapera.lanshare.core.json.Json
import io.github.joelpasapera.lanshare.core.json.JsonException
import io.github.joelpasapera.lanshare.core.log.RingBuffer
import io.github.joelpasapera.lanshare.core.net.LanAddress
import io.github.joelpasapera.lanshare.core.net.LanAddresses
import io.github.joelpasapera.lanshare.core.net.LinkKind
import io.github.joelpasapera.lanshare.core.testkit.Check
import io.github.joelpasapera.lanshare.core.testkit.Suite

class JsonTests : Suite("JSON") {
    init {
        test("analiza todos los tipos, escapes y pares sustitutos") {
            val v = Json.parse("""{"a":[1,-2.5e2,true,false,null],"s":"\u00e1\n\"\\\/\ud83d\ude00"}""") as Map<*, *>
            Check.equal(listOf(1.0, -250.0, true, false, null), v["a"], "array")
            Check.equal("á\n\"\\/😀", v["s"], "cadena")
        }

        test("ida y vuelta conserva el valor") {
            val original = linkedMapOf("nombre" to "Proyecto \"uno\"\t\u0001", "puerto" to 8080, "lan" to true, "x" to listOf(1.5, null))
            val back = Json.parse(Json.write(original)) as Map<*, *>
            Check.equal("Proyecto \"uno\"\t\u0001", back["nombre"], "texto con controles")
            Check.equal(8080.0, back["puerto"], "entero")
            Check.equal(listOf(1.5, null), back["x"], "lista")
        }

        for (bad in listOf("{", "[1,]", "{\"a\":1,}", "01", "1.", "\"\\x\"", "\"a\u0001\"", "tru", "{\"a\" 1}", "[1] 2", "-")) {
            test("SEGURIDAD: rechaza '$bad'") {
                Check.throws<JsonException>("entrada no válida") { Json.parse(bad) }
            }
        }

        test("SEGURIDAD: el anidamiento tiene tope y no agota la pila") {
            Check.throws<JsonException>("profundo") { Json.parse("[".repeat(100_000) + "]".repeat(100_000)) }
        }
    }
}

class CollectionTests : Suite("Estructuras propias") {
    init {
        test("LRU: expulsa por peso al menos usado y reordena al leer") {
            val cache = LruCache<String, ByteArray>(10) { it.size.toLong() }
            cache.put("a", ByteArray(4))
            cache.put("b", ByteArray(4))
            Check.notNull(cache.get("a"), "a sigue")
            cache.put("c", ByteArray(4))
            Check.equal(null, cache.get("b"), "b era el menos usado")
            Check.equal(listOf("a", "c").toSet(), cache.keysByRecency().toSet(), "contenido")
            Check.equal(8L, cache.weight, "peso")
            cache.put("enorme", ByteArray(11))
            Check.equal(null, cache.get("enorme"), "lo que no cabe no entra")
            cache.put("a", ByteArray(2))
            Check.equal(6L, cache.weight, "reemplazo descuenta el anterior")
            cache.removeIf { it == "c" }
            Check.equal(listOf("a"), cache.keysByRecency(), "removeIf")
        }

        test("cola circular: conserva las últimas N en orden") {
            val ring = RingBuffer<Int>(3)
            for (i in 1..7) ring.add(i)
            Check.equal(listOf(5, 6, 7), ring.toList(), "últimas tres")
            ring.clear()
            ring.add(9)
            Check.equal(listOf(9), ring.toList(), "tras vaciar")
        }

        test("registro de clientes: cuenta peticiones y respeta el máximo") {
            val registry = ClientRegistry(maxClients = 2)
            registry.record("10.0.0.1", false, 8080, "Mozilla/5.0 (Linux; Android 14; Pixel 8) Chrome/129.0", 100, 1)
            registry.record("10.0.0.1", false, 8081, null, 50, 2)
            registry.record("10.0.0.2", false, 8080, null, 0, 3)
            registry.record("10.0.0.3", false, 8080, null, 0, 4)
            val snap = registry.snapshot()
            Check.equal(2, snap.size, "tope")
            Check.equal(listOf("10.0.0.3", "10.0.0.2"), snap.map { it.ip }, "el más antiguo se fue")
        }
    }
}

class IdentityTests : Suite("Identificación y direcciones") {
    init {
        test("User-Agent: navegador y dispositivo") {
            val pixel = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.6668.70 Mobile Safari/537.36"
            Check.equal("Chrome 129", UaParser.browser(pixel), "Chrome")
            Check.equal("Android 14 · Pixel 8", UaParser.device(pixel), "modelo")
            val frozen = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
            Check.equal("Android (versión y modelo ocultos)", UaParser.device(frozen), "UA congelado")
            val samsung = "Mozilla/5.0 (Linux; Android 13; SM-A546E) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/25.0 Chrome/121.0 Mobile Safari/537.36"
            Check.equal("Samsung Internet 25", UaParser.browser(samsung), "Samsung antes que Chrome")
            Check.equal("curl 8", UaParser.browser("curl/8.5.0"), "curl")
        }

        test("direcciones: Wi-Fi primero, punto de acceso después, fuera datos móviles y enlace local") {
            val ranked = LanAddresses.rank(listOf(
                LanAddress("100.71.3.9", "rmnet_data0", LanAddresses.classify("rmnet_data0")),
                LanAddress("10.32.4.1", "swlan0", LanAddresses.classify("swlan0")),
                LanAddress("169.254.1.1", "wlan0", LinkKind.WIFI),
                LanAddress("192.168.1.34", "wlan0", LanAddresses.classify("wlan0")),
                LanAddress("10.8.0.2", "tun0", LanAddresses.classify("tun0")),
            ))
            Check.equal(listOf("192.168.1.34", "10.32.4.1", "10.8.0.2"), ranked.map { it.address }, "orden")
            Check.equal(LinkKind.HOTSPOT, LanAddresses.classify("ap0"), "ap0 es punto de acceso")
        }
    }
}

class ArchiveUrlTests : Suite("Importar: direcciones") {
    init {
        test("GitHub: repositorio, rama, .git, abreviatura y GitHub Pages") {
            Check.equal("https://github.com/JoelPasapera/LanShare/archive/HEAD.zip",
                ArchiveUrls.resolve("https://github.com/JoelPasapera/LanShare")?.url, "repositorio")
            Check.equal("https://github.com/JoelPasapera/LanShare/archive/refs/heads/dev/v2.zip",
                ArchiveUrls.resolve("https://github.com/JoelPasapera/LanShare/tree/dev/v2")?.url, "rama con barra")
            Check.equal("LanShare", ArchiveUrls.resolve("JoelPasapera/LanShare.git")?.suggestedName, "abreviatura")
            Check.equal("https://github.com/joelpasapera/pdf-editor/archive/HEAD.zip",
                ArchiveUrls.resolve("https://joelpasapera.github.io/pdf-editor/")?.url, "GitHub Pages")
        }

        test("otros .zip por HTTPS tal cual; HTTP plano y basura no") {
            Check.equal("https://ejemplo.org/sitio.zip", ArchiveUrls.resolve(" https://ejemplo.org/sitio.zip ")?.url, ".zip")
            Check.equal(null, ArchiveUrls.resolve("http://ejemplo.org/sitio.zip"), "HTTP plano")
            Check.equal(null, ArchiveUrls.resolve("https://ejemplo.org/pagina"), "no es un .zip")
            Check.equal(null, ArchiveUrls.resolve("javascript:alert(1)"), "esquema raro")
            Check.equal(null, ArchiveUrls.resolve("https://github.com/../x"), "nombres no válidos")
        }

        test("rutas relativas válidas") {
            Check.equal(null, RelativePath.parse("a/../b"), "..")
            Check.equal(listOf("a", "b"), RelativePath.parse("/a//b/")?.segments, "barras")
            Check.isTrue(RelativePath.parse("a/.git/x")!!.touchesHiddenEntry(), "oculto")
        }
    }
}
