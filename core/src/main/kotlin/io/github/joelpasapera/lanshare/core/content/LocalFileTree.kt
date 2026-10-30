package io.github.joelpasapera.lanshare.core.content

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * [ContentTree] sobre una carpeta del sistema de archivos (java.io).
 *
 * En Android sirve los proyectos importados desde un .zip, que viven en el
 * almacenamiento privado de la aplicación; en la JVM, las pruebas.
 *
 * Contención: cada ruta se canoniza (enlaces simbólicos resueltos) y se exige
 * que siga dentro de la raíz. Entre comprobar y abrir queda una ventana en la
 * que un enlace podría cambiar; en el almacenamiento privado nadie más puede
 * crear enlaces, y el extractor de .zip nunca los crea.
 */
class LocalFileTree(root: File, displayName: String? = null) : ContentTree {

    private val root: File = root.canonicalFile
    private val rootPrefix: String = this.root.path.trimEnd(File.separatorChar) + File.separator

    override val displayName: String = displayName ?: this.root.name

    private fun fileFor(path: RelativePath): File? {
        var file = root
        for (segment in path.segments) file = File(file, segment)
        val canonical = try {
            file.canonicalFile
        } catch (e: IOException) {
            return null
        }
        return if (canonical == root || canonical.path.startsWith(rootPrefix)) canonical else null
    }

    override fun probe(path: RelativePath): Probe {
        val file = fileFor(path) ?: return Probe.Missing
        if (file.isDirectory) return Probe.Directory
        if (!file.isFile) return Probe.Missing

        val stream = try {
            FileInputStream(file)
        } catch (e: IOException) {
            return Probe.Missing
        }
        return Probe.File(LocalOpenedFile(stream, file))
    }

    override fun list(path: RelativePath): List<DirEntry>? {
        val dir = fileFor(path) ?: return null
        if (!dir.isDirectory) return null
        val children = dir.listFiles() ?: return null
        return children.map { child ->
            val isDir = child.isDirectory
            DirEntry(child.name, isDir, if (isDir) -1L else child.length(), child.lastModified())
        }
    }

    private class LocalOpenedFile(private val stream: FileInputStream, file: File) : OpenedFile {
        private val channel: FileChannel = stream.channel

        override val size: Long = channel.size()
        override val lastModifiedMillis: Long = file.lastModified()
        override val versionStamp: Long = nanosOf(file, lastModifiedMillis)

        override fun read(dst: ByteBuffer, position: Long): Int = channel.read(dst, position)

        override fun close() {
            stream.close()
        }

        companion object {
            /** Con java.nio.file hay nanosegundos; si no, los milisegundos bastan. */
            private fun nanosOf(file: File, fallbackMillis: Long): Long = try {
                Files.getLastModifiedTime(file.toPath()).to(TimeUnit.NANOSECONDS)
            } catch (e: Exception) {
                fallbackMillis * 1_000_000L
            }
        }
    }
}
