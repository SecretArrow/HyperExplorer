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
import com.hyperexplorer.data.remote.RemoteCredentials
import com.hyperexplorer.data.remote.RemoteProtocol
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.Base64

/** Test JVM murni [NextcloudRemote] dengan MockWebServer (tanpa server Nextcloud asli). */
class NextcloudRemoteTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `list parses multistatus and skips the collection itself`() {
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(207).setBody(MULTISTATUS))
            nextcloud().use { remote ->
                val entries = remote.list("")

                assertEquals(2, entries.size)
                val file = entries.first { it.name == "my file.txt" }
                assertEquals("remote.php/dav/files/user/my file.txt", file.path)
                assertFalse(file.isDirectory)
                assertEquals(5L, file.size)
                assertEquals(1445412480000L, file.lastModified)
                val folder = entries.first { it.name == "Photos" }
                assertEquals("remote.php/dav/files/user/Photos", folder.path)
                assertTrue(folder.isDirectory)
                assertEquals(0L, folder.size)

                val recorded = server.takeRequest()
                assertEquals("PROPFIND", recorded.method)
                assertEquals("/remote.php/dav/files/user", recorded.path)
                assertEquals("1", recorded.getHeader("Depth"))
                val auth = recorded.getHeader("Authorization")
                assertNotNull(auth)
                assertTrue(auth!!.startsWith("Basic "))
                assertEquals(basicAuth("user", "app-password"), auth)
            }
        }
    }

    @Test
    fun `makeDirectory sends MKCOL under the user DAV root`() {
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(201))
            nextcloud().use { remote ->
                remote.makeDirectory("", "new-folder")
            }
            val recorded = server.takeRequest()
            assertEquals("MKCOL", recorded.method)
            assertEquals("/remote.php/dav/files/user/new-folder", recorded.path)
        }
    }

    @Test
    fun `delete sends DELETE to the target path`() {
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(204))
            nextcloud().use { remote ->
                remote.delete("docs/old.txt", isDirectory = false)
            }
            val recorded = server.takeRequest()
            assertEquals("DELETE", recorded.method)
            assertEquals("/remote.php/dav/files/user/docs/old.txt", recorded.path)
        }
    }

    @Test
    fun `rename sends MOVE with an absolute Destination header`() {
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(201))
            nextcloud().use { remote ->
                remote.rename("docs", "old.txt", "new.txt")
            }
            val recorded = server.takeRequest()
            assertEquals("MOVE", recorded.method)
            assertEquals("/remote.php/dav/files/user/docs/old.txt", recorded.path)
            val destination = recorded.getHeader("Destination")
            assertNotNull(destination)
            assertTrue(destination!!.startsWith("http://"))
            assertTrue(destination.endsWith("/remote.php/dav/files/user/docs/new.txt"))
        }
    }

    @Test
    fun `download streams the response body into the target file`() {
        runBlocking {
            server.enqueue(MockResponse().setBody("hello"))
            val target = File(File(temp.root, "missing-dir"), "report.bin")
            nextcloud().use { remote ->
                remote.download("docs/report.bin", target)
            }
            assertEquals("hello", target.readText())
            val recorded = server.takeRequest()
            assertEquals("GET", recorded.method)
            assertEquals("/remote.php/dav/files/user/docs/report.bin", recorded.path)
        }
    }

    @Test
    fun `upload sends the local file content as octet stream`() {
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(201))
            val local = File(temp.root, "upload.txt").apply { writeText("file-content") }
            nextcloud().use { remote ->
                remote.upload(local, "docs")
            }
            val recorded = server.takeRequest()
            assertEquals("PUT", recorded.method)
            assertEquals("application/octet-stream", recorded.getHeader("Content-Type"))
            assertEquals("file-content", recorded.body.readUtf8())
            assertEquals("/remote.php/dav/files/user/docs/upload.txt", recorded.path)
        }
    }

    @Test
    fun `basePath prefixes the DAV path over plain http`() {
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(201))
            // secure = false → skema http; MockWebServer hanya melayani HTTP polos,
            // jadi request yang berhasil membuktikan skema http terpakai.
            nextcloud(basePath = "nc").use { remote ->
                remote.makeDirectory("", "reports")
            }
            val recorded = server.takeRequest()
            assertEquals("MKCOL", recorded.method)
            assertTrue(recorded.path!!.contains("/nc/remote.php/dav/files/user"))
            assertEquals("/nc/remote.php/dav/files/user/reports", recorded.path)
        }
    }

    @Test
    fun `blank username fails fast with IllegalArgumentException`() {
        val remote = nextcloud(username = "   ")
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { remote.list("") }
            }
        assertEquals("Nextcloud username is required", error.message)
    }

    @Test
    fun `path traversal is rejected by RemotePath before any request`() {
        val remote = nextcloud()
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { remote.list("..%20/..") }
            }
        assertEquals("Path traversal is not allowed: ..%20/..", error.message)
    }

    @Test
    fun `operations after close fail with IllegalStateException`() {
        val remote = nextcloud()
        remote.close()
        remote.close() // idempoten
        val error =
            assertThrows(IllegalStateException::class.java) {
                runBlocking { remote.list("") }
            }
        assertEquals("Remote file system is closed", error.message)
    }

    @Test
    fun `list maps HTTP 500 to IOException mentioning the operation`() {
        server.enqueue(MockResponse().setResponseCode(500))
        val remote = nextcloud()
        val error =
            assertThrows(IOException::class.java) {
                runBlocking { remote.list("") }
            }
        assertTrue(error.message!!.contains("Nextcloud list failed"))
    }

    @Test
    fun `upload rejects a missing local file with IOException`() {
        val remote = nextcloud()
        val missing = File(temp.root, "does-not-exist.bin")
        val error =
            assertThrows(IOException::class.java) {
                runBlocking { remote.upload(missing, "docs") }
            }
        assertTrue(error.message!!.contains("local file not found"))
    }

    /** Koneksi NEXTCLOUD ke [server]; secure = false → http (MockWebServer polos). */
    private fun nextcloud(
        basePath: String = "",
        username: String = "user",
    ): NextcloudRemote =
        NextcloudRemote(
            RemoteConnection(
                protocol = RemoteProtocol.NEXTCLOUD,
                host = server.hostName,
                port = server.port,
                basePath = basePath,
                credentials = RemoteCredentials(username = username, password = "app-password", anonymous = false),
                secure = false,
            ),
        )

    private fun basicAuth(
        username: String,
        password: String,
    ): String = "Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))

    private companion object {
        /** Multistatus DAV: entri dir-sendiri + 1 berkas (href percent-encoded) + 1 folder. */
        val MULTISTATUS =
            """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/remote.php/dav/files/user/</d:href>
                <d:propstat>
                  <d:prop>
                    <d:resourcetype>
                      <d:collection/>
                    </d:resourcetype>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
              <d:response>
                <d:href>/remote.php/dav/files/user/my%20file.txt</d:href>
                <d:propstat>
                  <d:prop>
                    <d:resourcetype/>
                    <d:getcontentlength>5</d:getcontentlength>
                    <d:getlastmodified>Wed, 21 Oct 2015 07:28:00 GMT</d:getlastmodified>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
              <d:response>
                <d:href>/remote.php/dav/files/user/Photos/</d:href>
                <d:propstat>
                  <d:prop>
                    <d:resourcetype>
                      <d:collection/>
                    </d:resourcetype>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>
            """.trimIndent()
    }
}
