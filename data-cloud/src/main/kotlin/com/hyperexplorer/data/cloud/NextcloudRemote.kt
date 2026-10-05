/*
 * Hyper Explorer - a free and open source file manager for Android.
 * Copyright (C) 2025-2026 Hyper Explorer contributors
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.hyperexplorer.data.cloud

import com.hyperexplorer.data.remote.RemoteConnection
import com.hyperexplorer.data.remote.RemoteEntry
import com.hyperexplorer.data.remote.RemoteFileSystem
import com.hyperexplorer.data.remote.RemotePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.time.Duration
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Base64
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Klien berkas cloud Nextcloud di atas endpoint WebDAV bawaan server.
 *
 * URL DAV: "{http|https}://host:port/[basePath/]remote.php/dav/files/<username>/<path>"
 * — skema https bila [RemoteConnection.secure] aktif, dan [RemoteConnection.basePath]
 * opsional untuk instalasi Nextcloud di sub-direktori (mis. "nextcloud"). Autentikasi
 * selalu memakai Basic auth berisi username dan APP PASSWORD (token yang dibuat dari
 * menu Settings → Security → "Devices & sessions", bukan password utama akun) pada
 * setiap request.
 *
 * Keamanan:
 * - Respons XML PROPFIND diparse dengan [DocumentBuilderFactory] yang diperkuat:
 *   secure processing, doctype-decl dilarang, entity eksternal dimatikan, dan parser
 *   namespace-aware — proteksi XXE terhadap isi respons server.
 * - Setiap path pemanggil divalidasi [RemotePath.requireSafe] (anti-traversal), lalu
 *   digabung ke dalam root DAV user, sehingga koordinat tidak bisa keluar dari ruang
 *   berkas user di server.
 *
 * Fail-fast: username kosong menggagalkan setiap operasi dengan
 * [IllegalArgumentException]; operasi setelah [close] melempar
 * [IllegalStateException]. Kegagalan jaringan/protokol dibungkus [IOException] dengan
 * konteks operasi dan target.
 */
