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

import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.UnknownHostException
import java.util.Calendar
import java.util.Collections
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Server FTP ditulis dari nol (tanpa Apache FtpServer/MINA) dan murni JVM:
 * nol import `android.*` sehingga bisa diuji lewat unit test JVM biasa.
 *
 * Kebijakan keamanan (pelajaran dari port 59777 ES File Explorer yang
 * melayani perintah tanpa autentikasi):
 * - Autentikasi USER/PASS wajib; konfigurasi tanpa kredensial ditolak keras.
 * - ZIP-SLIP GUARD: semua path klien dinormalisasi lalu diverifikasi
 *   canonicalPath-nya tetap di dalam rootDir (CWD/MKD/RMD/DELE/RNFR/RNTO/
 *   RETR/STOR/SIZE/LIST/NLST).
 * - Anti FTP-bounce: alamat pada PORT harus sama dengan host klien kontrol.
 * - Watchdog idle: server berhenti otomatis bila tidak ada koneksi aktif
 *   selama [FtpConfig.idleTimeoutMinutes] menit.
 * - Satu exception tidak boleh mematikan server: ditangkap per perintah
 *   dan per koneksi.
 *
 * Protokol: koneksi kontrol memakai ISO-8859-1 dengan akhiran CRLF;
 * koneksi data dibuka via PASV (ServerSocket sementara) atau PORT.
 */
