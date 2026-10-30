package io.github.joelpasapera.lanshare.core.json

/** Texto que no es JSON válido, con la posición del fallo. */
class JsonException(message: String, val position: Int) : Exception("$message (posición $position)")

/**
 * JSON (RFC 8259) escrito desde cero: analizador descendente recursivo con
 * tope de anidamiento y escritor con escapado correcto.
 *
 * Lo que llega por /__consola lo puede mandar cualquier equipo de la red, así
 * que el analizador es estricto (sin comas finales ni comentarios) y limita la
 * profundidad: un "[[[[[..." de un millón de niveles agotaría la pila.
 *
 * Tipos: objeto → Map<String, Any?> (orden de inserción), array → List<Any?>,
 * número → Double, y String, Boolean o null.
 */
object Json {

    fun parse(text: String, maxDepth: Int = 32): Any? {
        val parser = Parser(text, maxDepth)
        parser.skipWhitespace()
        val value = parser.readValue(0)
        parser.skipWhitespace()
        if (!parser.atEnd()) throw JsonException("Contenido tras el valor", parser.pos)
        return value
    }

    fun write(value: Any?): String {
        val sb = StringBuilder(256)
        writeValue(sb, value)
        return sb.toString()
    }

    private fun writeValue(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is String -> writeString(sb, value)
            is Boolean -> sb.append(if (value) "true" else "false")
            is Int, is Long, is Short, is Byte -> sb.append(value.toString())
            is Double -> writeDouble(sb, value)
            is Float -> writeDouble(sb, value.toDouble())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k.toString())
                    sb.append(':')
                    writeValue(sb, v)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (v in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeValue(sb, v)
                }
                sb.append(']')
            }
            else -> writeString(sb, value.toString())
        }
    }

    private fun writeDouble(sb: StringBuilder, d: Double) {
        if (d.isNaN() || d.isInfinite()) {
            sb.append("null")
        } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            sb.append(d.toLong())
        } else {
            sb.append(d.toString())
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' || c == ' ' || c == ' ' -> {
                    sb.append("\\u")
                    val hex = Integer.toHexString(c.code)
                    for (i in hex.length until 4) sb.append('0')
                    sb.append(hex)
                }
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(private val s: String, private val maxDepth: Int) {
        var pos = 0

        fun atEnd() = pos >= s.length

        fun skipWhitespace() {
            while (pos < s.length) {
                val c = s[pos]
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++ else break
            }
        }

        fun readValue(depth: Int): Any? {
            if (pos >= s.length) throw JsonException("Fin inesperado", pos)
            return when (val c = s[pos]) {
                '{' -> readObject(depth + 1)
                '[' -> readArray(depth + 1)
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') readNumber() else throw JsonException("Carácter inesperado '$c'", pos)
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, pos)) throw JsonException("Literal no válido", pos)
            pos += word.length
            return value
        }

        private fun readObject(depth: Int): Map<String, Any?> {
            if (depth > maxDepth) throw JsonException("Anidamiento excesivo", pos)
            pos++
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (pos < s.length && s[pos] == '}') {
                pos++
                return map
            }
            while (true) {
                skipWhitespace()
                if (pos >= s.length || s[pos] != '"') throw JsonException("Se esperaba una clave", pos)
                val key = readString()
                skipWhitespace()
                if (pos >= s.length || s[pos] != ':') throw JsonException("Se esperaba ':'", pos)
                pos++
                skipWhitespace()
                map[key] = readValue(depth)
                skipWhitespace()
                if (pos >= s.length) throw JsonException("Objeto sin cerrar", pos)
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return map
                    }
                    else -> throw JsonException("Se esperaba ',' o '}'", pos)
                }
            }
        }

        private fun readArray(depth: Int): List<Any?> {
            if (depth > maxDepth) throw JsonException("Anidamiento excesivo", pos)
            pos++
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (pos < s.length && s[pos] == ']') {
                pos++
                return list
            }
            while (true) {
                skipWhitespace()
                list.add(readValue(depth))
                skipWhitespace()
                if (pos >= s.length) throw JsonException("Array sin cerrar", pos)
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return list
                    }
                    else -> throw JsonException("Se esperaba ',' o ']'", pos)
                }
            }
        }

        private fun readString(): String {
            pos++
            val sb = StringBuilder()
            while (true) {
                if (pos >= s.length) throw JsonException("Cadena sin cerrar", pos)
                val c = s[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (pos >= s.length) throw JsonException("Escape incompleto", pos)
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000c')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> sb.append(readHex4())
                            else -> throw JsonException("Escape no válido '\\$e'", pos - 1)
                        }
                    }
                    c < ' ' -> throw JsonException("Carácter de control en una cadena", pos - 1)
                    else -> sb.append(c)
                }
            }
        }

        private fun readHex4(): Char {
            if (pos + 4 > s.length) throw JsonException("Escape \\u incompleto", pos)
            var v = 0
            for (i in 0 until 4) {
                val c = s[pos + i]
                val d = when (c) {
                    in '0'..'9' -> c - '0'
                    in 'a'..'f' -> c - 'a' + 10
                    in 'A'..'F' -> c - 'A' + 10
                    else -> throw JsonException("Dígito hexadecimal no válido", pos + i)
                }
                v = v * 16 + d
            }
            pos += 4
            // Los sustitutos sueltos se conservan tal cual: Java los admite en String.
            return v.toChar()
        }

        private fun readNumber(): Double {
            val start = pos
            if (s[pos] == '-') pos++
            if (pos >= s.length) throw JsonException("Número incompleto", pos)
            if (s[pos] == '0') {
                pos++
            } else if (s[pos] in '1'..'9') {
                while (pos < s.length && s[pos] in '0'..'9') pos++
            } else {
                throw JsonException("Número no válido", pos)
            }
            if (pos < s.length && s[pos] == '.') {
                pos++
                if (pos >= s.length || s[pos] !in '0'..'9') throw JsonException("Decimal no válido", pos)
                while (pos < s.length && s[pos] in '0'..'9') pos++
            }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                if (pos >= s.length || s[pos] !in '0'..'9') throw JsonException("Exponente no válido", pos)
                while (pos < s.length && s[pos] in '0'..'9') pos++
            }
            return s.substring(start, pos).toDouble()
        }
    }
}

/** Lectura cómoda y tolerante de valores ya analizados. */
object JsonValues {
    fun string(map: Map<*, *>, key: String): String = map[key] as? String ?: ""
    fun stringOrNull(map: Map<*, *>, key: String): String? = map[key] as? String
    fun int(map: Map<*, *>, key: String, default: Int = 0): Int {
        val d = map[key] as? Double ?: return default
        if (d.isNaN()) return default
        return if (d >= Int.MAX_VALUE) Int.MAX_VALUE else if (d <= Int.MIN_VALUE) Int.MIN_VALUE else d.toInt()
    }
    fun boolean(map: Map<*, *>, key: String, default: Boolean = false): Boolean = map[key] as? Boolean ?: default
}
