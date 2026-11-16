package com.example.doccollector

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Patterns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

enum class Kind(val label: String, val badge: String, val bg: Long, val fg: Long) {
    IMAGE("Images", "IMG", 0xFFD6E9FF, 0xFF0B4F9C),
    VIDEO("Videos", "VID", 0xFFFFE0D2, 0xFF9A3412),
    AUDIO("Audio", "AUD", 0xFFE9DDFF, 0xFF5B21B6),
    PDF("PDFs", "PDF", 0xFFFFD9D9, 0xFFB91C1C),
    HTML("Web pages", "HTML", 0xFFD8F5E0, 0xFF166534),
    LINK("Links", "LINK", 0xFFD5F3F6, 0xFF0E7490),
    TEXT("Text", "TXT", 0xFFEDEDED, 0xFF404040),
    DOC("Office", "DOC", 0xFFFFF1C2, 0xFF854D0E),
    OTHER("Other", "FILE", 0xFFE5E7EB, 0xFF374151)
}

private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "svg")
private val VIDEO_EXT = setOf("mp4", "mkv", "webm", "mov", "avi", "3gp", "m4v", "ts", "flv", "wmv")
private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "wav", "ogg", "opus", "flac", "amr")
private val HTML_EXT = setOf("html", "htm", "xhtml", "mhtml", "mht")
private val TEXT_EXT = setOf("txt", "md", "csv", "json", "xml", "log", "rtf")
private val DOC_EXT = setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "epub")

fun kindOf(name: String): Kind {
    val e = name.substringAfterLast('.', "").lowercase()
    return when {
        e in IMAGE_EXT -> Kind.IMAGE
        e in VIDEO_EXT -> Kind.VIDEO
        e in AUDIO_EXT -> Kind.AUDIO
        e == "pdf" -> Kind.PDF
        e in HTML_EXT -> Kind.HTML
        e == "url" -> Kind.LINK
        e in TEXT_EXT -> Kind.TEXT
        e in DOC_EXT -> Kind.DOC
        else -> Kind.OTHER
    }
}

fun mimeOf(name: String): String {
    val e = name.substringAfterLast('.', "").lowercase()
    if (e in HTML_EXT) return "text/html"
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(e) ?: "*/*"
}

object Store {
    private fun dir(ctx: Context) = File(ctx.filesDir, "docs").apply { mkdirs() }

