package io.github.joelpasapera.lanshare.core.importing

import io.github.joelpasapera.lanshare.core.content.RelativePath
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.Charset
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** Algo impide importar; el mensaje es para el usuario. */
class ImportException(message: String) : Exception(message)

class ZipLimits(
    val maxEntries: Int = 50_000,
    val maxTotalBytes: Long = 2L * 1024 * 1024 * 1024,
)

/** [root] es la carpeta que hace de raíz del proyecto, dentro de la de destino. */
class ExtractResult(val root: File, val files: Int, val bytes: Long)

/**
 * Extrae un .zip en una carpeta, tratando su contenido como hostil:
 *
 * - Cada nombre se convierte en una [RelativePath]: nada de rutas absolutas,
 *   "..", ni barras invertidas que fabriquen separadores (zip slip).
 * - Los límites de entradas y de bytes se aplican mientras se escribe, no al
 *   final: una bomba de descompresión se corta en cuanto se pasa.
 * - Nunca se crean enlaces simbólicos: todo se escribe como archivo normal.
 *
 * Se usa ZipFile y no ZipInputStream: lee el directorio central, admite
 * entradas STORED con descriptor de datos y ZIP64.
 */
object ZipExtractor {

    fun extract(
        zip: File,
        destination: File,
        limits: ZipLimits = ZipLimits(),
        cancelled: () -> Boolean = { false },
    ): ExtractResult {
        if (!destination.isDirectory && !destination.mkdirs()) throw IOException("No se pudo crear $destination")
        val base = destination.canonicalFile
        val basePrefix = base.path.trimEnd(File.separatorChar) + File.separator

        var files = 0
        var total = 0L
        var entries = 0
        open(zip).use { zf ->
            val iterator = zf.entries()
            val buffer = ByteArray(64 * 1024)
            while (iterator.hasMoreElements()) {
                if (cancelled()) throw ImportException("Importación cancelada")
                val entry = iterator.nextElement()
                if (++entries > limits.maxEntries) throw ImportException("El .zip tiene más de ${limits.maxEntries} entradas")

                val path = safePath(entry.name)
                    ?: throw ImportException("El .zip contiene una ruta no permitida: " + entry.name.take(120))
                if (path.isRoot || isJunk(path)) continue

                val target = File(base, path.toString())
                if (!target.canonicalPath.startsWith(basePrefix)) {
                    throw ImportException("El .zip intenta escribir fuera de su carpeta")
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                    continue
                }
                target.parentFile?.mkdirs()
                zf.getInputStream(entry).use { input ->
                    FileOutputStream(target).use { output ->
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            if (total > limits.maxTotalBytes) {
                                throw ImportException("El contenido descomprimido supera ${limits.maxTotalBytes / (1024 * 1024)} MB")
                            }
                            output.write(buffer, 0, n)
                        }
                    }
                }
                // La fecha del .zip NO se restaura: el ETag es tamaño + fecha, y dos
                // versiones con fechas fijas (builds reproducibles) y el mismo tamaño
                // compartirían validador: el navegador se quedaría con la vieja (304).
                files++
            }
        }
        if (files == 0) throw ImportException("El .zip no contiene archivos")
        return ExtractResult(effectiveRoot(base), files, total)
    }

    /**
     * Los .zip de GitHub (y muchos otros) meten todo en una carpeta
     * "repo-main/". Se baja mientras solo haya una carpeta, hasta tres niveles.
     */
    fun effectiveRoot(dir: File): File {
        var current = dir
        for (level in 0 until 3) {
            val children = current.listFiles()?.filter { !it.name.startsWith('.') } ?: return current
            if (children.size != 1 || !children[0].isDirectory) return current
            current = children[0]
        }
        return current
    }

    /** Nombre de entrada → ruta segura, o null si es peligrosa. Admite '\' de los .zip hechos en Windows. */
    fun safePath(name: String): RelativePath? {
        val normalized = name.replace('\\', '/')
        if (normalized.startsWith("/")) return null
        if (normalized.length >= 2 && normalized[1] == ':') return null // C:/...
        val parts = normalized.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) return null
        return RelativePath.of(parts)
    }

    private fun isJunk(path: RelativePath): Boolean =
        path.segments[0] == "__MACOSX" || path.name == ".DS_Store" || path.name == "Thumbs.db"

    private fun open(zip: File): ZipFile {
        // Los .zip de Windows antiguos guardan los nombres en la página de códigos OEM.
        val charsets = ArrayList<Charset>(3)
        charsets.add(Charsets.UTF_8)
        try {
            charsets.add(Charset.forName("IBM437"))
        } catch (e: Exception) {
            // Sin ese juego de caracteres, se pasa al siguiente.
        }
        charsets.add(Charsets.ISO_8859_1)

        var last: Exception? = null
        for (charset in charsets) {
            var zf: ZipFile? = null
            try {
                zf = ZipFile(zip, charset)
                // Recorrer los nombres fuerza la decodificación ahora y no a mitad de extraer.
                val e = zf.entries()
                while (e.hasMoreElements()) e.nextElement().name
                return zf
            } catch (e: ZipException) {
                zf?.close()
                last = e
            } catch (e: IllegalArgumentException) {
                zf?.close()
                last = e
            }
        }
        throw ImportException("No es un .zip válido" + (last?.message?.let { ": $it" } ?: ""))
    }
}
