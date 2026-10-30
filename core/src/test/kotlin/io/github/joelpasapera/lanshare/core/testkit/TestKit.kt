package io.github.joelpasapera.lanshare.core.testkit

import java.io.File
import java.nio.file.Files

/**
 * Batería de pruebas sin frameworks externos, como en LanShare para Windows:
 * el ejecutor, las aserciones y el árbol temporal caben en este archivo, y
 * todo compila con el mismo kotlinc que el resto del proyecto.
 *
 * Convención de nombres: "SEGURIDAD:" para contención y entradas hostiles,
 * "REGRESION:" para fallos que ya ocurrieron una vez.
 */
abstract class Suite(val name: String) {
    internal val cases = ArrayList<Pair<String, () -> Unit>>()

    protected fun test(name: String, body: () -> Unit) {
        cases.add(Pair(name, body))
    }
}

class CheckFailure(message: String) : AssertionError(message)

object Check {
    fun isTrue(condition: Boolean, message: String) {
        if (!condition) throw CheckFailure(message)
    }

    fun isFalse(condition: Boolean, message: String) = isTrue(!condition, message)

    fun equal(expected: Any?, actual: Any?, message: String) {
        if (expected != actual) throw CheckFailure("$message\n      esperado: ${show(expected)}\n      obtenido: ${show(actual)}")
    }

    fun notNull(value: Any?, message: String) = isTrue(value != null, message)

    fun contains(haystack: String, needle: String, message: String) {
        if (!haystack.contains(needle)) throw CheckFailure("$message\n      falta: ${show(needle)}\n      en: ${show(haystack.take(400))}")
    }

    fun notContains(haystack: String, needle: String, message: String) {
        if (haystack.contains(needle)) throw CheckFailure("$message\n      sobra: ${show(needle)}")
    }

    inline fun <reified T : Throwable> throws(message: String, block: () -> Unit): T {
        try {
            block()
        } catch (t: Throwable) {
            if (t is T) return t
            throw CheckFailure("$message: se esperaba ${T::class.java.simpleName} y saltó ${t.javaClass.simpleName}: ${t.message}")
        }
        throw CheckFailure("$message: se esperaba ${T::class.java.simpleName} y no saltó nada")
    }

    private fun show(v: Any?): String = when (v) {
        null -> "null"
        is String -> "\"" + v.replace("\r", "\\r").replace("\n", "\\n") + "\""
        is ByteArray -> v.joinToString(",", "[", "]")
        else -> v.toString()
    }
}

/** Carpeta temporal con ayudas para escribir archivos; se borra al cerrar. */
class TempTree : AutoCloseable {
    val root: File = Files.createTempDirectory("lanshare-test").toFile()

    fun file(path: String, content: String): File = file(path, content.toByteArray(Charsets.UTF_8))

    fun file(path: String, content: ByteArray): File {
        val f = File(root, path)
        f.parentFile.mkdirs()
        f.writeBytes(content)
        return f
    }

    fun dir(path: String): File = File(root, path).also { it.mkdirs() }

    override fun close() {
        root.deleteRecursively()
    }
}

object TestRunner {
    fun run(suites: List<Suite>): Int {
        var passed = 0
        var failed = 0
        val started = System.nanoTime()
        for (suite in suites) {
            for ((name, body) in suite.cases) {
                try {
                    body()
                    passed++
                } catch (t: Throwable) {
                    failed++
                    println("FALLA  ${suite.name} › $name")
                    println("      " + (t.message ?: t.javaClass.name))
                    if (t !is CheckFailure) t.stackTrace.take(6).forEach { println("        at $it") }
                }
            }
            println("ok     ${suite.name} (${suite.cases.size})")
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        println()
        println("$passed pruebas correctas, $failed fallidas, en $ms ms")
        return if (failed == 0) 0 else 1
    }
}
