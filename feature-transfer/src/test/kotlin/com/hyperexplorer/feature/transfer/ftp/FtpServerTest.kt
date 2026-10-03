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

package com.hyperexplorer.feature.transfer.ftp

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintStream
import java.net.Socket

/**
 * Test JVM murni untuk [FtpServer] memakai klien socket mini di localhost.
 * Idle-loop sungguhan sengaja tidak dites (rawan flaky); logikanya diuji
 * lewat [FtpServer.shouldAutoStop] yang murni.
 */
class FtpServerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var rootDir: File
    private lateinit var server: FtpServer
    private lateinit var controlSocket: Socket
    private lateinit var reader: BufferedReader
    private lateinit var writer: PrintStream

    @Before
    fun setUp() {
        rootDir = temporaryFolder.newFolder("ftp-root")
        server =
            FtpServer(
                FtpConfig(
                    port = 0,
                    username = "hyper",
                    password = "rahasia",
                    rootDir = rootDir,
                    idleTimeoutMinutes = 15,
                ),
            )
        server.start()
        connect()
    }

    @After
    fun tearDown() {
        server.stop()
    }

    @Test
    fun `login salah dan perintah sebelum login ditolak`() =
        runBlocking<Unit> {
            assertEquals("331 Password required", command("USER hyper"))
            assertTrue(command("PASS salah-sandi").startsWith("530"))
            assertTrue(command("PWD").startsWith("530"))
            assertTrue(command("LIST").startsWith("530"))
            // SYST/NOOP memang diizinkan sebelum login.
            assertEquals("215 UNIX Type: L8", command("SYST"))
            assertEquals("200 NOOP ok", command("NOOP"))
        }

    @Test
    fun `siklus berkas lengkap via pasv`() =
        runBlocking<Unit> {
            login()
            assertEquals("502 Command not implemented", command("MAKESUCH"))
            assertEquals("257 \"/\" is the current directory", command("PWD"))
            assertEquals("257 Directory created", command("MKD testdir"))
            assertEquals("250 Directory changed", command("CWD testdir"))
            assertEquals("257 \"/testdir\" is the current directory", command("PWD"))

            storeFile("hello.txt", "Hello FTP")
            // "Hello FTP" = 9 byte.
            assertEquals("213 9", command("SIZE hello.txt"))

            assertEquals("Hello FTP", retrieveFile("hello.txt"))

            val listing = listNames()
            assertTrue("NLST harus memuat hello.txt: $listing", listing.contains("hello.txt"))

            assertEquals("350 Ready for RNTO", command("RNFR hello.txt"))
            assertEquals("250 Rename successful", command("RNTO hi.txt"))
            assertTrue(command("SIZE hi.txt").startsWith("213"))
            assertEquals("250 File deleted", command("DELE hi.txt"))
            assertEquals("250 Directory changed", command("CDUP"))
            assertEquals("257 \"/\" is the current directory", command("PWD"))
        }

    @Test
    fun `path traversal di luar root ditolak`() =
        runBlocking<Unit> {
            login()
            assertTrue(command("CWD ../..").startsWith("550"))
            assertTrue(command("RETR ../../etc/passwd").startsWith("550"))
            assertTrue(command("CWD /../../etc").startsWith("550"))
            assertEquals("257 \"/\" is the current directory", command("PWD"))
        }

    @Test
    fun `stop mematikan server dan menutup klien`() =
        runBlocking<Unit> {
            assertTrue(server.isRunning)
            server.stop()
            assertFalse(server.isRunning)
            val line =
                try {
                    reader.readLine()
                } catch (_: IOException) {
                    null
                }
            assertNull("Koneksi kontrol harus tertutup setelah stop()", line)
        }

    @Test
    fun `konfigurasi tanpa kredensial ditolak`() {
        val anonymous =
            FtpServer(
                FtpConfig(port = 2121, username = "", password = "x", rootDir = rootDir),
            )
        try {
            anonymous.start()
            fail("Server tanpa kredensial harus ditolak")
        } catch (e: IllegalStateException) {
            assertEquals("Server FTP wajib memiliki nama pengguna dan kata sandi", e.message)
        }
    }

    @Test
    fun `konfigurasi port dan root tidak valid ditolak`() {
        assertStartFails(FtpServer(FtpConfig(port = 80, username = "hyper", password = "rahasia", rootDir = rootDir)))
        assertStartFails(
            FtpServer(
                FtpConfig(port = 2121, username = "hyper", password = "rahasia", rootDir = File(rootDir, "tidak-ada")),
            ),
        )
    }

    @Test
    fun `perhitungan idle murni shouldAutoStop`() {
        assertTrue(FtpServer.shouldAutoStop(connectionCount = 0, idleMillis = 15 * 60_000L, timeoutMinutes = 15))
        assertFalse(FtpServer.shouldAutoStop(connectionCount = 2, idleMillis = 999 * 60_000L, timeoutMinutes = 15))
        assertFalse(FtpServer.shouldAutoStop(connectionCount = 2, idleMillis = 60_000L, timeoutMinutes = 0))
        assertTrue(FtpServer.shouldAutoStop(connectionCount = 0, idleMillis = 0, timeoutMinutes = 0))
    }

    // --- helper klien socket mini ---

    private fun assertStartFails(target: FtpServer) {
        try {
            target.start()
            fail("start() seharusnya melempar IllegalStateException")
        } catch (_: IllegalStateException) {
            // Diharapkan.
        }
    }

    private fun connect() {
        controlSocket = Socket("127.0.0.1", server.boundPort())
        reader = BufferedReader(InputStreamReader(controlSocket.getInputStream(), Charsets.ISO_8859_1))
        writer = PrintStream(controlSocket.getOutputStream(), true, "ISO-8859-1")
        assertEquals("220 Hyper Explorer FTP server ready", reader.readLine())
    }

    private fun send(commandText: String) {
        writer.print("$commandText\r\n")
    }

    private fun command(commandText: String): String {
        send(commandText)
        return reader.readLine() ?: error("Koneksi kontrol tertutup saat mengirim: $commandText")
    }

    private fun login() {
        assertEquals("331 Password required", command("USER hyper"))
        assertEquals("230 Login successful", command("PASS rahasia"))
    }

    /** Kirim PASV, parse "227 (a,b,c,d,p1,p2)", lalu sambungkan koneksi data. */
    private fun openPassiveData(): Socket {
        val response = command("PASV")
        assertTrue("Respons PASV harus 227: $response", response.startsWith("227"))
        val match =
            Regex("\\((\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)\\)").find(response)
                ?: error("Format PASV tidak dikenali: $response")
        val values = match.groupValues.drop(1)
        val host = "${values[0]}.${values[1]}.${values[2]}.${values[3]}"
        val port = values[4].toInt() * 256 + values[5].toInt()
        return Socket(host, port)
    }

    private fun storeFile(
        name: String,
        content: String,
    ) {
        val data = openPassiveData()
        assertEquals("150 Opening data connection", command("STOR $name"))
        val output = data.getOutputStream()
        output.write(content.toByteArray(Charsets.ISO_8859_1))
        output.flush()
        data.shutdownOutput()
        // Server menutup sisi datanya setelah selesai menulis berkas -> EOF.
        readAll(data)
        assertEquals("226 Transfer complete", reader.readLine())
    }

    private fun retrieveFile(name: String): String {
        val data = openPassiveData()
        assertEquals("150 Opening data connection", command("RETR $name"))
        val content = readAll(data)
        assertEquals("226 Transfer complete", reader.readLine())
        return content
    }

    private fun listNames(): String {
        val data = openPassiveData()
        assertEquals("150 Opening data connection", command("NLST"))
        val listing = readAll(data)
        assertEquals("226 Transfer complete", reader.readLine())
        return listing
    }

    private fun readAll(socket: Socket): String {
        val buffer = ByteArray(8192)
        val collected = ByteArrayOutputStream()
        socket.getInputStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                collected.write(buffer, 0, read)
            }
        }
        return collected.toString("ISO-8859-1")
    }
}
