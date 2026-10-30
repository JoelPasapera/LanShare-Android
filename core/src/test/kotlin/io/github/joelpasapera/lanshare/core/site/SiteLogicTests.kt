package io.github.joelpasapera.lanshare.core.site

import io.github.joelpasapera.lanshare.core.content.DirEntry
import io.github.joelpasapera.lanshare.core.content.LocalFileTree
import io.github.joelpasapera.lanshare.core.content.Probe
import io.github.joelpasapera.lanshare.core.content.RelativePath
import io.github.joelpasapera.lanshare.core.http.HttpDate
import io.github.joelpasapera.lanshare.core.testkit.Check
import io.github.joelpasapera.lanshare.core.testkit.Suite
import io.github.joelpasapera.lanshare.core.testkit.TempTree
import java.nio.file.Files

class RangeAndCacheTests : Suite("Sitio: rangos y validadores") {

    private fun partial(header: String, size: Long): Pair<Long, Long> {
        val r = RangeParser.parse(header, size)
        Check.isTrue(r is RangeOutcome.Partial, "se esperaba rango parcial para '$header'")
        val range = (r as RangeOutcome.Partial).range
        return Pair(range.start, range.end)
    }

    init {
        test("rangos simples, abiertos y por sufijo") {
            Check.equal(Pair(0L, 99L), partial("bytes=0-99", 1000), "0-99")
            Check.equal(Pair(500L, 999L), partial("bytes=500-", 1000), "500-")
            Check.equal(Pair(900L, 999L), partial("bytes=-100", 1000), "-100")
            Check.equal(Pair(0L, 999L), partial("bytes=-5000", 1000), "sufijo mayor que el archivo")
            Check.equal(Pair(990L, 999L), partial("bytes=990-5000", 1000), "fin recortado")
            Check.equal(Pair(1L, 2L), partial(" bytes = 1 - 2 ", 1000), "espacios")
        }

        test("fuera del archivo es 416; sintaxis inválida o varios rangos, el archivo entero") {
            Check.isTrue(RangeParser.parse("bytes=1000-", 1000) is RangeOutcome.Unsatisfiable, "inicio en el tamaño")
            Check.isTrue(RangeParser.parse("bytes=-0", 1000) is RangeOutcome.Unsatisfiable, "sufijo cero")
            Check.isTrue(RangeParser.parse("bytes=0-", 0) is RangeOutcome.Unsatisfiable, "archivo vacío")
            for (h in listOf("bytes=-", "bytes=5-2", "bytes=a-b", "items=0-1", "bytes=0-1,5-9", "bytes 0-1", "bytes=99999999999999999999-")) {
                Check.isTrue(RangeParser.parse(h, 1000) is RangeOutcome.Whole, "ignorar '$h'")
            }
        }

        test("ETag distingue variantes y la comparación débil acepta W/") {
            val plain = EntityTag.build(1234, 5678, "")
            val injected = EntityTag.build(1234, 5678, "c")
            Check.isTrue(plain != injected, "variante en el validador")
            Check.isTrue(EntityTag.clientHasFreshCopy(plain, null, plain, 0), "mismo ETag")
            Check.isTrue(EntityTag.clientHasFreshCopy("\"x\", W/$plain", null, plain, 0), "lista con W/")
            Check.isFalse(EntityTag.clientHasFreshCopy(injected, null, plain, 0), "otra variante")
            Check.isTrue(EntityTag.clientHasFreshCopy("*", null, plain, 0), "comodín")
        }

        test("If-Modified-Since compara por segundos y cede ante If-None-Match") {
            val modified = 1_700_000_000_123L
            val since = HttpDate.format(modified)
            Check.isTrue(EntityTag.clientHasFreshCopy(null, since, "\"a\"", modified), "mismo segundo")
            Check.isFalse(EntityTag.clientHasFreshCopy(null, HttpDate.format(modified - 2000), "\"a\"", modified), "anterior")
            Check.isFalse(EntityTag.clientHasFreshCopy("\"b\"", since, "\"a\"", modified), "If-None-Match manda")
            Check.isFalse(EntityTag.clientHasFreshCopy(null, since, "\"a\"", 0), "fecha desconocida nunca es fresca")
        }

        test("tipos MIME críticos para aplicaciones web") {
            Check.equal("text/javascript; charset=utf-8", MimeTypes.forExtension("mjs"), "módulos")
            Check.equal("application/wasm", MimeTypes.forExtension("wasm"), "wasm")
            Check.equal("application/manifest+json; charset=utf-8", MimeTypes.forExtension("webmanifest"), "manifiesto")
            Check.equal(MimeTypes.FALLBACK, MimeTypes.forExtension("desconocida"), "repliegue")
            Check.isTrue(MimeTypes.isCompressible(MimeTypes.forExtension("svg")), "svg comprimible")
            Check.isFalse(MimeTypes.isCompressible(MimeTypes.forExtension("png")), "png no")
        }
    }
}

