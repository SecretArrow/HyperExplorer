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

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Klien SMB (SMB2/3) berbasis SMBJ.
 *
 * Koneksi + autentikasi + [DiskShare] dibuka lazy pada operasi pertama dan
 * di-cache. Path operasi memakai koordinat di dalam share ('/'); SMBJ menerima
 * backslash, jadi konversi dilakukan internal setelah [RemotePath.requireSafe]
 * menolak backslash dari pemanggil. Segmen pertama path URI sudah menjadi
 * [RemoteConnection.share]; [RemoteConnection.basePath] menjadi root koordinat.
 */
class SmbRemote(private val connection: RemoteConnection) : RemoteFileSystem {
    @Volatile
    private var closed = false

    @Volatile
    private var client: SMBClient? = null

    @Volatile
    private var session: Session? = null

    @Volatile
    private var diskShare: DiskShare? = null

    private val mutex = Mutex()

    override suspend fun list(path: String): List<RemoteEntry> =
        io("list", path) {
            RemotePath.requireSafe(path)
            val dir = RemotePath.join(connection.basePath, path)
            diskShare().list(dir.replace('/', '\\'))
                .filter { it.fileName != "." && it.fileName != ".." }
                .map { info ->
                    RemoteEntry(
                        name = info.fileName,
                        path = RemotePath.join(dir, info.fileName),
                        isDirectory = (info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L,
                        size = maxOf(0L, info.endOfFile),
                        lastModified = windowsToEpochMillis(info.lastWriteTime.windowsTimeStamp),
                    )
                }
        }

    override suspend fun makeDirectory(
        parent: String,
        name: String,
    ) = io("mkdir", name) {
        RemotePath.requireSafe(parent)
        RemotePath.requireSafe(name)
        val target = toSmb(RemotePath.join(parent, name))
        diskShare().mkdir(target)
    }

    override suspend fun delete(
        path: String,
        isDirectory: Boolean,
    ) = io("delete", path) {
        RemotePath.requireSafe(path)
        val target = toSmb(path)
        val share = diskShare()
        if (isDirectory) share.rmdir(target, true) else share.rm(target)
    }

    override suspend fun rename(
        path: String,
        oldName: String,
        newName: String,
    ) = io("rename", oldName) {
        RemotePath.requireSafe(path)
        RemotePath.requireSafe(oldName)
        RemotePath.requireSafe(newName)
        val from = toSmb(RemotePath.join(path, oldName))
        val to = toSmb(RemotePath.join(path, newName))
        // Rename perlu handle dengan AccessMask.DELETE lalu SET_INFO FileRenameInformation
        // (DiskEntry.rename di SMBJ); jalan paling sederhana yang benar di SMBJ 0.13.
        val entry =
            diskShare().open(
                from,
                setOf(AccessMask.DELETE),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null,
            )
        entry.use { it.rename(to, true) }
    }

    override suspend fun download(
        remotePath: String,
        target: File,
        sizeHint: Long,
    ) = io("download", remotePath) {
        // sizeHint diabaikan: stream dibaca sampai habis.
        RemotePath.requireSafe(remotePath)
        val handle =
            diskShare().openFile(
                toSmb(remotePath),
                setOf(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null,
            )
        target.parentFile?.mkdirs()
        handle.use { file ->
            file.getInputStream().use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output, 64 * 1024)
                }
            }
        }
    }

    override suspend fun upload(
        local: File,
        remoteDir: String,
    ) = io("upload", local.name) {
        RemotePath.requireSafe(remoteDir)
        if (!local.isFile) throw IOException("local file not found: ${local.absolutePath}")
        val handle =
            diskShare().openFile(
                toSmb(RemotePath.join(remoteDir, local.name)),
                setOf(AccessMask.GENERIC_WRITE),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OVERWRITE_IF,
                null,
            )
        handle.use { file ->
            file.getOutputStream().use { output ->
                local.inputStream().use { input ->
                    input.copyTo(output, 64 * 1024)
                }
            }
        }
    }

    override fun close() {
        closed = true
        val share = diskShare
        val sess = session
        val cl = client
        diskShare = null
        session = null
        client = null
        if (share != null) {
            try {
                share.close()
            } catch (_: IOException) {
                // Best effort: koneksi mungkin sudah mati.
            }
        }
        if (sess != null) {
            try {
                sess.close()
            } catch (_: IOException) {
                // Idem.
            }
        }
        if (cl != null) {
            try {
                cl.close()
            } catch (_: IOException) {
                // Idem.
            }
        }
    }

    private fun ensureOpen() {
        if (closed) throw IllegalStateException("Remote file system is closed")
    }

    private suspend fun diskShare(): DiskShare =
        mutex.withLock {
            ensureOpen()
            diskShare ?: connect().also { diskShare = it }
        }

    private fun connect(): DiskShare {
        val shareName = connection.share
        if (shareName.isBlank()) {
            throw IOException("SMB share not set: connect to an smb://host/share URI")
        }
        val newClient = SMBClient()
        try {
            val conn = newClient.connect(connection.host, connection.port)
            val sess = conn.authenticate(authenticationContext())
            client = newClient
            session = sess
            val opened = sess.connectShare(shareName)
            if (opened !is DiskShare) {
                throw IOException("SMB share '$shareName' is not a disk share")
            }
            return opened
        } catch (e: SMBApiException) {
            reset(newClient)
            throw IOException("SMB share not found or access denied: '$shareName' on ${connection.host} (${e.message})", e)
        } catch (e: Exception) {
            reset(newClient)
            throw IOException(
                "SMB connect failed to ${connection.host}:${connection.port} share '$shareName': ${e.message}",
                e,
            )
        }
    }

    private fun authenticationContext(): AuthenticationContext {
        val creds = connection.credentials
        return if (creds.anonymous && creds.username.isBlank()) {
            AuthenticationContext.anonymous()
        } else {
            AuthenticationContext(creds.username, creds.password.toCharArray(), null)
        }
    }

    private fun reset(newClient: SMBClient) {
        diskShare = null
        session = null
        client = null
        try {
            newClient.close()
        } catch (_: IOException) {
            // Best effort saat pembersihan.
        }
    }

    /** Koordinat remote → path dalam share dengan pemisah backslash milik SMB. */
    private fun toSmb(path: String): String = RemotePath.join(connection.basePath, path).replace('/', '\\')

    /**
     * Konversi timestamp Windows (100-nanosecond sejak 1601-01-01) ke epoch millis.
     * Nilai 0/negatif (belum diset) → 0; hasil sebelum epoch Unix dijepit ke 0.
     */
    private fun windowsToEpochMillis(windowsTimestamp: Long): Long {
        if (windowsTimestamp <= 0L) return 0L
        val millis = (windowsTimestamp - WINDOWS_EPOCH_DELTA_100NS) / 10_000L
        return maxOf(0L, millis)
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
                throw IOException("SMB $operation failed for '$target': ${e.message}", e)
            } catch (e: SMBApiException) {
                throw IOException("SMB $operation failed for '$target': ${e.message}", e)
            } catch (e: Exception) {
                throw IOException("SMB $operation failed for '$target': ${e.javaClass.simpleName}: ${e.message}", e)
            }
        }

    private companion object {
        /** Selisih epoch Windows (1601) dan Unix (1970) dalam satuan 100ns. */
        const val WINDOWS_EPOCH_DELTA_100NS = 116444736000000000L
    }
}
