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

/** Satu entri hasil listing remote. [path] memakai koordinat remote (tanpa skema, tanpa '/' di depan). */
data class RemoteEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
)

/**
 * Kredensial remote. [anonymous] = true berarti klien memakai login anonim/default
 * dari protokol masing-masing (mis. FTP "anonymous").
 */
data class RemoteCredentials(
    val username: String = "",
    val password: String = "",
    val anonymous: Boolean = true,
)

/** Protokol remote yang didukung beserta port defaultnya. */
enum class RemoteProtocol(
    val defaultPort: Int,
    val scheme: String,
) {
    FTP(21, "ftp"),
    SFTP(22, "sftp"),
    SMB(445, "smb"),
    WEBDAV(80, "dav"),

    /**
     * Nextcloud: WebDAV di "/remote.php/dav/files/<username>" + Login flow v2.
     * Entri enum DITAMBAH DI AKHIR agar indeks tersimpan di penyimpanan lama
     * (ConnectionStore protokolIndex) tetap valid.
     */
    NEXTCLOUD(443, "nc"),
}

/**
 * Definisi koneksi remote.
 *
 * Semantik [share] per protokol: SMB = nama share; WebDAV = base path server
 * (mis. "remote.php/webdav"); FTP/SFTP/NEXTCLOUD = "" (tidak dipakai).
 * [basePath] = sub-path awal opsional yang menjadi root koordinat path operasi;
 * untuk NEXTCLOUD = prefix path server (mis. "nextcloud" pada
 * "https://host/nextcloud"), boleh kosong.
 * [secure] = pakai TLS (https); dipakai protokol NEXTCLOUD, protokol lain
 * mengabaikannya.
 */
data class RemoteConnection(
    val id: Long = 0L,
    val protocol: RemoteProtocol,
    val host: String,
    val port: Int,
    val share: String = "",
    val basePath: String = "",
    val credentials: RemoteCredentials = RemoteCredentials(),
    val displayName: String = "",
    val secure: Boolean = false,
)
