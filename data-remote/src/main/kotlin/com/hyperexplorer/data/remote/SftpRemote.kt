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
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.io.File
import java.io.IOException

/**
 * Klien SFTP berbasis sshj.
 *
 * PERINGATAN KEAMANAN: host key server diverifikasi dengan [PromiscuousVerifier],
 * artinya TIDAK ADA verifikasi host key — koneksi rentan serangan
 * man-in-the-middle (penyadap bisa menyamar sebagai server). Keputusan ini
 * didokumentasikan agar koneksi pertama ke server baru tidak menolak; jangan
 * dipakai pada jaringan yang tidak dipercaya.
 * TODO(security): pin host key per koneksi dari known_hosts pengguna dan
 *  ganti verifier ini dengan pemeriksaan fingerprint tersimpan.
 *
 * Koneksi SSH dibuka lazy pada operasi pertama; kanal SFTP dibuka-tutup per
 * operasi. Login kata sandi hanya bila kredensial non-anonim; kredensial anonim
 * bergantung pada server yang mengizinkan auth "none".
 */
class SftpRemote(
    private val connection: RemoteConnection,
) : RemoteFileSystem {
    @Volatile
    private var closed = false

    @Volatile
    private var ssh: SSHClient? = null

    private val mutex = Mutex()

    override suspend fun list(path: String): List<RemoteEntry> =
        io("list", path) {
            RemotePath.requireSafe(path)
            val dir = absolute(path)
            sftp().use { channel ->
                channel.ls(dir).map {
                    RemoteEntry(
                        name = it.name,
                        path = RemotePath.join(dir, it.name),
                        isDirectory = it.isDirectory,
                        size = maxOf(0L, it.attributes.size),
                        lastModified = maxOf(0L, it.attributes.mtime) * 1000L,
                    )
                }
            }
        }

    override suspend fun makeDirectory(
        parent: String,
        name: String,
    ) = io("mkdir", name) {
        RemotePath.requireSafe(parent)
        RemotePath.requireSafe(name)
        val target = absolute(RemotePath.join(parent, name))
        sftp().use { it.mkdir(target) }
    }

    override suspend fun delete(
        path: String,
        isDirectory: Boolean,
    ) = io("delete", path) {
        RemotePath.requireSafe(path)
        val target = absolute(path)
        sftp().use { channel ->
            if (isDirectory) channel.rmdir(target) else channel.rm(target)
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
        val from = absolute(RemotePath.join(path, oldName))
        val to = absolute(RemotePath.join(path, newName))
        sftp().use { it.rename(from, to) }
    }

    override suspend fun download(
        remotePath: String,
        target: File,
        sizeHint: Long,
    ) = io("download", remotePath) {
        // sizeHint diabaikan: sshj menulis sampai transfer selesai.
        RemotePath.requireSafe(remotePath)
        target.parentFile?.mkdirs()
        val source = absolute(remotePath)
        sftp().use { it.get(source, target.absolutePath) }
    }

    override suspend fun upload(
        local: File,
        remoteDir: String,
    ) = io("upload", local.name) {
        RemotePath.requireSafe(remoteDir)
        if (!local.isFile) throw IOException("local file not found: ${local.absolutePath}")
        val target = absolute(RemotePath.join(remoteDir, local.name))
        sftp().use { it.put(local.absolutePath, target) }
    }

    override fun close() {
        closed = true
        val existing = ssh
        ssh = null
        if (existing != null) {
            try {
                existing.disconnect()
            } catch (_: IOException) {
                // Best effort: koneksi mungkin sudah mati.
            }
        }
    }

    private fun ensureOpen() {
        if (closed) throw IllegalStateException("Remote file system is closed")
    }

    private suspend fun sftp(): SFTPClient =
        mutex.withLock {
            ensureOpen()
            val existing = ssh ?: connect().also { ssh = it }
            existing.newSFTPClient()
        }

    private fun connect(): SSHClient {
        val client = SSHClient()
        // Lihat KDoc kelas: verifier promiscuous adalah kompromi keamanan terdokumentasi.
        client.addHostKeyVerifier(PromiscuousVerifier())
        client.setConnectTimeout(30_000)
        client.setTimeout(30_000)
        client.connect(connection.host, connection.port)
        try {
            val creds = connection.credentials
            val hasCredentials = !creds.anonymous || creds.username.isNotBlank()
            if (hasCredentials) {
                if (creds.username.isBlank()) {
                    throw IOException("username required for non-anonymous login")
                }
                client.authPassword(creds.username, creds.password)
            }
            return client
        } catch (e: IOException) {
            try {
                client.disconnect()
            } catch (_: IOException) {
                // Best effort saat pembersihan.
            }
            throw e
        }
    }

    private fun absolute(path: String): String = "/" + RemotePath.join(connection.basePath, path)

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
                throw IOException("SFTP $operation failed for '$target': ${e.message}", e)
            } catch (e: Exception) {
                throw IOException("SFTP $operation failed for '$target': ${e.javaClass.simpleName}: ${e.message}", e)
            }
        }
}
