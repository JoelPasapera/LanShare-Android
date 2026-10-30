package io.github.joelpasapera.lanshare.core.http

/**
 * Fechas HTTP en formato IMF-fixdate (RFC 9110 §5.6.7):
 * "Sun, 06 Nov 1994 08:49:37 GMT".
 *
 * Escrito a mano con aritmética de calendario civil (algoritmo de Howard
 * Hinnant): sin SimpleDateFormat, que no es seguro entre hilos, y sin
 * RFC_1123_DATE_TIME de java.time, que no rellena el día con cero y produce
 * "6 Nov", formato que HTTP no permite.
 */
object HttpDate {
    private val DAYS = arrayOf("Thu", "Fri", "Sat", "Sun", "Mon", "Tue", "Wed")
    private val MONTHS = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    fun format(epochMillis: Long): String {
        val seconds = Math.floorDiv(epochMillis, 1000L)
        val days = Math.floorDiv(seconds, 86_400L)
        val secOfDay = Math.floorMod(seconds, 86_400L).toInt()

        // Civil desde días (era de 400 años).
        val z = days + 719_468
        val era = Math.floorDiv(z, 146_097L)
        val doe = (z - era * 146_097).toInt()
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = doy - (153 * mp + 2) / 5 + 1
        val month = if (mp < 10) mp + 3 else mp - 9
        val year = yoe + era * 400 + if (month <= 2) 1 else 0

        val sb = StringBuilder(29)
        sb.append(DAYS[Math.floorMod(days, 7L).toInt()]).append(", ")
        two(sb, day).append(' ').append(MONTHS[month - 1]).append(' ').append(year).append(' ')
        two(sb, secOfDay / 3600).append(':')
        two(sb, secOfDay / 60 % 60).append(':')
        two(sb, secOfDay % 60).append(" GMT")
        return sb.toString()
    }

    /** Milisegundos Unix, o null si no es una IMF-fixdate válida. */
    fun parse(text: String?): Long? {
        if (text == null) return null
        val s = text.trim()
        if (s.length != 29 || s[3] != ',' || s[4] != ' ' || !s.endsWith(" GMT")) return null
        val day = num(s, 5, 2) ?: return null
        val month = MONTHS.indexOf(s.substring(8, 11)) + 1
        if (month == 0 || s[7] != ' ' || s[11] != ' ' || s[16] != ' ') return null
        val year = num(s, 12, 4) ?: return null
        if (s[19] != ':' || s[22] != ':') return null
        val hour = num(s, 17, 2) ?: return null
        val minute = num(s, 20, 2) ?: return null
        val second = num(s, 23, 2) ?: return null
        if (day !in 1..31 || hour > 23 || minute > 59 || second > 60) return null

        val y = if (month <= 2) year - 1 else year
        val era = Math.floorDiv(y.toLong(), 400L)
        val yoe = (y - era * 400).toInt()
        val mp = if (month > 2) month - 3 else month + 9
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        val days = era * 146_097 + doe - 719_468
        return ((days * 86_400L) + hour * 3600L + minute * 60L + second) * 1000L
    }

    private fun two(sb: StringBuilder, v: Int): StringBuilder {
        if (v < 10) sb.append('0')
        return sb.append(v)
    }

    private fun num(s: String, from: Int, len: Int): Int? {
        var v = 0
        for (i in from until from + len) {
            val c = s[i]
            if (c !in '0'..'9') return null
            v = v * 10 + (c - '0')
        }
        return v
    }
}
