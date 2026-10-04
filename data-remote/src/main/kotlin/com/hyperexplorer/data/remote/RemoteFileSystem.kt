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

import java.io.File

/**
 * Kontrak operasi berkas pada satu koneksi remote (FTP/SFTP/SMB/WebDAV).
 *
 * Semua [path] bersifat absolut DI DALAM remote, tanpa skema dan tanpa '/' di
 * depan/belakang (root = ""); normalisasi & anti-traversal ditangani oleh
 * [RemotePath]. Koneksi dibuka lazy pada operasi pertama; [close] idempoten.
 */
interface RemoteFileSystem : AutoCloseable {
    /** Daftar entri di [path] (path = koordinat remote). */
    suspend fun list(path: String): List<RemoteEntry>

    /** Buat direktori [name] di dalam [parent]. */
    suspend fun makeDirectory(
        parent: String,
        name: String,
    )

    /** Hapus [path]; [isDirectory] menentukan apakah target direktori. */
    suspend fun delete(
        path: String,
        isDirectory: Boolean,
    )

    /** Ganti nama [oldName] menjadi [newName] di dalam direktori [path]. */
    suspend fun rename(
        path: String,
        oldName: String,
        newName: String,
    )

    /** Unduh [remotePath] ke berkas lokal [target]. [sizeHint] boleh diabaikan implementasi. */
    suspend fun download(
        remotePath: String,
        target: File,
        sizeHint: Long = -1L,
    )

    /** Unggah [local] ke direktori remote [remoteDir] dengan nama berkas lokalnya. */
    suspend fun upload(
        local: File,
        remoteDir: String,
    )

    /** Tutup koneksi (idempoten; operasi berikutnya melempar IllegalStateException). */
    override fun close()
}

/** Factory [RemoteFileSystem] per protokol. */
object RemoteFileSystems {
    /** Bangun klien untuk [connection]; validasi host/port fail-fast. */
    fun connect(connection: RemoteConnection): RemoteFileSystem {
        require(connection.host.isNotBlank()) { "Host is required" }
        require(connection.port in 1..65535) { "Invalid port: ${connection.port}" }
        return when (connection.protocol) {
            RemoteProtocol.FTP -> FtpRemote(connection)
            RemoteProtocol.SFTP -> SftpRemote(connection)
            RemoteProtocol.SMB -> SmbRemote(connection)
            RemoteProtocol.WEBDAV -> WebDavRemote(connection)
            // Fail-fast eksplisit: Nextcloud harus lewat CloudFileSystems.connect
            // (implementasinya ada di modul :data-cloud, bukan di sini).
            RemoteProtocol.NEXTCLOUD ->
                throw IllegalArgumentException(
                    "NEXTCLOUD must be connected via CloudFileSystems.connect (module :data-cloud)",
                )
        }
    }
}