class FtpServer(
    private val config: FtpConfig,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onStopped: (reason: String?) -> Unit = {},
) {
    @Volatile
    private var running = false

    /** true bila server sedang berjalan dan menerima koneksi. */
    val isRunning: Boolean
        get() = running

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var lastBoundPort = config.port

    @Volatile
    private var scope: CoroutineScope? = null

    @Volatile
    private var rootCanonical: String = config.rootDir.absolutePath

    @Volatile
    private var idleSince = System.currentTimeMillis()

    @Volatile
    private var stopRequested = false

    private val clients: MutableSet<Socket> = Collections.synchronizedSet(LinkedHashSet<Socket>())

    private val lifecycleLock = Any()

    /** Port aktual yang terikat; berguna saat [FtpConfig.port] = 0 (ephemeral). */
    fun boundPort(): Int = lastBoundPort

    /**
     * Menjalankan server: validasi konfigurasi, ikat [ServerSocket] ke semua
     * antarmuka (tujuannya: akses dari PC di LAN), lalu mulai loop penerima
     * koneksi dan pengawas idle.
     *
     * @throws IllegalStateException bila sudah berjalan, kredensial kosong,
     *   port di luar rentang, atau direktori root tidak valid.
     */
    fun start() {
        if (running) {
            throw IllegalStateException("Server FTP sudah berjalan")
        }
        // Anonim dilarang keras: server transfer tanpa auth adalah bencana.
        if (config.username.isBlank() || config.password.isBlank()) {
            throw IllegalStateException("Server FTP wajib memiliki nama pengguna dan kata sandi")
        }
        if (config.port != 0 && (config.port < MIN_PORT || config.port > MAX_PORT)) {
            throw IllegalStateException("Port FTP tidak valid: ${config.port} (gunakan 0 untuk acak, atau 1024-65535)")
        }
        if (!config.rootDir.exists() || !config.rootDir.isDirectory) {
            throw IllegalStateException("Direktori root tidak valid: ${config.rootDir.absolutePath}")
        }
        val socket = try {
            ServerSocket(config.port)
        } catch (e: IOException) {
            throw IllegalStateException("Tidak dapat mengikat port ${config.port}: ${e.message}", e)
        }
        rootCanonical = config.rootDir.canonicalPath
        synchronized(lifecycleLock) { stopRequested = false }
        serverSocket = socket
        lastBoundPort = socket.localPort
        idleSince = System.currentTimeMillis()
        running = true
        val activeScope = CoroutineScope(SupervisorJob() + dispatcher)
        scope = activeScope
        activeScope.launch { acceptLoop(socket) }
        activeScope.launch { idleWatchdog() }
    }

    /** Menghentikan server: tutup socket server DAN semua socket klien aktif. */
    fun stop() {
        shutdown(null)
    }

    private fun shutdown(reason: String?) {
        synchronized(lifecycleLock) {
            if (stopRequested) return
            stopRequested = true
        }
        running = false
        closeQuietly(serverSocket)
        serverSocket = null
        val snapshot: List<Socket> = synchronized(clients) {
            val copy = clients.toList()
            clients.clear()
            copy
        }
        for (socket in snapshot) {
            closeQuietly(socket)
        }
        val activeScope = scope
        scope = null
        activeScope?.cancel()
        // Dipanggil tepat sekali, apa pun penyebab berhentinya (manual/idle/fatal).
        onStopped(reason)
    }

    private fun acceptLoop(socket: ServerSocket) {
        try {
            while (running) {
                val client = socket.accept()
                if (!running) {
                    closeQuietly(client)
                    break
                }
                clients.add(client)
                scope?.launch { handleClient(client) }
            }
        } catch (e: IOException) {
            if (running) {
                // Bukan akibat stop(): kegagalan fatal pada server socket.
                shutdown("Kesalahan pada socket server: ${e.message}")
            }
        }
    }

    private suspend fun idleWatchdog() {
        while (running) {
            delay(IDLE_CHECK_INTERVAL_MILLIS)
            if (!running) break
            val idleMillis = System.currentTimeMillis() - idleSince
            if (shouldAutoStop(clients.size, idleMillis, config.idleTimeoutMinutes)) {
                shutdown(STOP_REASON_IDLE)
            }
        }
    }

    private fun handleClient(socket: Socket) {
        var session: ClientSession? = null
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
            val writer = PrintStream(socket.getOutputStream(), true, "ISO-8859-1")
            writer.print("$GREETING\r\n")
            val active = ClientSession(socket, writer)
            session = active
            while (running) {
                val line = reader.readLine() ?: break
                val response = active.handle(line)
                writer.print("$response\r\n")
                if (active.shouldClose) break
            }
        } catch (_: IOException) {
            // Koneksi terputus — bersihkan di finally.
        } catch (_: Exception) {
            // Jangan pernah biarkan exception satu koneksi membunuh server.
        } finally {
            session?.cleanup()
            clients.remove(socket)
            closeQuietly(socket)
            if (clients.isEmpty()) {
                idleSince = System.currentTimeMillis()
            }
        }
    }

    private fun formatTimestamp(lastModified: Long): String {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = lastModified
        val month = MONTHS[calendar.get(Calendar.MONTH)]
        return String.format(
            Locale.US,
            "%s %02d %02d:%02d",
            month,
            calendar.get(Calendar.DAY_OF_MONTH),
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
        )
    }

    /**
     * State per koneksi: sesi login (auth per koneksi, klien simultan saling
     * independen), direktori aktif, file RNFR tertunda, dan mode koneksi data
     * (PASV atau PORT).
     */
    private inner class ClientSession(
        private val control: Socket,
        private val writer: PrintStream,
    ) {
        private var usernameMatches = false
        private var loggedIn = false
        private var cwd = "/"
        private var pendingRename: File? = null
        private var passiveServer: ServerSocket? = null
        private var portAddress: InetAddress? = null
        private var portNumber = -1

        var shouldClose = false
            private set

        fun cleanup() {
            closeDataConnection(null)
        }

        fun handle(raw: String): String {
            val trimmed = raw.trim()
            val separator = trimmed.indexOf(' ')
            val command = (if (separator < 0) trimmed else trimmed.substring(0, separator)).uppercase(Locale.US)
            val argument = if (separator < 0) null else trimmed.substring(separator + 1).trim().ifEmpty { null }
            if (command.isEmpty()) return "501 Syntax error"
            return try {
                when (command) {
                    "USER" -> handleUser(argument)
                    "PASS" -> handlePass(argument)
                    "QUIT" -> {
                        shouldClose = true
                        "221 Goodbye"
                    }
                    "SYST" -> "215 UNIX Type: L8"
                    "FEAT" -> "211-Features:\r\n UTF8\r\n PASV\r\n211 End"
                    "NOOP" -> "200 NOOP ok"
                    else -> {
                        if (!loggedIn) {
                            "530 Please login with USER and PASS"
                        } else {
                            dispatch(command, argument)
                        }
                    }
                }
            } catch (_: Exception) {
                // Tangkap per perintah: satu kegagalan tidak boleh mematikan server.
                "451 Requested action aborted: local error"
            }
        }

        private fun dispatch(command: String, argument: String?): String = when (command) {
            "TYPE" -> handleType(argument)
            "PWD" -> "257 \"$cwd\" is the current directory"
            "CWD" -> handleCwd(argument)
            "CDUP" -> handleCdup()
            "MKD" -> handleMkd(argument)
            "RMD" -> handleRmd(argument)
            "DELE" -> handleDele(argument)
            "SIZE" -> handleSize(argument)
            "RNFR" -> handleRnfr(argument)
            "RNTO" -> handleRnto(argument)
            "PASV" -> handlePasv()
            "PORT" -> handlePort(argument)
            "LIST" -> handleList(argument, namesOnly = false)
            "NLST" -> handleList(argument, namesOnly = true)
            "RETR" -> handleRetr(argument)
            "STOR" -> handleStore(argument, append = false)
            "APPE" -> handleStore(argument, append = true)
            else -> "502 Command not implemented"
        }

        private fun handleUser(argument: String?): String {
            usernameMatches = argument != null && argument == config.username
            return "331 Password required"
        }

        private fun handlePass(argument: String?): String {
            if (usernameMatches && argument == config.password) {
                loggedIn = true
                return "230 Login successful"
            }
            return "530 Login incorrect"
        }

        private fun handleType(argument: String?): String {
            val parts = argument?.uppercase(Locale.US)?.split(" ")?.filter { it.isNotEmpty() }.orEmpty()
            val type = parts.firstOrNull()
            val second = parts.getOrNull(1)
            val validType = type == "A" || type == "I"
            val validSecond = second == null || second == "N"
            return if (validType && validSecond) "200 Type set" else "501 Syntax error"
        }

        private fun handleCwd(argument: String?): String {
            if (argument == null) return "501 Syntax error"
            val target = safeFile(cwd, argument) ?: return "550 Access denied"
            if (!target.exists() || !target.isDirectory) return "550 Not found"
            cwd = displayPath(target)
            return "250 Directory changed"
        }

        private fun handleCdup(): String {
            cwd = if (cwd == "/") "/" else cwd.substringBeforeLast("/").ifEmpty { "/" }
            return "200 Directory changed"
        }

        private fun handleMkd(argument: String?): String {
            if (argument == null) return "501 Syntax error"
            val target = safeFile(cwd, argument) ?: return "550 Access denied"
            return if (target.mkdir()) "257 Directory created" else "550 Cannot create"
        }

        private fun handleRmd(argument: String?): String {
            if (argument == null) return "501 Syntax error"
            val target = safeFile(cwd, argument) ?: return "550 Access denied"
            if (!target.isDirectory) return "550 Not found"
            if (target.path == rootCanonical) return "550 Directory not empty"
            val entries = target.listFiles()
            if (!entries.isNullOrEmpty()) return "550 Directory not empty"
            return if (target.delete()) "250 Directory removed" else "550 Cannot remove"
        }

        private fun handleDele(argument: String?): String {
            if (argument == null) return "501 Syntax error"
            val target = safeFile(cwd, argument) ?: return "550 Access denied"
            return if (target.isFile && target.delete()) "250 File deleted" else "550 Cannot delete"
        }

        private fun handleSize(argument: String?): String {
            if (argument == null) return "501 Syntax error"
            val target = safeFile(cwd, argument) ?: return "550 Access denied"
            return if (target.isFile) "213 ${target.length()}" else "550 Not found"
        }

        private fun handleRnfr(argument: String?): String {
            if (argument == null) return "501 Syntax error"
            val target = safeFile(cwd, argument) ?: return "550 Access denied"
            if (!target.exists()) return "550 Not found"
            pendingRename = target
            return "350 Ready for RNTO"
        }

        private fun handleRnto(argument: String?): String {
            if (argument == null) return "501 Syntax error"
            val source = pendingRename ?: return "503 Bad sequence of commands"
            val target = safeFile(cwd, argument) ?: return "550 Access denied"
            pendingRename = null
            return if (source.renameTo(target)) "250 Rename successful" else "550 Cannot rename"
        }

        private fun handlePasv(): String {
            closeDataConnection(null)
            val server = try {
                ServerSocket(0)
            } catch (_: IOException) {
                return "425 Cannot open data connection"
            }
            passiveServer = server
            val octets = passiveAddress().address
            val port = server.localPort
            val host =
                "${octets[0].toInt() and 0xFF},${octets[1].toInt() and 0xFF},${octets[2].toInt() and 0xFF},${octets[3].toInt() and 0xFF}"
            return "227 Entering Passive Mode ($host,${port / 256},${port % 256})"
        }

        private fun handlePort(argument: String?): String {
            val numbers = argument?.split(",")?.map { it.trim().toIntOrNull() }.orEmpty()
            val valid = numbers.size == 6 && numbers.all { it != null && it >= 0 && it <= 255 }
            if (!valid) return "501 Syntax error"
            val addressBytes = byteArrayOf(
                numbers[0]!!.toByte(),
                numbers[1]!!.toByte(),
                numbers[2]!!.toByte(),
                numbers[3]!!.toByte(),
            )
            val targetAddress = try {
                InetAddress.getByAddress(addressBytes)
            } catch (_: UnknownHostException) {
                return "501 Syntax error"
            }
            // Anti FTP-bounce: alamat PORT harus sama dengan host klien kontrol.
            val peer = control.inetAddress
            if (peer != null && peer != targetAddress) return "501 Syntax error"
            closeDataConnection(null)
            portAddress = targetAddress
            portNumber = numbers[4]!! * 256 + numbers[5]!!
            return "200 PORT command successful"
        }

        private fun handleList(argument: String?, namesOnly: Boolean): String {
            // Klien umum mengirim flag seperti "-la": abaikan flag, tampilkan folder aktif.
            val pathArgument = argument?.takeIf { !it.startsWith("-") }
            val target = safeFile(cwd, pathArgument ?: ".") ?: return "550 Access denied"
            if (!target.exists()) return "550 Not found"
            writer.print("150 Opening data connection\r\n")
            val data = openData() ?: return "425 Cannot open data connection"
            try {
                data.getOutputStream().use { output ->
                    val entries: List<File> = when {
                        target.isDirectory ->
                            target.listFiles()?.sortedBy { it.name.lowercase(Locale.US) } ?: emptyList()
                        else -> listOf(target)
                    }
                    val listing = StringBuilder()
                    for (entry in entries) {
                        if (namesOnly) {
                            listing.append(entry.name).append("\r\n")
                        } else {
                            listing.append(listLine(entry)).append("\r\n")
                        }
                    }
                    output.write(listing.toString().toByteArray(Charsets.ISO_8859_1))
                    output.flush()
                }
            } catch (_: IOException) {
                return "426 Connection closed; transfer aborted"
            } finally {
                closeDataConnection(data)
            }
            return "226 Transfer complete"
        }

        private fun listLine(entry: File): String {
            val mode = if (entry.isDirectory) "drwxr-xr--" else "-rw-r--r--"
            return "$mode 1 owner group ${entry.length()} ${formatTimestamp(entry.lastModified())} ${entry.name}"
        }

        private fun handleRetr(argument: String?): String {
            if (argument == null) return "501 Syntax error"
            val target = safeFile(cwd, argument) ?: return "550 Access denied"
            if (!target.isFile) return "550 Not found"
            writer.print("150 Opening data connection\r\n")
            val data = openData() ?: return "425 Cannot open data connection"
            try {
                data.getOutputStream().use { output ->
                    target.inputStream().use { input ->
                        input.copyTo(output, TRANSFER_BUFFER_SIZE)
                    }
                    output.flush()
                }
            } catch (_: IOException) {
                return "426 Connection closed; transfer aborted"
            } finally {
                closeDataConnection(data)
            }
            return "226 Transfer complete"
        }

        private fun handleStore(argument: String?, append: Boolean): String {
            if (argument == null) return "501 Syntax error"
            val target = safeFile(cwd, argument) ?: return "550 Access denied"
            if (target.isDirectory) return "550 Not found"
            writer.print("150 Opening data connection\r\n")
            val data = openData() ?: return "425 Cannot open data connection"
            try {
                FileOutputStream(target, append).use { output ->
                    data.getInputStream().use { input ->
                        input.copyTo(output, TRANSFER_BUFFER_SIZE)
                    }
                    output.flush()
                }
            } catch (_: IOException) {
                return "426 Connection closed; transfer aborted"
            } finally {
                closeDataConnection(data)
            }
            return "226 Transfer complete"
        }

        /** Buka koneksi data sesuai mode sesi (PASV diutamakan); null bila gagal. */
        private fun openData(): Socket? = try {
            val passive = passiveServer
            val address = portAddress
            val number = portNumber
            when {
                passive != null -> {
                    passive.soTimeout = DATA_TIMEOUT_MILLIS
                    passive.accept()
                }
                address != null && number > 0 -> {
                    val socket = Socket()
                    socket.connect(InetSocketAddress(address, number), DATA_TIMEOUT_MILLIS)
                    socket
                }
                else -> null
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

        private fun closeDataConnection(dataSocket: Socket?) {
            closeQuietly(dataSocket)
            closeQuietly(passiveServer)
            passiveServer = null
            portAddress = null
            portNumber = -1
        }

        /**
         * Alamat yang diiklankan pada respons PASV: alamat lokal koneksi
         * kontrol (IP LAN perangkat di Android, loopback di JVM test);
         * fallback ke getLocalHost() bila tidak memungkinkan.
         */
        private fun passiveAddress(): InetAddress {
            val fromControl = control.localAddress
            if (!fromControl.isAnyLocalAddress && fromControl.address.size == 4) return fromControl
            return try {
                val localhost = InetAddress.getLocalHost()
                if (localhost.address.size == 4) localhost else InetAddress.getLoopbackAddress()
            } catch (_: UnknownHostException) {
                InetAddress.getLoopbackAddress()
            }
        }

        /**
         * ZIP-SLIP GUARD: gabungkan cwd + argumen, normalisasi, lalu pastikan
         * canonicalPath tetap di dalam rootDir. Null berarti di luar root
         * ("550 Access denied").
         */
        private fun safeFile(current: String, argument: String): File? {
            val relative = relativePath(current, argument) ?: return null
            val candidate = File(rootDir, relative)
            return try {
                val canonical = candidate.canonicalPath
                if (canonical == rootCanonical || canonical.startsWith(rootCanonical + File.separator)) {
                    File(canonical)
                } else {
                    null
                }
            } catch (_: IOException) {
                null
            }
        }

        /**
         * Normalisasi chroot: ".." yang keluar dari root DITOLAK (null),
         * bukan di-clamp, agar pelanggaran terlihat jelas.
         */
        private fun relativePath(current: String, argument: String): String? {
            val combined = if (argument.startsWith("/")) argument else current.trimEnd('/') + "/" + argument
            val segments = ArrayDeque<String>()
            for (segment in combined.split("/")) {
                when (segment) {
                    "", "." -> Unit
                    ".." -> {
                        if (segments.isEmpty()) return null
                        segments.removeLast()
                    }
                    else -> segments.addLast(segment)
                }
            }
            return if (segments.isEmpty()) "" else segments.joinToString("/", prefix = "/")
        }

        private fun displayPath(file: File): String {
            val canonical = try {
                file.canonicalPath
            } catch (_: IOException) {
                return "/"
            }
            return if (canonical == rootCanonical) "/" else "/" + canonical.removePrefix(rootCanonical + File.separator)
        }
    }

    companion object {
        internal const val STOP_REASON_IDLE = "idle"
        private const val GREETING = "220 Hyper Explorer FTP server ready"
        private const val MIN_PORT = 1024
        private const val MAX_PORT = 65535
        private const val IDLE_CHECK_INTERVAL_MILLIS = 30_000L
        private const val DATA_TIMEOUT_MILLIS = 30_000
        private const val TRANSFER_BUFFER_SIZE = 8192

        private val MONTHS = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

        /**
         * Keputusan murni pengawas idle, dipisah agar mudah dites tanpa
         * timing: berhenti otomatis hanya bila tidak ada koneksi aktif DAN
         * sudah diam setidaknya [timeoutMinutes] menit (timeout <= 0 berarti
         * berhenti segera saat idle).
         */
        internal fun shouldAutoStop(connectionCount: Int, idleMillis: Long, timeoutMinutes: Int): Boolean {
            if (connectionCount > 0) return false
            if (timeoutMinutes <= 0) return true
            return idleMillis >= timeoutMinutes * 60_000L
        }
    }
}

private fun closeQuietly(closeable: Closeable?) {
    if (closeable == null) return
    try {
        closeable.close()
    } catch (_: IOException) {
        // Tutup diam-diam.
    }
}
