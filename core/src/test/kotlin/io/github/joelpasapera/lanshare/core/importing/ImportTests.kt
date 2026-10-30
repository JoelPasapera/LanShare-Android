package io.github.joelpasapera.lanshare.core.importing

import io.github.joelpasapera.lanshare.core.content.LocalFileTree
import io.github.joelpasapera.lanshare.core.content.RelativePath
import io.github.joelpasapera.lanshare.core.discovery.ProjectInspector
import io.github.joelpasapera.lanshare.core.testkit.Check
import io.github.joelpasapera.lanshare.core.testkit.Suite
import io.github.joelpasapera.lanshare.core.testkit.TempTree
import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipExtractorTests : Suite("Importar: extracción de .zip") {

    private fun zip(file: File, entries: List<Pair<String, ByteArray?>>, stored: Boolean = false) {
        ZipOutputStream(FileOutputStream(file)).use { out ->
            for ((name, data) in entries) {
                val e = ZipEntry(name)
                if (stored && data != null) {
                    e.method = ZipEntry.STORED
                    e.size = data.size.toLong()
                    e.crc = CRC32().also { it.update(data) }.value
                }
                out.putNextEntry(e)
                if (data != null) out.write(data)
                out.closeEntry()
            }
        }
    }

    init {
        test("baja la carpeta única de GitHub y conserva la estructura") {
            TempTree().use { t ->
                val z = File(t.root, "repo.zip")
                zip(z, listOf(
                    "repo-main/" to null,
                    "repo-main/index.html" to "<h1>x</h1>".toByteArray(),
                    "repo-main/js/app.js" to "console.log(1)".toByteArray(),
                    "__MACOSX/repo-main/._index.html" to ByteArray(3),
                ))
                val result = ZipExtractor.extract(z, File(t.root, "out"))
                Check.equal("repo-main", result.root.name, "raíz efectiva")
                Check.equal(2, result.files, "archivos (sin la basura de macOS)")
                Check.equal("console.log(1)", File(result.root, "js/app.js").readText(), "contenido")
            }
        }

        test("REGRESION: no se restaura la fecha de las entradas (cada importación, ETag nuevo)") {
            TempTree().use { t ->
                val z = File(t.root, "fijo.zip")
                ZipOutputStream(FileOutputStream(z)).use { out ->
                    // Fecha fija, como la de un build reproducible: 1 de enero de 2001.
                    out.putNextEntry(ZipEntry("app.js").apply { time = 978_307_200_000L })
                    out.write("v1".toByteArray())
                    out.closeEntry()
                }
                val before = System.currentTimeMillis() - 60_000
                val result = ZipExtractor.extract(z, File(t.root, "out"))
                Check.isTrue(File(result.root, "app.js").lastModified() > before, "fecha de la extracción, no la del .zip")
            }
        }

        test("admite entradas STORED y barras invertidas de Windows") {
            TempTree().use { t ->
                val z = File(t.root, "w.zip")
                zip(z, listOf("sitio\\index.html" to "hola".toByteArray()), stored = true)
                val result = ZipExtractor.extract(z, File(t.root, "out"))
                Check.equal("hola", File(result.root, "index.html").readText(), "contenido")
            }
        }

        for (evil in listOf("../fuera.txt", "/etc/passwd", "a/../../x", "C:/Windows/x", "a\\..\\..\\x")) {
            test("SEGURIDAD: zip slip con '$evil' se rechaza sin escribir fuera") {
                TempTree().use { t ->
                    val z = File(t.root, "malo.zip")
                    zip(z, listOf("bien.txt" to "x".toByteArray(), evil to "malo".toByteArray()))
                    Check.throws<ImportException>("ruta peligrosa") { ZipExtractor.extract(z, File(t.root, "out/dentro")) }
                    Check.isFalse(File(t.root, "out/fuera.txt").exists() || File(t.root, "fuera.txt").exists(), "nada fuera")
                }
            }
        }

        test("SEGURIDAD: el límite de bytes corta una bomba mientras se escribe") {
            TempTree().use { t ->
                val z = File(t.root, "bomba.zip")
                zip(z, listOf("ceros.bin" to ByteArray(5_000_000)))
                Check.throws<ImportException>("bomba") {
                    ZipExtractor.extract(z, File(t.root, "out"), ZipLimits(maxTotalBytes = 1_000_000))
                }
            }
        }

        test("un archivo que no es .zip da un mensaje claro") {
            TempTree().use { t ->
                val f = t.file("no.zip", "esto no es un zip")
                val e = Check.throws<ImportException>("no zip") { ZipExtractor.extract(f, File(t.root, "out")) }
                Check.contains(e.message ?: "", "No es un .zip válido", "mensaje")
            }
        }
    }
}

