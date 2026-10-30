package io.github.joelpasapera.lanshare.core.console

import io.github.joelpasapera.lanshare.core.log.LogBus
import io.github.joelpasapera.lanshare.core.log.LogLevel
import io.github.joelpasapera.lanshare.core.net.HostGuard
import io.github.joelpasapera.lanshare.core.testkit.Check
import io.github.joelpasapera.lanshare.core.testkit.Suite

class ConsoleTests : Suite("Consola remota") {

    private fun report(json: String): ConsoleReport = ConsoleReport.parse(json) ?: throw AssertionError("lote ilegible: $json")

    init {
        test("interpreta un lote y tolera campos de tipo inesperado") {
            val r = report("""{"p":"/index.html","d":2,"e":[{"l":"error","m":"x","s":"/a.js:1:2","k":"","n":3},{"l":5,"m":null},7]}""")
            Check.equal("/index.html", r.page, "página")
            Check.equal(2, r.dropped, "omitidos")
            Check.equal(2, r.entries.size, "el 7 no es un mensaje")
            Check.equal(3, r.entries[0].count, "repeticiones")
            Check.equal("", r.entries[1].level, "nivel no textual")
            Check.equal(1, r.entries[1].count, "cuenta mínima")
        }

        test("SEGURIDAD: rechaza lo que no es un objeto o anida demasiado") {
            Check.equal(null, ConsoleReport.parse("[1,2]"), "array")
            Check.equal(null, ConsoleReport.parse("{\"e\":" + "[".repeat(50) + "]".repeat(50) + "}"), "anidamiento")
            Check.equal(null, ConsoleReport.parse("{\"e\":[],}"), "coma final")
        }

        test("una línea por mensaje con la pila acortada") {
            val entry = ConsoleEntry("error", "TypeError: x no es una función", "",
                "TypeError: x\n    at f (http://127.0.0.1:8080/js/app.js:42:7)\n    at http://127.0.0.1:8080/js/main.js:10:3", 5)
            val line = ConsoleLine.format(entry, "127.0.0.1", "/index.html", "http://127.0.0.1:8080")
            Check.equal("127.0.0.1 /index.html  TypeError: x no es una función  @ /js/app.js:42:7 <- /js/main.js:10:3  (x5)", line, "línea")
        }

        test("marcos de Gecko y JavaScriptCore") {
            Check.equal(listOf("/a.js:1:2"), ConsoleLine.stackLocations("f@http://h:1/a.js:1:2", "http://h:1", 3), "Gecko")
            Check.equal(null, ConsoleLine.locate("sin marco"), "texto sin ubicación")
        }

        test("SEGURIDAD: neutraliza saltos de línea y caracteres de dirección") {
            val flat = ConsoleLine.flatten("uno\r\ndos‮exe.jpg\u0000", 100)
            Check.equal("uno | dos exe.jpg ", flat, "aplanado")
            val long = ConsoleLine.flatten("a".repeat(50) + "😀", 51)
            Check.isFalse(Character.isHighSurrogate(long[long.length - 4]), "no parte un par sustituto")
        }

        test("la cubeta concede ráfaga y luego ritmo, y un reloj que retrocede no regala fichas") {
            val bucket = TokenBucket(10, 5.0, 0)
            Check.equal(10, bucket.take(20, 0), "ráfaga")
            Check.equal(0, bucket.take(1, 0), "vacía")
            Check.equal(5, bucket.take(20, 1000), "un segundo")
            Check.equal(0, bucket.take(5, 500), "reloj hacia atrás")
        }

        test("el relevo limita por cliente y avisa de lo omitido") {
            val log = LogBus()
            val relay = ConsoleRelay(log)
            val entries = (1..100).joinToString(",") { """{"l":"log","m":"m$it","s":"","k":"","n":1}""" }
            val batch = report("""{"p":"/","d":0,"e":[$entries]}""")
            val written = relay.relay(batch, "192.168.1.9", 8080, "http://192.168.1.9:8080", 0)
            Check.equal(ConsoleRelay.BURST, written, "solo la ráfaga")
            Check.isFalse(log.snapshot().any { it.text.contains("omitidos") }, "el aviso también espera a tener ficha")
            relay.relay(report("""{"p":"/","e":[]}"""), "192.168.1.9", 8080, "", 10_000)
            Check.isTrue(log.snapshot().any { it.level == LogLevel.JS_WARN && it.text.contains("60 mensajes de consola omitidos") },
                "aviso de omitidos")
            Check.equal(ConsoleRelay.BURST, relay.relay(batch, "192.168.1.10", 8080, "", 0), "otro cliente, otra cubeta")
        }

        test("SEGURIDAD: solo el mismo origen y nombres que no se pueden suplantar con DNS") {
            Check.isTrue(HostGuard.isSameOrigin("http://127.0.0.1:8080", "127.0.0.1:8080", "same-origin"), "localhost")
            Check.isTrue(HostGuard.isSameOrigin(null, "192.168.1.5:8080", null), "sin Origin (curl)")
            Check.isFalse(HostGuard.isSameOrigin("https://malo.com", "192.168.1.5:8080", "cross-site"), "otra web")
            Check.isFalse(HostGuard.isSameOrigin("http://malo.com:8080", "malo.com:8080", null), "DNS rebinding")
            Check.isFalse(HostGuard.isSameOrigin("null", "127.0.0.1:8080", null), "origen null")
            Check.isTrue(HostGuard.isLocalHostName("movil.local:8080"), ".local")
            Check.isTrue(HostGuard.isLocalHostName("[::1]:8080"), "IPv6")
            Check.isFalse(HostGuard.isLocalHostName("ejemplo.com"), "dominio público")
            Check.isFalse(HostGuard.isLocalHostName("999.1.1.1"), "octeto imposible no es IP y tiene puntos")
        }
    }
}