class HtmlInjectorTests : Suite("Sitio: inyección en HTML") {
    private val tag = "<script src=\"/__consola.js\"></script>"

    init {
        test("tras la apertura de <head>, con atributos y en cualquier caja") {
            Check.equal("<!DOCTYPE html><html><HEAD lang=x>${tag}<title>t</title>",
                HtmlInjector.injectHead("<!DOCTYPE html><html><HEAD lang=x><title>t</title>", tag), "head")
        }

        test("<header> no cuenta como <head>") {
            Check.equal("<html>${tag}<body><header>x</header>",
                HtmlInjector.injectHead("<html><body><header>x</header>", tag), "header")
        }

        test("sin head ni html, tras el DOCTYPE; sin nada, al principio") {
            Check.equal("<!doctype html>${tag}<p>x", HtmlInjector.injectHead("<!doctype html><p>x", tag), "doctype")
            Check.equal("${tag}<p>x", HtmlInjector.injectHead("<p>x", tag), "fragmento")
        }

        test("REGRESION: nunca antes del DOCTYPE (modo quirks)") {
            val out = HtmlInjector.injectHead("\n<!DOCTYPE html>\n<p>sin head", tag)
            Check.isTrue(out.indexOf("<!DOCTYPE") < out.indexOf("<script"), "DOCTYPE primero")
        }

        test("trabaja sobre bytes: el resto de la página no cambia aunque no sea UTF-8") {
            val latin1 = "<html><head><title>Café</title>".toByteArray(Charsets.ISO_8859_1)
            val out = HtmlInjector.injectHead(latin1, latin1.size, tag.toByteArray())
            val expected = "<html><head>$tag<title>Café</title>".toByteArray(Charsets.ISO_8859_1)
            Check.isTrue(out.contentEquals(expected), "bytes Latin-1 intactos")
        }

        test("REGRESION: sin <head>, un \"<head>\" dentro de un script no atrae la etiqueta") {
            val page = "<!DOCTYPE html><html><title>x</title><script>document.title=\"<head> tag\"</script>"
            Check.equal("<!DOCTYPE html><html>$tag<title>x</title><script>document.title=\"<head> tag\"</script>",
                HtmlInjector.injectHead(page, tag), "tras <html>, el script intacto")
            val minified = "<!doctype html><meta charset=utf-8><script>let h=\"<head>\"</script><p>x"
            Check.equal("<!doctype html>$tag<meta charset=utf-8><script>let h=\"<head>\"</script><p>x",
                HtmlInjector.injectHead(minified, tag), "html y head omitidos (html-minifier)")
        }

        test("salta comentarios, BOM y espacios; respeta comillas en los atributos") {
            Check.equal("<!-- <head> --><!DOCTYPE html>\n<html>\n<head>$tag<title>",
                HtmlInjector.injectHead("<!-- <head> --><!DOCTYPE html>\n<html>\n<head><title>", tag), "comentario delante")
            Check.equal("﻿<!DOCTYPE html><head>$tag<title>",
                HtmlInjector.injectHead("﻿<!DOCTYPE html><head><title>", tag), "BOM")
            Check.equal("<html data-x=\"a>b\" lang='c>d'><head class=x>$tag<title>",
                HtmlInjector.injectHead("<html data-x=\"a>b\" lang='c>d'><head class=x><title>", tag), "'>' entre comillas")
            Check.equal("<!---->$tag<p>", HtmlInjector.injectHead("<!----><p>", tag), "comentario vacío")
            Check.equal("<!DOCTYPE html><html><!--c-->$tag<body>",
                HtmlInjector.injectHead("<!DOCTYPE html><html><!--c--><body>", tag), "antes de la primera etiqueta que no es head")
        }

        test("sin cierre en el documento: nunca dentro de una etiqueta a medias") {
            Check.equal("$tag<head data-x=\"sin cerrar", HtmlInjector.injectHead("<head data-x=\"sin cerrar", tag), "head sin '>'")
            Check.equal("<!DOCTYPE html>\n$tag", HtmlInjector.injectHead("<!DOCTYPE html>\n", tag), "solo DOCTYPE")
        }

        test("la ventana sobre el archivo da el mismo punto que el documento en memoria") {
            val page = "<!-- " + "x".repeat(5000) + " -->\n<!DOCTYPE html>\n<html lang=\"es\">\n<head>\n<title>t</title>"
            val bytes = page.toByteArray()
            val expected = HtmlInjector.injectionPoint(HtmlInjector.arraySource(bytes), bytes.size.toLong())
            TempTree().use { t ->
                t.file("p.html", bytes)
                val probe = LocalFileTree(t.root).probe(RelativePath.parse("p.html")!!) as Probe.File
                probe.file.use { file ->
                    for (window in listOf(7, 64, 4096)) {
                        Check.equal(expected, HtmlInjector.injectionPoint(FileWindow(file, window), file.size), "ventana de $window bytes")
                    }
                }
            }
            Check.equal(page.indexOf("<head>") + 6L, expected, "tras <head>")
        }
    }
}