class ProjectInspectorTests : Suite("Descubrir proyectos") {
    init {
        test("páginas de la raíz con index.html primero") {
            TempTree().use { t ->
                t.file("zeta.html", "")
                t.file("index.html", "")
                t.file("about.htm", "")
                val i = ProjectInspector.inspect(LocalFileTree(t.root))
                Check.equal(listOf("index.html", "about.htm", "zeta.html"), i.pages, "orden")
                Check.equal("index.html", i.suggestedStart, "propuesta")
            }
        }

        test("una carpeta de proyectos lista los que tienen index.html") {
            TempTree().use { t ->
                t.file("guillotina/index.html", "")
                t.file("alambique/index.html", "")
                t.file("notas/leeme.txt", "")
                t.file(".git/index.html", "")
                val i = ProjectInspector.inspect(LocalFileTree(t.root))
                Check.isFalse(i.isProject, "no es un proyecto")
                Check.equal(listOf("alambique", "guillotina"), i.subprojects, "subproyectos")
            }
        }

        test("icono del manifiesto: el mayor raster de propósito any y su color de fondo") {
            TempTree().use { t ->
                t.file("index.html", "<html><head><link rel=\"manifest\" href=\"app/m.json\"></head>")
                t.file("app/m.json", """{"background_color":"#123456","icons":[
                    {"src":"i/192.png","sizes":"192x192"},
                    {"src":"i/512.png","sizes":"512x512"},
                    {"src":"i/mask.png","sizes":"1024x1024","purpose":"maskable"},
                    {"src":"i/v.svg","sizes":"any","type":"image/svg+xml"}]}""")
                for (n in listOf("192", "512", "mask")) t.file("app/i/$n.png", "x")
                val icon = ProjectInspector.findIcon(LocalFileTree(t.root), RelativePath.parse("index.html"))
                Check.equal("app/i/512.png", icon?.path?.toString(), "icono")
                Check.equal(0xFF123456.toInt(), icon?.backgroundColor, "color")
            }
        }

        test("sin manifiesto: link rel=icon de la página y luego nombres habituales") {
            TempTree().use { t ->
                t.file("index.html", "<link href='/img/logo.png' rel='icon shortcut'>")
                t.file("img/logo.png", "x")
                Check.equal("img/logo.png", ProjectInspector.findIcon(LocalFileTree(t.root), RelativePath.parse("index.html"))?.path?.toString(), "link")
            }
            TempTree().use { t ->
                t.file("index.html", "<p>")
                t.file("favicon.png", "x")
                Check.equal("favicon.png", ProjectInspector.findIcon(LocalFileTree(t.root), RelativePath.parse("index.html"))?.path?.toString(), "habitual")
            }
        }

        test("SEGURIDAD: referencias externas o que suben por encima de la raíz no se resuelven") {
            Check.equal(null, ProjectInspector.resolveRef(RelativePath.ROOT, "https://cdn/x.png"), "externa")
            Check.equal(null, ProjectInspector.resolveRef(RelativePath.ROOT, "../x.png"), "sube")
            Check.equal("a/x.png", ProjectInspector.resolveRef(RelativePath.parse("a/b")!!, "../x.png?v=2").toString(), "relativa")
        }
    }
}
