package io.github.joelpasapera.lanshare.core.console

import io.github.joelpasapera.lanshare.core.json.Json
import io.github.joelpasapera.lanshare.core.json.JsonException

/** Un mensaje de consola tal como lo manda el navegador. */
class ConsoleEntry(
    /** error, warn, info, log o debug. */
    val level: String,
    val text: String,
    /** Ubicación ya acortada por el script: "/js/app.js:42:7". */
    val where: String,
    /** Pila del error, si la había, en el formato del navegador. */
    val stack: String,
    /** Veces seguidas que se repitió: el script agrupa los iguales. */
    count: Int,
) {
    val count: Int = if (count < 1) 1 else count
}

/**
 * Lote del script de la consola remota:
 * {"p": página, "d": omitidos, "e": [{"l", "m", "s", "k", "n"}, ...]}.
 *
 * Cualquier equipo de la red puede mandar lo que quiera a /__consola, así que
 * nada se da por supuesto: un campo de tipo inesperado cuenta como vacío y un
 * cuerpo que no sea un objeto JSON se rechaza entero.
 */
class ConsoleReport private constructor(
    val page: String,
    /** Mensajes que el navegador descartó por tener la cola llena. */
    val dropped: Int,
    val entries: List<ConsoleEntry>,
) {
    companion object {
        /** Mensajes por lote. El script nunca manda más de 100. */
        const val MAX_ENTRIES = 200

        fun parse(json: String): ConsoleReport? {
            if (json.isEmpty()) return null
            val root = try {
                Json.parse(json, maxDepth = 8)
            } catch (e: JsonException) {
                return null
            } as? Map<*, *> ?: return null

            val entries = ArrayList<ConsoleEntry>()
            val list = root["e"] as? List<*>
            if (list != null) {
                for (item in list) {
                    if (entries.size >= MAX_ENTRIES) break
                    val fields = item as? Map<*, *> ?: continue
                    entries.add(
                        ConsoleEntry(
                            text(fields, "l"), text(fields, "m"), text(fields, "s"),
                            text(fields, "k"), number(fields, "n"),
                        ),
                    )
                }
            }
            return ConsoleReport(text(root, "p"), number(root, "d"), entries)
        }

        private fun text(fields: Map<*, *>, key: String): String = fields[key] as? String ?: ""

        /** Entero no negativo; cualquier otra cosa cuenta como cero. */
        private fun number(fields: Map<*, *>, key: String): Int {
            val value = fields[key] as? Double ?: return 0
            if (value.isNaN() || value <= 0) return 0
            return if (value >= Int.MAX_VALUE) Int.MAX_VALUE else value.toInt()
        }
    }
}