class StaticResolverTests : Suite("Sitio: resolución de rutas") {

    private fun tree(t: TempTree) = LocalFileTree(t.root, "demo")

    private fun resolve(r: StaticResolver, raw: String, slash: Boolean = raw.endsWith("/"), query: String = ""): Resolution {
        val path = RelativePath.parse(raw)!!
        val res = r.resolve(path, slash || path.isRoot, query)
        if (res is Resolution.ServeFile) res.file.close()
        return res
    }

    init {
        test("la raíz sirve index.html; una carpeta sin barra redirige con 301 conservando la consulta") {
            TempTree().use { t ->
                t.file("index.html", "<h1>hola</h1>")
                t.file("docs/index.html", "docs")
                val r = StaticResolver(tree(t), RelativePath.parse("index.html"), true, true)
                val root = resolve(r, "/")
                Check.isTrue(root is Resolution.ServeFile && root.path.toString() == "index.html", "raíz")
                val redirect = resolve(r, "docs", slash = false, query = "a=1")
                Check.isTrue(redirect is Resolution.Redirect, "redirección")
                redirect as Resolution.Redirect
                Check.equal("/docs/?a=1", redirect.location, "destino")
                Check.isTrue(redirect.permanent, "301")
                val docs = resolve(r, "docs/")
                Check.isTrue(docs is Resolution.ServeFile && docs.path.toString() == "docs/index.html", "índice de subcarpeta")
            }
        }

        test("archivo de inicio en subcarpeta: la raíz redirige con 302 para no romper las rutas relativas") {
            TempTree().use { t ->
                t.file("app/main.html", "x")
                val r = StaticResolver(tree(t), RelativePath.parse("app/main.html"), true, true)
                val res = resolve(r, "/")
                Check.isTrue(res is Resolution.Redirect && res.location == "/app/main.html" && !res.permanent, "302 al inicio")
            }
        }

        test("repliegue de página única solo para rutas sin extensión") {
            TempTree().use { t ->
                t.file("index.html", "spa")
                val r = StaticResolver(tree(t), RelativePath.parse("index.html"), true, true)
                Check.isTrue(resolve(r, "usuarios/42", slash = false) is Resolution.ServeFile, "ruta de la app")
                Check.isTrue(resolve(r, "falta.js", slash = false) is Resolution.NotFound, "un .js que falta da 404")
            }
        }

        test("carpeta sin índice: listado; servida como carpeta: listado siempre") {
            TempTree().use { t ->
                t.file("fotos/a.jpg", "x")
                t.file("index.html", "x")
                val normal = StaticResolver(tree(t), RelativePath.parse("index.html"), true, true)
                Check.isTrue(resolve(normal, "fotos/") is Resolution.Listing, "listado")
                val noListing = StaticResolver(tree(t), RelativePath.parse("index.html"), false, true)
                Check.isTrue(resolve(noListing, "fotos/") is Resolution.NotFound, "sin listado")
                val folder = StaticResolver(tree(t), null, false, true)
                Check.isTrue(resolve(folder, "/") is Resolution.Listing, "como carpeta lista aunque haya index.html")
            }
        }

        test("SEGURIDAD: lo que empieza por punto no existe hacia fuera (salvo .well-known)") {
            TempTree().use { t ->
                t.file(".git/config", "secreto")
                t.file(".env", "CLAVE=1")
                t.file(".well-known/assetlinks.json", "[]")
                val r = StaticResolver(tree(t), null, true, false)
                Check.isTrue(resolve(r, ".git/config", slash = false) is Resolution.NotFound, ".git")
                Check.isTrue(resolve(r, ".env", slash = false) is Resolution.NotFound, ".env")
                Check.isTrue(resolve(r, ".well-known/assetlinks.json", slash = false) is Resolution.ServeFile, ".well-known")
            }
        }

        test("SEGURIDAD: un enlace simbólico que sale de la raíz no se sirve") {
            TempTree().use { inside ->
                TempTree().use { outside ->
                    outside.file("secreto.txt", "no")
                    try {
                        Files.createSymbolicLink(inside.root.toPath().resolve("enlace"), outside.root.toPath())
                    } catch (e: Exception) {
                        return@use // sistema sin enlaces simbólicos: nada que probar
                    }
                    val r = StaticResolver(LocalFileTree(inside.root), null, true, false)
                    Check.isTrue(resolve(r, "enlace/secreto.txt", slash = false) is Resolution.NotFound, "fuera de la raíz")
                }
            }
        }
    }
}