class NextcloudRemote(
    private val connection: RemoteConnection,
) : RemoteFileSystem {
    @Volatile
    private var closed = false

    private val http: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(Duration.ofSeconds(30))
            .readTimeout(Duration.ofSeconds(30))
            .writeTimeout(Duration.ofSeconds(30))
            .build()

    override suspend fun list(path: String): List<RemoteEntry> =
        io("list", path) {
            requireUsername()
            RemotePath.requireSafe(path)
            val dir = davPath(path)
            val body = PROPFIND_BODY.toRequestBody(XML_MEDIA_TYPE)
            val request =
                buildRequest(dir) {
                    header("Depth", "1")
                    method("PROPFIND", body)
                }
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val bytes = response.body?.bytes() ?: throw IOException("empty response body")
                parseMultistatus(bytes, path, dir)
            }
        }

    override suspend fun makeDirectory(
        parent: String,
        name: String,
    ) = io("mkcol", name) {
        requireUsername()
        RemotePath.requireSafe(parent)
        RemotePath.requireSafe(name)
        val request = buildRequest(davPath(RemotePath.join(parent, name))) { method("MKCOL", null) }
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        }
    }

    override suspend fun delete(
        path: String,
        isDirectory: Boolean,
    ) = io("delete", path) {
        // isDirectory diabaikan: DELETE Nextcloud berlaku untuk berkas & koleksi.
        requireUsername()
        RemotePath.requireSafe(path)
        val request = buildRequest(davPath(path)) { method("DELETE", null) }
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        }
    }

    override suspend fun rename(
        path: String,
        oldName: String,
        newName: String,
    ) = io("rename", oldName) {
        requireUsername()
        RemotePath.requireSafe(path)
        RemotePath.requireSafe(oldName)
        RemotePath.requireSafe(newName)
        val destination = url(davPath(RemotePath.join(path, newName)))
        val request =
            buildRequest(davPath(RemotePath.join(path, oldName))) {
                header("Destination", destination)
                method("MOVE", null)
            }
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        }
    }

    override suspend fun download(
        remotePath: String,
        target: File,
        sizeHint: Long,
    ) = io<Unit>("download", remotePath) {
        // sizeHint diabaikan: stream dibaca sampai habis.
        requireUsername()
        RemotePath.requireSafe(remotePath)
        val request = buildRequest(davPath(remotePath)) { get() }
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val input = response.body?.byteStream() ?: throw IOException("empty response body")
            target.parentFile?.mkdirs()
            target.outputStream().use { output ->
                input.copyTo(output, 64 * 1024)
            }
        }
    }

    override suspend fun upload(
        local: File,
        remoteDir: String,
    ) = io("upload", local.name) {
        requireUsername()
        RemotePath.requireSafe(remoteDir)
        if (!local.isFile) throw IOException("local file not found: ${local.absolutePath}")
        val request =
            buildRequest(davPath(RemotePath.join(remoteDir, local.name))) {
                put(local.asRequestBody(OCTET_STREAM_MEDIA_TYPE))
            }
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        }
    }

    override fun close() {
        // Idempoten: hanya menandai flag; operasi berikutnya gagal lewat ensureOpen().
        closed = true
    }

    /** Username koneksi; fail-fast bila kosong (Nextcloud selalu butuh username). */
    private fun requireUsername(): String {
        val username = connection.credentials.username
        if (username.isBlank()) throw IllegalArgumentException("Nextcloud username is required")
        return username
    }

    private fun ensureOpen() {
        if (closed) throw IllegalStateException("Remote file system is closed")
    }

    private fun buildRequest(
        remotePath: String,
        configure: Request.Builder.() -> Unit,
    ): Request {
        val builder = Request.Builder().url(url(remotePath))
        val (headerName, headerValue) = authHeader()
        builder.header(headerName, headerValue)
        builder.configure()
        return builder.build()
    }

    /** URL absolut "http(s)://host[:port]/<path DAV>" dengan percent-encoding per segmen. */
    private fun url(remotePath: String): String {
        val scheme = if (connection.secure) "https" else "http"
        val path = "/" + remotePath
        return URI(scheme, null, connection.host, connection.port, path, null, null).toASCIIString()
    }

    /** Header basic auth (username:appPassword); selalu dikirim, tanpa mode anonim. */
    private fun authHeader(): Pair<String, String> {
        val creds = connection.credentials
        val raw = "${creds.username}:${creds.password}"
        val encoded = Base64.getEncoder().encodeToString(raw.toByteArray(Charsets.UTF_8))
        return "Authorization" to "Basic $encoded"
    }

    /** Root DAV user: "[basePath/]remote.php/dav/files/<username>". */
    private fun davRoot(): String {
        val username = requireUsername()
        val base = connection.basePath.trim('/')
        return listOf(base, "remote.php", "dav", "files", username)
            .filter { it.isNotEmpty() }
            .joinToString("/")
    }

    /** Path DAV lengkap untuk koordinat remote [remotePath] (root DAV user saat ""). */
    private fun davPath(remotePath: String): String = RemotePath.join(davRoot(), remotePath)

    private fun parseMultistatus(
        bytes: ByteArray,
        listingPath: String,
        dir: String,
    ): List<RemoteEntry> {
        val document =
            try {
                secureFactory().newDocumentBuilder().parse(ByteArrayInputStream(bytes))
            } catch (e: Exception) {
                throw IOException("invalid XML in PROPFIND response: ${e.message}", e)
            }
        val entries = mutableListOf<RemoteEntry>()
        val self = RemotePath.normalize(listingPath) // target listing, relatif root DAV
        val responses = document.getElementsByTagNameNS("DAV:", "response")
        for (index in 0 until responses.length) {
            val response = responses.item(index) as? Element ?: continue
            val href = response.text("href") ?: continue
            val relative = relativize(href) ?: continue
            if (relative == self) continue // entri dir-nya sendiri (Depth:1)
            val name = relative.substringAfterLast('/')
            val isDirectory =
                response.getElementsByTagNameNS("DAV:", "collection").length > 0 || href.endsWith('/')
            entries +=
                RemoteEntry(
                    name = name,
                    path = RemotePath.join(dir, name),
                    isDirectory = isDirectory,
                    size = response.text("getcontentlength")?.toLongOrNull() ?: 0L,
                    lastModified = response.text("getlastmodified")?.let { parseRfc1123(it) } ?: 0L,
                )
        }
        return entries
    }

    /**
     * Parser XML yang diperkuat: secure processing, doctype-decl dilarang, entity
     * eksternal dimatikan — proteksi XXE terhadap isi respons server.
     */
    private fun secureFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
            isNamespaceAware = true
        }

    /** Relatifkan href terhadap root DAV user; null bila href di luar root. */
    private fun relativize(href: String): String? {
        val decoded =
            try {
                URI(href).path ?: return null
            } catch (_: URISyntaxException) {
                return null
            }
        val withSlash = if (decoded.startsWith("/")) decoded else "/$decoded"
        val base = "/" + davRoot()
        if (withSlash != base && !withSlash.startsWith("$base/")) return null
        return withSlash.removePrefix(base).trim('/')
    }

    private fun Element.text(localName: String): String? = getElementsByTagNameNS("DAV:", localName).item(0)?.textContent

    /** Parse tanggal RFC 1123 ("Wed, 21 Oct 2015 07:28:00 GMT"); fallback 0. */
    private fun parseRfc1123(value: String): Long =
        try {
            OffsetDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            0L
        }

    private suspend fun <T> io(
        operation: String,
        target: String,
        block: suspend () -> T,
    ): T =
        withContext(Dispatchers.IO) {
            ensureOpen()
            try {
                block()
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: IllegalStateException) {
                throw e
            } catch (e: IOException) {
                throw IOException("Nextcloud $operation failed for '$target': ${e.message}", e)
            } catch (e: Exception) {
                throw IOException(
                    "Nextcloud $operation failed for '$target': ${e.javaClass.simpleName}: ${e.message}",
                    e,
                )
            }
        }

    private companion object {
        val XML_MEDIA_TYPE: MediaType = "application/xml".toMediaType()
        val OCTET_STREAM_MEDIA_TYPE: MediaType = "application/octet-stream".toMediaType()
        const val PROPFIND_BODY =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<d:propfind xmlns:d=\"DAV:\"><d:prop>" +
                "<d:resourcetype/><d:getcontentlength/><d:getlastmodified/>" +
                "</d:prop></d:propfind>"
    }
}
