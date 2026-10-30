package io.github.joelpasapera.lanshare.core.site

import io.github.joelpasapera.lanshare.core.content.DirEntry
import io.github.joelpasapera.lanshare.core.content.RelativePath
import io.github.joelpasapera.lanshare.core.http.Html
import io.github.joelpasapera.lanshare.core.http.UrlPath
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * HTML del listado de una carpeta sin página de inicio. Portado de LanShare
 * para Windows: misma estética, migas navegables, orden por columna y filtro
 * por nombre a partir de ocho entradas. Sin JavaScript sigue siendo una tabla.
 *
 * Responsabilidad única: producir el documento.
 */
object DirectoryPage {
    /** Una carpeta con decenas de miles de entradas congelaría el navegador del móvil. */
    private const val MAX_ENTRIES = 3000

    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

    fun build(rootName: String, path: RelativePath, entries: List<DirEntry>, zone: ZoneId = ZoneId.systemDefault()): String {
        val visible = entries.filter { !it.name.startsWith('.') }
            .sortedWith(compareBy<DirEntry>({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }))

        val html = StringBuilder(8192)
        val title = Html.escape(if (path.isRoot) rootName else "/" + path.toString())
        html.append("<!DOCTYPE html><html lang=\"es\"><head><meta charset=\"utf-8\">")
        html.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        html.append("<meta name=\"color-scheme\" content=\"dark\">")
        html.append("<title>").append(title).append("</title>")
        html.append(STYLE)
        html.append("</head><body><main>")
        appendBreadcrumb(html, rootName, path)

        html.append("<table id=\"lista\"><thead><tr><th data-k=\"n\">Nombre</th>")
        html.append("<th class=\"n\" data-k=\"s\">Tamaño</th>")
        html.append("<th class=\"n\" data-k=\"t\">Modificado</th></tr></thead><tbody>")

        if (!path.isRoot) {
            html.append("<tr><td colspan=\"3\"><a class=\"up\" href=\"../\">&#8593; Subir un nivel</a></td></tr>")
        }

        var folders = 0
        var files = 0
        var total = 0L
        var shown = 0
        var truncated = false
        for (entry in visible) {
            if (entry.isDirectory) folders++ else {
                files++
                if (entry.size > 0) total += entry.size
            }
            if (shown >= MAX_ENTRIES) {
                truncated = true
                continue
            }
            appendRow(html, entry, zone)
            shown++
        }
        html.append("</tbody></table>")

        if (truncated) {
            html.append("<p class=\"aviso\">Mostrando las primeras ").append(MAX_ENTRIES)
                .append(" entradas. La carpeta contiene más.</p>")
        }
        html.append("<p class=\"pie\">")
            .append(folders).append(if (folders == 1) " carpeta" else " carpetas")
            .append(" &middot; ")
            .append(files).append(if (files == 1) " archivo" else " archivos")
            .append(" &middot; ").append(formatSize(total))
            .append("</p>")
        html.append(SCRIPT)
        html.append("</main></body></html>")
        return html.toString()
    }

    private fun appendBreadcrumb(html: StringBuilder, rootName: String, path: RelativePath) {
        val root = Html.escape(rootName.ifEmpty { "/" })
        html.append("<h1 class=\"ruta\">")
        if (path.isRoot) {
            html.append("<b>").append(root).append("</b>")
        } else {
            html.append("<a href=\"/\">").append(root).append("</a>")
            val href = StringBuilder("/")
            val segments = path.segments
            for (i in segments.indices) {
                href.append(UrlPath.encodeSegment(segments[i])).append('/')
                val text = Html.escape(segments[i])
                html.append("<i>/</i>")
                if (i == segments.size - 1) html.append("<b>").append(text).append("</b>")
                else html.append("<a href=\"").append(href).append("\">").append(text).append("</a>")
            }
        }
        html.append("</h1>")
    }

