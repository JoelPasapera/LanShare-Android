package io.github.joelpasapera.lanshare.core.importing

import java.net.URI
import java.net.URISyntaxException

/** Dirección de descarga de un .zip y el nombre que se propone para el proyecto. */
class ArchiveSource(val url: String, val suggestedName: String)

/**
 * Convierte lo que el usuario pega en una dirección de .zip descargable:
 *
 * - https://github.com/dueño/repo → rama por defecto (archive/HEAD.zip)
 * - https://github.com/dueño/repo/tree/rama → esa rama
 * - https://github.com/dueño/repo.git, dueño/repo → igual que el primero
 * - https://dueño.github.io/repo/ (GitHub Pages) → el repositorio de esa página
 * - cualquier otra https://… que acabe en .zip → tal cual
 *
 * Solo HTTPS: un .zip por HTTP plano podría llegar manipulado por la red.
 */
object ArchiveUrls {

    fun resolve(input: String): ArchiveSource? {
        val text = input.trim()
        if (text.isEmpty()) return null

        if (!text.contains("://")) {
            val parts = text.trim('/').split('/')
            if (parts.size == 2 && parts.all(::isGithubName)) return github(parts[0], parts[1].removeSuffix(".git"), null)
            return null
        }

        val uri = try {
            URI(text)
        } catch (e: URISyntaxException) {
            return null
        }
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        val host = (uri.host ?: return null).lowercase()
        val segments = (uri.path ?: "").split('/').filter { it.isNotEmpty() }

        if (host == "github.com" || host == "www.github.com") {
            if (segments.size < 2) return null
            val owner = segments[0]
            val repo = segments[1].removeSuffix(".git")
            if (!isGithubName(owner) || !isGithubName(repo)) return null
            if (segments.size >= 4 && segments[2] == "tree") {
                return github(owner, repo, segments.subList(3, segments.size).joinToString("/"))
            }
            if (segments.size >= 3 && segments[2] == "archive") return ArchiveSource(text, repo)
            return github(owner, repo, null)
        }

        if (host.endsWith(".github.io")) {
            val owner = host.removeSuffix(".github.io")
            if (!isGithubName(owner)) return null
            val repo = if (segments.isEmpty()) "$owner.github.io" else segments[0]
            if (!isGithubName(repo)) return null
            return github(owner, repo, null)
        }

        if (segments.isNotEmpty() && segments.last().endsWith(".zip", ignoreCase = true)) {
            return ArchiveSource(text, segments.last().removeSuffix(".zip").removeSuffix(".ZIP"))
        }
        return null
    }

    private fun github(owner: String, repo: String, branch: String?): ArchiveSource {
        val ref = if (branch.isNullOrEmpty()) "HEAD" else "refs/heads/" + branch.split('/').joinToString("/") { encode(it) }
        return ArchiveSource("https://github.com/$owner/$repo/archive/$ref.zip", repo)
    }

    private fun isGithubName(s: String): Boolean =
        s.isNotEmpty() && s.length <= 100 && s.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' || it == '.' } &&
            s != "." && s != ".."

    private fun encode(segment: String): String {
        val sb = StringBuilder()
        for (b in segment.toByteArray(Charsets.UTF_8)) {
            val c = (b.toInt() and 0xFF).toChar()
            if (c.isLetterOrDigit() && c.code < 128 || c == '-' || c == '_' || c == '.') sb.append(c)
            else sb.append('%').append(String.format("%02X", b.toInt() and 0xFF))
        }
        return sb.toString()
    }
}
