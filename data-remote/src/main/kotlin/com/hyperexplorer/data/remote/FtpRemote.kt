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

package com.hyperexplorer.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import java.io.File
import java.io.IOException

/**
 * Klien FTP berbasis commons-net.
 *
 * Koneksi kontrol dibuka lazy pada operasi pertama (passive mode, transfer BINARY,
 * timeout 30 detik). Path operasi diubah menjadi absolut server ("/" + koordinat
 * remote); root koneksi = basePath koneksi, atau root server bila kosong.
 */
class FtpRemote(
    private val connection: RemoteConnection,
) : RemoteFileSystem {
    @Volatile
    private var closed = false

    @Volatile
    private var client: FTPClient? = null

    private val mutex = Mutex()

    override suspend fun list(path: String): List<RemoteEntry> =
        io("list", path) {
            RemotePath.requireSafe(path)
            val ftp = control()
            val dir = absolute(path)
            val files =
                ftp.listFiles(dir)
                    ?: throw IOException("listing unavailable${ftp.replyDetail()}")
            files
                .filterNotNull()
                .filter { it.name != "." && it.name != ".." }
                .map {
                    RemoteEntry(
                        name = it.name,
                        path = RemotePath.join(dir, it.name),
                        isDirectory = it.isDirectory,
                        size = maxOf(0L, it.size),
                        lastModified = it.timestamp?.timeInMillis ?: 0L,
                    )
                }
        }

    override suspend fun makeDirectory(
        parent: String,
        name: String,
    ) = io("mkdir", name) {
        RemotePath.requireSafe(parent)
        RemotePath.requireSafe(name)
        val ftp = control()
        val target = absolute(RemotePath.join(parent, name))
        if (!ftp.makeDirectory(target)) {
            throw IOException("rejected by server${ftp.replyDetail()}")
        }
    }

    override suspend fun delete(
        path: String,
        isDirectory: Boolean,
    ) = io("delete", path) {
        RemotePath.requireSafe(path)
        val ftp = control()
        val target = absolute(path)
        val ok = if (isDirectory) ftp.removeDirectory(target) else ftp.deleteFile(target)
        if (!ok) {
            throw IOException("rejected by server${ftp.replyDetail()}")
        }
    }

    override suspend fun rename(
        path: String,
        oldName: String,
        newName: String,
    ) = io("rename", oldName) {
        RemotePath.requireSafe(path)
        RemotePath.requireSafe(oldName)
        RemotePath.requireSafe(newName)
        val ftp = control()
        val from = absolute(RemotePath.join(path, oldName))
        val to = absolute(RemotePath.join(path, newName))
        if (!ftp.rename(from, to)) {
            throw IOException("rejected by server${ftp.replyDetail()}")
        }
    }

    override suspend fun download(
        remotePath: String,
        target: File,
        sizeHint: Long,
    ) = io("download", remotePath) {
        // sizeHint diabaikan: ukuran diambil dari transfer itu sendiri.
        RemotePath.requireSafe(remotePath)
        val ftp = control()
        val source = absolute(remotePath)
        target.parentFile?.mkdirs()
        target.outputStream().use { output ->
            if (!ftp.retrieveFile(source, output)) {
                throw IOException("rejected by server${ftp.replyDetail()}")
            }
        }
    }

    override suspend fun upload(
        local: File,
        remoteDir: String,
    ) = io("upload", local.name) {
        RemotePath.requireSafe(remoteDir)
        if (!local.isFile) throw IOException("local file not found: ${local.absolutePath}")
        val ftp = control()
        val target = absolute(RemotePath.join(remoteDir, local.name))
        local.inputStream().use { input ->
            if (!ftp.storeFile(target, input)) {
                throw IOException("rejected by server${ftp.replyDetail()}")
            }
        }
    }

    override fun close() {
        closed = true
        val existing = client
        client = null
        if (existing != null) {
            try {
                existing.logout()
            } catch (_: IOException) {
                // Best effort: server mungkin sudah terputus.
            }
            try {
                existing.disconnect()
            } catch (_: IOException) {
                // Idem.
            }
        }
    }

    private fun ensureOpen() {
        if (closed) throw IllegalStateException("Remote file system is closed")
    }

    private suspend fun control(): FTPClient =
        mutex.withLock {
            ensureOpen()
            client ?: openControl().also { client = it }
        }

    private fun openControl(): FTPClient {
        val ftp = FTPClient()
        ftp.connectTimeout = 30_000
        ftp.defaultTimeout = 30_000
        ftp.soTimeout = 30_000
        ftp.controlEncoding = "UTF-8"
        ftp.connect(connection.host, connection.port)
        if (!FTPReply.isPositiveCompletion(ftp.replyCode)) {
            ftp.disconnect()
            throw IOException("server refused connection${ftp.replyDetail()}")
        }
        val creds = connection.credentials
        val username = if (creds.anonymous) ANONYMOUS_USER else creds.username
        val password = if (creds.anonymous) ANONYMOUS_PASSWORD else creds.password
        if (username.isBlank()) {
            ftp.disconnect()
            throw IOException("username required for non-anonymous login")
        }
        if (!ftp.login(username, password)) {
            ftp.disconnect()
            throw IOException("login rejected for user '$username'${ftp.replyDetail()}")
        }
        ftp.enterLocalPassiveMode()
        ftp.setFileType(FTP.BINARY_FILE_TYPE)
        return ftp
    }

    private fun absolute(path: String): String = "/" + RemotePath.join(connection.basePath, path)

    private fun FTPClient.replyDetail(): String {
        val reply = replyString?.trim()
        return if (reply.isNullOrEmpty()) " (reply $replyCode)" else " (reply $replyCode: $reply)"
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
                throw IOException("FTP $operation failed for '$target': ${e.message}", e)
            } catch (e: Exception) {
                throw IOException("FTP $operation failed for '$target': ${e.javaClass.simpleName}: ${e.message}", e)
            }
        }

    private companion object {
        const val ANONYMOUS_USER = "anonymous"
        const val ANONYMOUS_PASSWORD = "hyper@"
    }
}
