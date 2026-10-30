package io.github.joelpasapera.lanshare.core.http

/** Frases de estado y la página de error mínima que acompaña a los 4xx y 5xx. */
object HttpStatus {

    fun reason(status: Int): String = when (status) {
        101 -> "Switching Protocols"
        200 -> "OK"
        204 -> "No Content"
        206 -> "Partial Content"
        301 -> "Moved Permanently"
        302 -> "Found"
        304 -> "Not Modified"
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        408 -> "Request Timeout"
        411 -> "Length Required"
        413 -> "Content Too Large"
        414 -> "URI Too Long"
        416 -> "Range Not Satisfiable"
        421 -> "Misdirected Request"
        431 -> "Request Header Fields Too Large"
        500 -> "Internal Server Error"
        501 -> "Not Implemented"
        503 -> "Service Unavailable"
        505 -> "HTTP Version Not Supported"
        else -> "Status $status"
    }

    /** Texto en español para la página de error. */
    fun spanish(status: Int): String = when (status) {
        400 -> "Petición no válida"
        403 -> "Acceso prohibido"
        404 -> "No encontrado"
        405 -> "Método no permitido"
        408 -> "Tiempo de espera agotado"
        411 -> "Falta la longitud del cuerpo"
        413 -> "Cuerpo demasiado grande"
        414 -> "Dirección demasiado larga"
        416 -> "Rango no disponible"
        421 -> "Nombre de servidor no reconocido"
        431 -> "Cabeceras demasiado grandes"
        500 -> "Error interno del servidor"
        501 -> "No implementado"
        503 -> "Servidor ocupado"
        505 -> "Versión de HTTP no admitida"
        else -> reason(status)
    }

    /** Página HTML pequeña para errores; [detail] se escapa. */
    fun errorPage(status: Int, detail: String?): String {
        val title = "$status · ${spanish(status)}"
        val sb = StringBuilder(512)
        sb.append("<!DOCTYPE html><html lang=\"es\"><head><meta charset=\"utf-8\">")
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        sb.append("<title>").append(title).append("</title><style>")
        sb.append("body{margin:0;background:#151a23;color:#e7edf4;font:15px/1.5 system-ui,sans-serif;")
        sb.append("display:flex;min-height:100vh;align-items:center;justify-content:center}")
        sb.append("main{padding:24px;max-width:520px}h1{font-size:20px;margin:0 0 8px}")
        sb.append("p{color:#8fa1b5;margin:0;word-break:break-word}</style></head><body><main>")
        sb.append("<h1>").append(title).append("</h1>")
        if (!detail.isNullOrEmpty()) sb.append("<p>").append(Html.escape(detail)).append("</p>")
        sb.append("</main></body></html>")
        return sb.toString()
    }
}

/** Escapado HTML para texto y valores de atributo entre comillas dobles. */
object Html {
    fun escape(text: String): String {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '<' || c == '>' || c == '&' || c == '"' || c == '\'') break
            i++
        }
        if (i == text.length) return text
        val sb = StringBuilder(text.length + 16)
        sb.append(text, 0, i)
        while (i < text.length) {
            when (val c = text[i]) {
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '&' -> sb.append("&amp;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&#39;")
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }
}