class DirectoryPageTests : Suite("Sitio: listado de carpetas") {
    init {
        test("SEGURIDAD: escapa nombres en texto y en enlaces por separado") {
            val html = DirectoryPage.build("raíz", RelativePath.parse("sub")!!, listOf(
                DirEntry("<img src=x onerror=alert(1)>.html", false, 10, 1_700_000_000_000L),
                DirEntry("a\"b", true, -1, 0),
            ))
            Check.notContains(html, "<img src=x", "texto sin escapar")
            Check.contains(html, "&lt;img src=x onerror=alert(1)&gt;.html", "texto escapado")
            Check.contains(html, "href=\"%3Cimg%20src%3Dx%20onerror%3Dalert%281%29%3E.html\"", "href codificado")
            Check.contains(html, "href=\"a%22b/\"", "carpeta con comillas")
        }

        test("oculta lo que empieza por punto, ordena carpetas primero y resume") {
            val html = DirectoryPage.build("demo", RelativePath.ROOT, listOf(
                DirEntry("z.txt", false, 2048, 0), DirEntry(".git", true, -1, 0), DirEntry("a", true, -1, 0),
            ))
            Check.notContains(html, ".git", "oculto")
            Check.isTrue(html.indexOf(">a/<") < html.indexOf(">z.txt<"), "carpetas delante")
            Check.contains(html, "1 carpeta &middot; 1 archivo &middot; 2.0 KB", "resumen")
            Check.notContains(html, "Subir un nivel", "la raíz no sube")
        }
    }
}
