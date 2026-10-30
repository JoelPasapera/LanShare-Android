package io.github.joelpasapera.lanshare.core.content

import java.io.Closeable
import java.nio.ByteBuffer

/**
 * Árbol de archivos de solo lectura que sirve un sitio.
 *
 * El servidor no sabe de dónde salen los bytes: una carpeta del disco, una
 * carpeta elegida con el selector de Android (SAF) o lo que venga después.
 * Cada implementación decide cómo resolver una [RelativePath] de la forma más
 * barata que permita su almacenamiento.
 *
 * Las implementaciones deben ser seguras para hilos: varias peticiones las
 * consultan a la vez.
 */
interface ContentTree : Closeable {

    /** Nombre de la carpeta raíz, para títulos y listados. */
    val displayName: String

    /**
     * Abre un archivo regular, o informa de que la ruta es una carpeta o de
     * que no existe. Abrir y averiguar el tipo en una sola operación ahorra
     * una consulta: en SAF cada consulta es una llamada entre procesos.
     */
    fun probe(path: RelativePath): Probe

    /** Contenido de una carpeta, o null si no existe o no es una carpeta. */
    fun list(path: RelativePath): List<DirEntry>?

    /** Olvida lo que se haya cacheado: el contenido pudo cambiar por fuera. */
    fun invalidate() {}

    override fun close() {}
}

/** Resultado de [ContentTree.probe]. Si es [File], quien llama debe cerrarlo. */
sealed class Probe {
    class File(val file: OpenedFile) : Probe()
    object Directory : Probe()
    object Missing : Probe()
}

class DirEntry(
    val name: String,
    val isDirectory: Boolean,
    /** Tamaño en bytes; -1 en carpetas o si se desconoce. */
    val size: Long,
    /** Milisegundos Unix; 0 si se desconoce. */
    val lastModifiedMillis: Long,
)

/**
 * No se pudo leer un archivo ya abierto: permiso de la carpeta retirado,
 * tarjeta de memoria extraída, archivo recortado mientras se enviaba...
 *
 * No es un IOException a propósito: para el servidor, IOException significa
 * "el cliente se fue". Esto es un fallo de este lado y merece un 500 (si la
 * respuesta aún no empezó) y una línea de error en el registro.
 */
class ContentReadException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Archivo abierto para lectura posicional. Varias lecturas pueden convivir. */
interface OpenedFile : Closeable {
    val size: Long

    /** Milisegundos Unix de la última modificación; 0 si se desconoce. */
    val lastModifiedMillis: Long

    /**
     * Marca de versión tan fina como permita el almacenamiento (nanosegundos
     * si los hay). Junto con el tamaño forma el ETag: dos escrituras dentro
     * del mismo segundo no deben compartir validador.
     */
    val versionStamp: Long

    /** Lee en [dst] desde [position]. Devuelve los bytes leídos o -1 al final. */
    fun read(dst: ByteBuffer, position: Long): Int
}