    private fun appendRow(html: StringBuilder, entry: DirEntry, zone: ZoneId) {
        val size = if (entry.isDirectory) -1L else entry.size
        html.append("<tr data-s=\"").append(size).append("\" data-t=\"")
            .append(maxOf(0L, entry.lastModifiedMillis)).append("\">")

        // Escapado doble y distinto: el href como URL, el texto como HTML.
        var href = UrlPath.encodeSegment(entry.name)
        if (entry.isDirectory) href += "/"
        html.append("<td><a href=\"").append(href).append("\">")
        html.append(if (entry.isDirectory) "<span class=\"ic d\"></span>" else "<span class=\"ic f\"></span>")
        html.append(Html.escape(entry.name))
        if (entry.isDirectory) html.append('/')
        html.append("</a></td>")
        html.append("<td class=\"n\">").append(if (entry.isDirectory || entry.size < 0) "" else formatSize(entry.size)).append("</td>")
        html.append("<td class=\"n\">")
        if (entry.lastModifiedMillis > 0) {
            html.append(DATE.format(Instant.ofEpochMilli(entry.lastModifiedMillis).atZone(zone)))
        }
        html.append("</td></tr>")
    }

    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1_048_576 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
        bytes < 1_073_741_824 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1_048_576.0)
        else -> String.format(Locale.ROOT, "%.2f GB", bytes / 1_073_741_824.0)
    }

    private const val STYLE = """<style>
:root{--bg:#151a23;--panel:#1d242f;--line:#2b3543;--txt:#e7edf4;--dim:#8fa1b5;--ok:#28af4a}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--txt);font:14px/1.5 -apple-system,'Segoe UI',Roboto,sans-serif}
main{max-width:900px;margin:0 auto;padding:28px 18px}
h1{font-size:17px;font-weight:600;margin:0 0 18px;word-break:break-all;color:var(--dim)}
.ruta a{color:var(--dim);text-decoration:none}
.ruta a:hover{color:var(--ok)}
.ruta b{color:var(--txt);font-weight:600}
.ruta i{font-style:normal;margin:0 7px;opacity:.45}
.filtro{width:100%;margin:0 0 12px;padding:9px 12px;border:1px solid var(--line);border-radius:8px;background:var(--panel);color:var(--txt);font:inherit}
table{width:100%;border-collapse:collapse;background:var(--panel);border:1px solid var(--line);border-radius:8px;overflow:hidden}
th,td{padding:9px 14px;text-align:left;border-bottom:1px solid var(--line)}
th{font-size:12px;text-transform:uppercase;letter-spacing:.05em;color:var(--dim);font-weight:600}
th[data-k]{cursor:pointer;user-select:none;-webkit-user-select:none}
th[aria-sort]::after{content:'';display:inline-block;margin-left:6px;vertical-align:1px;border:4px solid transparent}
th[aria-sort=ascending]::after{border-bottom-color:currentColor;border-top-width:0}
th[aria-sort=descending]::after{border-top-color:currentColor;border-bottom-width:0}
tbody tr:last-child td{border-bottom:none}
tbody tr:hover{background:#232c39}
td.n,th.n{text-align:right;color:var(--dim);white-space:nowrap;font-variant-numeric:tabular-nums}
td a{color:var(--txt);text-decoration:none;display:flex;align-items:center;gap:9px;word-break:break-all}
td a:hover{color:var(--ok)}
td a.up{color:var(--dim)}
.ic{width:9px;height:9px;border-radius:50%;flex:0 0 9px}
.ic.d{background:var(--ok)}
.ic.f{background:var(--dim);opacity:.55}
.pie{margin-top:14px;color:var(--dim);font-size:12px;text-align:right}
.aviso{margin-top:14px;color:#e0b34d;font-size:12px}
@media(max-width:560px){th:nth-child(3),td:nth-child(3){display:none}}
</style>"""

    /** Orden por columna (carpetas siempre delante) y filtro por nombre desde ocho entradas. */
    private const val SCRIPT = """<script>(function(){
var table=document.getElementById('lista');
if(!table||!table.tHead||!table.tBodies.length)return;
var body=table.tBodies[0],rows=[],heads=table.tHead.rows[0].cells,key='',dir=1,i;
for(i=0;i<body.rows.length;i++)if(body.rows[i].hasAttribute('data-s'))rows.push(body.rows[i]);
if(rows.length<2)return;
function num(r,a){return Number(r.getAttribute(a))||0;}
function name(r){return String(r.cells[0].textContent||'').toLowerCase();}
function compare(a,b){
var da=a.getAttribute('data-s')==='-1',db=b.getAttribute('data-s')==='-1';
if(da!==db)return da?-1:1;
var c=key==='s'?num(a,'data-s')-num(b,'data-s'):key==='t'?num(a,'data-t')-num(b,'data-t'):
name(a).localeCompare(name(b),undefined,{numeric:true});
return c*dir;}
function sort(k){
dir=key===k?-dir:(k==='n'?1:-1);key=k;
rows.sort(compare);
for(var j=0;j<rows.length;j++)body.appendChild(rows[j]);
for(j=0;j<heads.length;j++){
if(heads[j].getAttribute('data-k')===k)heads[j].setAttribute('aria-sort',dir>0?'ascending':'descending');
else heads[j].removeAttribute('aria-sort');}}
function bind(cell){
var k=cell.getAttribute('data-k');if(!k)return;
cell.tabIndex=0;
cell.onclick=function(){sort(k);};
cell.onkeydown=function(e){if(e.key==='Enter'||e.key===' '){e.preventDefault();sort(k);}};}
for(i=0;i<heads.length;i++)bind(heads[i]);
if(rows.length<8)return;
var input=document.createElement('input');
input.type='search';input.className='filtro';input.placeholder='Filtrar por nombre';
input.setAttribute('aria-label','Filtrar por nombre');
table.parentNode.insertBefore(input,table);
input.oninput=function(){
var q=input.value.toLowerCase();
for(var j=0;j<rows.length;j++)rows[j].style.display=!q||name(rows[j]).indexOf(q)>=0?'':'none';};
})();</script>"""
}
