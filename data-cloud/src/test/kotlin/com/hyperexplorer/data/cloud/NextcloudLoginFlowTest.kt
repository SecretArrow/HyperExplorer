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

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Test JVM murni untuk [NextcloudLoginFlow] via MockWebServer (tanpa jaringan eksternal).
 * Semua delay memakai nilai pendek (250 ms) agar deterministik dan cepat.
 */
class NextcloudLoginFlowTest {
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

    private fun flow(): NextcloudLoginFlow = NextcloudLoginFlow(server.url("/").toString())

    private fun start(): LoginFlowStart =
        LoginFlowStart(
            loginUrl = server.url("/login").toString(),
            pollEndpoint = server.url("/index.php/login/v2/poll").toString(),
            pollToken = TOKEN,
        )

    private fun startPayload(): String =
        """{"poll":{"token":"$TOKEN","endpoint":"${start().pollEndpoint}"},"login":"${start().loginUrl}"}"""

    private fun pollPayload(): String = """{"server":"https://cloud.example.com/nextcloud","loginName":"andi","appPassword":"secret-abc"}"""

    @Test
    fun `start success returns login flow start with correct fields`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(200).setBody(startPayload()))
            val loginFlowStart = flow().start()
            assertEquals(TOKEN, loginFlowStart.pollToken)
            assertEquals(server.url("/index.php/login/v2/poll").toString(), loginFlowStart.pollEndpoint)
            assertEquals(server.url("/login").toString(), loginFlowStart.loginUrl)
            val recorded = server.takeRequest()
            assertEquals("/index.php/login/v2", recorded.path)
            assertEquals("POST", recorded.method)
            assertEquals("app=Hyper%20Explorer", recorded.body.readUtf8())
            assertEquals("application/json", recorded.getHeader("Accept"))
            assertEquals("application/x-www-form-urlencoded", recorded.getHeader("Content-Type"))
        }

    @Test
    fun `start with non-200 response throws IOException`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(500))
            val e = assertThrows(IOException::class.java) { runBlocking { flow().start() } }
            assertEquals("Nextcloud login flow start failed: HTTP 500", e.message)
        }

    @Test
    fun `start with broken json throws IOException`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(200).setBody("not-json{{"))
            val e = assertThrows(IOException::class.java) { runBlocking { flow().start() } }
            assertTrue("Unexpected message: ${e.message}", e.message!!.contains("invalid JSON"))
        }

    @Test
    fun `start with missing or blank fields throws IOException`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"poll":{"token":"t","endpoint":"e"}}"""))
            val missing = assertThrows(IOException::class.java) { runBlocking { flow().start() } }
            assertTrue("Unexpected message: ${missing.message}", missing.message!!.contains("invalid JSON"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"poll":{"token":"","endpoint":"e"},"login":"u"}"""))
            val blank = assertThrows(IOException::class.java) { runBlocking { flow().start() } }
            assertTrue("Unexpected message: ${blank.message}", blank.message!!.contains("poll.token"))
        }

    @Test
    fun `poll success returns credentials and sends token`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(200).setBody(pollPayload()))
            val credentials = flow().poll(start())
            assertNotNull(credentials)
            assertEquals("https://cloud.example.com/nextcloud", credentials!!.server)
            assertEquals("andi", credentials.loginName)
            assertEquals("secret-abc", credentials.appPassword)
            val recorded = server.takeRequest()
            assertEquals("/index.php/login/v2/poll", recorded.path)
            assertEquals("POST", recorded.method)
            assertEquals("token=tok-123", recorded.body.readUtf8())
            assertEquals("application/json", recorded.getHeader("Accept"))
        }

    @Test
    fun `poll with 202 returns null while waiting for approval`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(202))
            assertNull(flow().poll(start()))
        }

    @Test
    fun `poll with 404 throws expired IOException`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(404))
            val e = assertThrows(IOException::class.java) { runBlocking { flow().poll(start()) } }
            assertTrue("Unexpected message: ${e.message}", e.message!!.contains("expired"))
        }

    @Test
    fun `poll with unexpected status throws IOException with status code`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(503))
            val e = assertThrows(IOException::class.java) { runBlocking { flow().poll(start()) } }
            assertEquals("Nextcloud login flow poll failed: HTTP 503", e.message)
        }

    @Test
    fun `awaitCredentials polls queue 202 202 200 then returns credentials`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(202))
            server.enqueue(MockResponse().setResponseCode(202))
            server.enqueue(MockResponse().setResponseCode(200).setBody(pollPayload()))
            // Catatan: brief tugas menyebut interval 50 ms, namun kontrak API fail-fast
            // memvalidasi intervalMs >= 250 (MIN_INTERVAL_MS); 250 ms tetap nilai pendek.
            val credentials = flow().awaitCredentials(start(), timeoutMs = 5_000L, intervalMs = 250L)
            assertNotNull(credentials)
            assertEquals("secret-abc", credentials!!.appPassword)
            assertEquals(3, server.requestCount)
        }

    @Test
    fun `awaitCredentials returns null after short timeout when never approved`() =
        runBlocking {
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setResponseCode(202)
                }
            assertNull(flow().awaitCredentials(start(), timeoutMs = 500L, intervalMs = 250L))
        }

    @Test
    fun `awaitCredentials rejects non positive timeout and too small interval`() {
        // Badan blok (bukan ekspresi): assertThrows mengembalikan nilai — method JUnit
        // wajib bertipe void agar kelas tidak ditolak (InvalidTestClassError).
        runBlocking {
            val flow = flow()
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { flow.awaitCredentials(start(), timeoutMs = 0L) }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { flow.awaitCredentials(start(), timeoutMs = 1_000L, intervalMs = 50L) }
            }
        }
    }

    @Test
    fun `server url without scheme gets https prefix and trailing slash removed`() {
        assertEquals("https://cloud.example.com/nc", NextcloudLoginFlow(" cloud.example.com/nc/ ").serverUrl)
        assertEquals("https://cloud.example.com", NextcloudLoginFlow("https://cloud.example.com/").serverUrl)
    }

    @Test
    fun `blank server url throws IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) { NextcloudLoginFlow("   ") }
    }

    @Test
    fun `invalid server url throws IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) { NextcloudLoginFlow("https://ho st") }
        assertThrows(IllegalArgumentException::class.java) { NextcloudLoginFlow("https://") }
        assertThrows(IllegalArgumentException::class.java) { NextcloudLoginFlow("https://host:port") }
    }

    private companion object {
        const val TOKEN = "tok-123"
    }
}