    fun list(ctx: Context): List<File> =
        dir(ctx).listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    private fun sanitize(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trimStart('.').take(120)

    private fun uniqueFile(ctx: Context, name: String): File {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var f = File(dir(ctx), base + ext)
        var i = 1
        while (f.exists()) f = File(dir(ctx), "$base ($i)$ext").also { i++ }
        return f
    }

    fun displayName(ctx: Context, uri: Uri): String? {
        var name: String? = null
        runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
            }
        }
        return name ?: uri.lastPathSegment
    }

    /** Any file / image / video / html / pdf etc. shared as a stream. */
    fun addUri(ctx: Context, uri: Uri): File {
        var name = sanitize(displayName(ctx, uri) ?: "")
        if (name.isBlank()) name = "file_${System.currentTimeMillis()}"
        // No usable extension? Work it out from the MIME type
        if (!Regex("\\.[A-Za-z0-9]{1,5}$").containsMatchIn(name)) {
            val ext = ctx.contentResolver.getType(uri)
                ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            if (ext != null) name = "$name.$ext"
        }
        val f = uniqueFile(ctx, name)
        try {
            val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open")
            input.use { i -> f.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
        } catch (t: Throwable) {
            f.delete()
            throw t
        }
        return f
    }

    /** Returns a normalised URL if the text is nothing but one web link. */
    fun asUrl(text: String): String? {
        val t = text.trim()
        if (t.isEmpty() || t.any { it.isWhitespace() }) return null
        if (!Patterns.WEB_URL.matcher(t).matches()) return null
        return if (t.startsWith("http://", true) || t.startsWith("https://", true)) t else "https://$t"
    }

    fun hostOf(url: String): String =
        runCatching { URL(url).host.removePrefix("www.") }.getOrDefault("link")

    fun linkOf(f: File): String? =
        runCatching {
            f.readLines().firstOrNull { it.startsWith("URL=") }?.removePrefix("URL=")?.trim()
        }.getOrNull()

    fun addLink(ctx: Context, url: String, subject: String?): File {
        val title = subject?.takeIf { it.isNotBlank() } ?: hostOf(url)
        val f = uniqueFile(ctx, sanitize(title).take(80).ifBlank { "link" } + ".url")
        f.writeText("[InternetShortcut]\nURL=$url\n")
        return f
    }

    fun addText(ctx: Context, text: String, subject: String?): File {
        val title = subject?.takeIf { it.isNotBlank() }
            ?: text.trim().lineSequence().first().take(40)
        val f = uniqueFile(ctx, sanitize(title).ifBlank { "note" } + ".txt")
        f.writeText(text)
        return f
    }

    fun addHtml(ctx: Context, html: String, subject: String?): File {
        val title = subject?.takeIf { it.isNotBlank() } ?: titleOf(html) ?: "page"
        val f = uniqueFile(ctx, sanitize(title).ifBlank { "page" } + ".html")
        val body = if (html.take(2000).contains("charset", true)) html
        else "<meta charset=\"utf-8\">\n$html"
        f.writeText(body)
        return f
    }

    private fun titleOf(html: String): String? =
        Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.get(1)
            ?.replace(Regex("\\s+"), " ")?.replace("&amp;", "&")?.trim()
            ?.takeIf { it.isNotBlank() }

    // ---------- Download a shared link (web page, or direct image/video/file URL) ----------

    private fun connect(start: URL): HttpURLConnection {
        var url = start
        var hops = 0
        while (true) {
            val c = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) DocCollector/1.1")
            }
            val code = c.responseCode
            if (code in 300..399 && hops < 5) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc == null) throw IOException("Redirect without location")
                url = URL(url, loc)
                hops++
                continue
            }
            if (code !in 200..299) {
                c.disconnect()
                throw IOException("HTTP $code")
            }
            return c
        }
    }

    private fun decode(s: String): String =
        runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    fun download(ctx: Context, link: String): File {
        val conn = connect(URL(link))
        try {
            val mime = conn.contentType?.substringBefore(';')?.trim()?.lowercase()
            val isHtml = mime == "text/html" || mime == "application/xhtml+xml"

            var name: String
            if (isHtml) {
                name = hostOf(link) + ".html"
            } else {
                val fromHeader = Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
                    .find(conn.getHeaderField("Content-Disposition") ?: "")?.groupValues?.get(1)
                val fromUrl = decode(URL(link).path.substringAfterLast('/'))
                name = sanitize(fromHeader?.let { decode(it) } ?: fromUrl)
                if (name.isBlank()) name = "download"
                if (!Regex("\\.[A-Za-z0-9]{1,5}$").containsMatchIn(name)) {
                    val ext = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) } ?: "bin"
                    name = "$name.$ext"
                }
            }

            val f = uniqueFile(ctx, name)
            try {
                conn.inputStream.use { i -> f.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
            } catch (t: Throwable) {
                f.delete()
                throw t
            }

            if (isHtml) {
                val head = f.inputStream().use { s ->
                    val buf = ByteArray(100_000)
                    val n = s.read(buf)
                    String(buf, 0, maxOf(n, 0), Charsets.UTF_8)
                }
                val title = titleOf(head)
                if (title != null) {
                    val renamed = uniqueFile(ctx, sanitize(title).take(80).ifBlank { "page" } + ".html")
                    if (f.renameTo(renamed)) return renamed
                }
            }
            return f
        } finally {
            conn.disconnect()
        }
    }
}
