package com.mydrop.android.share

import android.util.Base64
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLConnection
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Executors

/**
 * A small WebDAV server so a Mac can mount the phone in Finder, plus a tiny endpoint that hands out
 * mirrored notifications. Files are read-only unless editing is switched on. Every request needs the pairing code.
 */
class DavServer(
    private val root: File,
    private val password: () -> String,
    private val filesAllowed: () -> Boolean,
    private val writable: () -> Boolean,
) {
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val rootPath = root.canonicalPath
    var port = 0
        private set

    fun start(): Int {
        val ss = ServerSocket()
        ss.reuseAddress = true
        try { ss.bind(InetSocketAddress(PORT)) } catch (_: Exception) { ss.bind(InetSocketAddress(0)) }
        server = ss
        port = ss.localPort
        pool.execute { while (!ss.isClosed) { try { val s = ss.accept(); pool.execute { handle(s) } } catch (_: Exception) { break } } }
        return port
    }

    fun stop() { try { server?.close() } catch (_: Exception) {}; server = null }

    // ---------- HTTP ----------

    private fun handle(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = 60_000
                s.tcpNoDelay = true
                val input = BufferedInputStream(s.getInputStream(), 256 * 1024)
                val out = BufferedOutputStream(s.getOutputStream(), 64 * 1024)
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val headers = HashMap<String, String>()
                while (true) {
                    val l = readLine(input) ?: return
                    if (l.isEmpty()) break
                    val i = l.indexOf(':')
                    if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
                }
                val method = parts[0].uppercase()
                val body = body(input, headers)

                if (method == "OPTIONS") {
                    val dav = if (writable()) "1, 2" else "1"
                    val allow = if (writable()) "OPTIONS, GET, HEAD, PROPFIND, PROPPATCH, PUT, DELETE, MKCOL, MOVE, COPY, LOCK, UNLOCK" else "OPTIONS, GET, HEAD, PROPFIND"
                    body.drain()
                    return respond(out, 200, "OK", mapOf("DAV" to dav, "Allow" to allow, "MS-Author-Via" to "DAV"))
                }
                if (!authorized(headers["authorization"])) { body.drain(); return respond(out, 401, "Unauthorized", mapOf("WWW-Authenticate" to "Basic realm=\"MyDrop\"")) }

                val rawPath = parts[1].substringBefore('?')
                val query = parts[1].substringAfter('?', "")
                val rel = URLDecoder.decode(rawPath.replace("+", "%2B"), "UTF-8")

                if (rel == "/_mydrop/notifications") { body.drain(); return notifications(out, query) }
                if (!filesAllowed()) { body.drain(); return respond(out, 403, "Forbidden", body = "File sharing is off".toByteArray()) }

                val leaf = rel.trimEnd('/').substringAfterLast('/')
                if (leaf.startsWith("._") || leaf == ".DS_Store" || leaf == ".Trashes" || leaf == ".metadata_never_index") {
                    if (method == "PUT" && writable()) { body.drain(); return respond(out, 201, "Created") }   // swallow Finder's housekeeping files
                    body.drain(); return respond(out, 404, "Not Found")
                }

                when (method) {
                    "PROPFIND" -> { body.drain(); val f = resolve(rel) ?: return respond(out, 404, "Not Found"); propfind(out, f, headers["depth"] ?: "1") }
                    "GET", "HEAD" -> { body.drain(); val f = resolve(rel) ?: return respond(out, 404, "Not Found"); get(out, f, headers["range"], method == "HEAD") }
                    "PUT", "DELETE", "MKCOL", "MOVE", "COPY", "LOCK", "UNLOCK", "PROPPATCH" -> {
                        if (!writable()) { body.drain(); return respond(out, 403, "Forbidden", body = "Read-only".toByteArray()) }
                        modify(out, method, rel, headers, body)
                    }
                    else -> { body.drain(); respond(out, 405, "Method Not Allowed") }
                }
                out.flush()
            } catch (_: Exception) {}
        }
    }

    /** The request body, read only when a method needs it. */
    private class Body(private val input: InputStream, private val length: Long, private val chunked: Boolean) {
        private var used = false
        fun drain() { copyTo(OutputStream.nullOutputStream()) }
        fun copyTo(out: OutputStream) {
            if (used) return
            used = true
            val buf = ByteArray(256 * 1024)
            if (chunked) {
                while (true) {
                    val size = readHex(input)
                    if (size <= 0) { while (true) { val l = readRaw(input); if (l == null || l.isEmpty()) break }; break }
                    var left = size
                    while (left > 0) { val n = input.read(buf, 0, minOf(left, buf.size.toLong()).toInt()); if (n < 0) return; out.write(buf, 0, n); left -= n }
                    readRaw(input)
                }
            } else {
                var left = length
                while (left > 0) { val n = input.read(buf, 0, minOf(left, buf.size.toLong()).toInt()); if (n < 0) break; out.write(buf, 0, n); left -= n }
            }
        }
        private fun readRaw(i: InputStream): String? { val sb = StringBuilder(); while (true) { val c = i.read(); if (c < 0) return null; if (c == '\n'.code) return sb.toString().trimEnd('\r'); sb.append(c.toChar()) } }
        private fun readHex(i: InputStream): Long = (readRaw(i) ?: "0").substringBefore(';').trim().toLongOrNull(16) ?: 0L
    }

    private fun body(input: InputStream, headers: Map<String, String>) =
        Body(input, headers["content-length"]?.toLongOrNull() ?: 0L, headers["transfer-encoding"]?.contains("chunked", true) == true)

    private fun authorized(header: String?): Boolean {
        if (header == null || !header.startsWith("Basic ")) return false
        val decoded = try { String(Base64.decode(header.substring(6), Base64.DEFAULT), Charsets.UTF_8) } catch (_: Exception) { return false }
        return decoded.substringAfter(':', "") == password()
    }

    private fun inside(f: File) = f.path == rootPath || f.path.startsWith("$rootPath/")
    private fun resolve(rel: String): File? = File(root, rel.trimStart('/')).canonicalFile.takeIf { inside(it) && it.exists() }
    private fun resolveNew(rel: String): File? = File(root, rel.trimStart('/').trimEnd('/')).canonicalFile.takeIf { inside(it) && it.path != rootPath }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 16_384) return null
        }
    }

    private fun respond(out: OutputStream, code: Int, text: String, headers: Map<String, String> = emptyMap(), body: ByteArray? = null, type: String? = null) {
        val sb = StringBuilder("HTTP/1.1 $code $text\r\n")
        headers.forEach { (k, v) -> sb.append("$k: $v\r\n") }
        if (type != null) sb.append("Content-Type: $type\r\n")
        sb.append("Content-Length: ${body?.size ?: 0}\r\nConnection: close\r\n\r\n")
        out.write(sb.toString().toByteArray())
        if (body != null) out.write(body)
        out.flush()
    }

    // ---------- notifications for the Mac ----------

    private fun notifications(out: OutputStream, query: String) {
        val after = query.split("&").firstOrNull { it.startsWith("after=") }?.substringAfter('=')?.toLongOrNull() ?: -1L
        // First contact (after < 0) only returns the current position, so old notifications aren't replayed.
        val list = if (after < 0) emptyList() else MirrorBuffer.after(after)
        val sb = StringBuilder("{\"last\":${MirrorBuffer.lastId()},\"items\":[")
        list.forEachIndexed { i, n ->
            if (i > 0) sb.append(',')
            sb.append("{\"id\":${n.id},\"app\":${json(n.app)},\"title\":${json(n.title)},\"text\":${json(n.text)}}")
        }
        sb.append("]}")
        respond(out, 200, "OK", body = sb.toString().toByteArray(), type = "application/json; charset=utf-8")
    }

    private fun json(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "").replace("\t", " ") + "\""

    // ---------- WebDAV: reading ----------

    private fun httpDate(ms: Long): String =
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }.format(Date(ms))

    private fun hrefFor(f: File): String {
        val rel = f.canonicalPath.removePrefix(rootPath).ifEmpty { "/" }
        val enc = rel.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        return if (f.isDirectory && !enc.endsWith("/")) "$enc/" else enc
    }

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun entry(f: File): String {
        val name = if (f.canonicalPath == rootPath) "Phone" else f.name
        val props = StringBuilder("<D:displayname>${xml(name)}</D:displayname>")
        if (f.isDirectory) props.append("<D:resourcetype><D:collection/></D:resourcetype>")
        else props.append("<D:resourcetype/><D:getcontentlength>${f.length()}</D:getcontentlength><D:getcontenttype>${contentType(f)}</D:getcontenttype>")
        props.append("<D:getlastmodified>${httpDate(f.lastModified())}</D:getlastmodified>")
        return "<D:response><D:href>${xml(hrefFor(f))}</D:href><D:propstat><D:prop>$props</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>"
    }

    private fun propfind(out: OutputStream, f: File, depth: String) {
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?><D:multistatus xmlns:D=\"DAV:\">")
        sb.append(entry(f))
        if (f.isDirectory && depth != "0") {
            f.listFiles()?.filter { !it.name.startsWith("._") && it.name != ".DS_Store" }?.sortedBy { it.name.lowercase() }?.forEach { sb.append(entry(it)) }
        }
        sb.append("</D:multistatus>")
        respond(out, 207, "Multi-Status", body = sb.toString().toByteArray(), type = "application/xml; charset=utf-8")
    }

    private fun contentType(f: File) = URLConnection.guessContentTypeFromName(f.name) ?: "application/octet-stream"

    private fun get(out: OutputStream, f: File, range: String?, headOnly: Boolean) {
        if (f.isDirectory) return respond(out, 200, "OK", body = "MyDrop".toByteArray(), type = "text/plain")
        val total = f.length()
        var start = 0L; var end = total - 1; var partial = false
        if (range != null && range.startsWith("bytes=")) {
            val spec = range.removePrefix("bytes=").substringBefore(',')
            val a = spec.substringBefore('-'); val b = spec.substringAfter('-', "")
            if (a.isEmpty()) { start = (total - (b.toLongOrNull() ?: 0)).coerceAtLeast(0) }
            else { start = a.toLongOrNull() ?: 0; if (b.isNotEmpty()) end = minOf(end, b.toLongOrNull() ?: end) }
            if (start > end || start >= total) return respond(out, 416, "Range Not Satisfiable", mapOf("Content-Range" to "bytes */$total"))
            partial = true
        }
        val len = end - start + 1
        val head = StringBuilder(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
        head.append("Content-Type: ${contentType(f)}\r\nContent-Length: $len\r\nAccept-Ranges: bytes\r\nLast-Modified: ${httpDate(f.lastModified())}\r\n")
        if (partial) head.append("Content-Range: bytes $start-$end/$total\r\n")
        head.append("Connection: close\r\n\r\n")
        out.write(head.toString().toByteArray())
        if (headOnly) return
        f.inputStream().use { ins ->
            ins.skip(start)
            val buf = ByteArray(256 * 1024)
            var left = len
            while (left > 0) { val n = ins.read(buf, 0, minOf(left, buf.size.toLong()).toInt()); if (n < 0) break; out.write(buf, 0, n); left -= n }
        }
    }

    // ---------- WebDAV: changing (only when editing is on) ----------

    private fun destination(headers: Map<String, String>): File? {
        val dest = headers["destination"] ?: return null
        val path = dest.substringAfter("://").substringAfter('/', "")
        return resolveNew(URLDecoder.decode("/" + path.substringBefore('?'), "UTF-8"))
    }

    private fun modify(out: OutputStream, method: String, rel: String, headers: Map<String, String>, body: Body) {
        when (method) {
            "PUT" -> {
                val f = resolveNew(rel) ?: run { body.drain(); return respond(out, 403, "Forbidden") }
                if (f.isDirectory) { body.drain(); return respond(out, 405, "Method Not Allowed") }
                val existed = f.exists()
                f.parentFile?.mkdirs()
                f.outputStream().buffered(256 * 1024).use { body.copyTo(it) }
                respond(out, if (existed) 204 else 201, if (existed) "No Content" else "Created")
            }
            "MKCOL" -> { body.drain(); val f = resolveNew(rel) ?: return respond(out, 403, "Forbidden"); if (f.exists()) respond(out, 405, "Method Not Allowed") else if (f.mkdirs()) respond(out, 201, "Created") else respond(out, 409, "Conflict") }
            "DELETE" -> { body.drain(); val f = resolve(rel) ?: return respond(out, 404, "Not Found"); if (f.canonicalPath == rootPath) respond(out, 403, "Forbidden") else if (f.deleteRecursively()) respond(out, 204, "No Content") else respond(out, 500, "Internal Server Error") }
            "MOVE", "COPY" -> {
                body.drain()
                val src = resolve(rel) ?: return respond(out, 404, "Not Found")
                val dst = destination(headers) ?: return respond(out, 400, "Bad Request")
                val overwrite = headers["overwrite"]?.uppercase() != "F"
                if (dst.exists() && !overwrite) return respond(out, 412, "Precondition Failed")
                val existed = dst.exists()
                if (existed) dst.deleteRecursively()
                dst.parentFile?.mkdirs()
                val ok = if (method == "MOVE") (src.renameTo(dst) || (src.copyRecursively(dst, true) && src.deleteRecursively())) else src.copyRecursively(dst, true)
                if (ok) respond(out, if (existed) 204 else 201, if (existed) "No Content" else "Created") else respond(out, 500, "Internal Server Error")
            }
            "LOCK" -> {
                body.drain()
                val token = "opaquelocktoken:${UUID.randomUUID()}"
                val xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?><D:prop xmlns:D=\"DAV:\"><D:lockdiscovery><D:activelock><D:locktype><D:write/></D:locktype><D:lockscope><D:exclusive/></D:lockscope><D:depth>0</D:depth><D:timeout>Second-3600</D:timeout><D:locktoken><D:href>$token</D:href></D:locktoken></D:activelock></D:lockdiscovery></D:prop>"
                respond(out, 200, "OK", mapOf("Lock-Token" to "<$token>"), xml.toByteArray(), "application/xml; charset=utf-8")
            }
            "UNLOCK" -> { body.drain(); respond(out, 204, "No Content") }
            "PROPPATCH" -> {
                body.drain()
                val f = resolve(rel)
                val xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?><D:multistatus xmlns:D=\"DAV:\"><D:response><D:href>${xml(f?.let { hrefFor(it) } ?: rel)}</D:href><D:propstat><D:prop/><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>"
                respond(out, 207, "Multi-Status", body = xml.toByteArray(), type = "application/xml; charset=utf-8")
            }
        }
    }

    companion object { const val PORT = 50380 }
}
