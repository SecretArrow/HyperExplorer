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
import com.hyperexplorer.data.remote.RemoteFileSystem
import com.hyperexplorer.data.remote.RemoteFileSystems
import com.hyperexplorer.data.remote.RemoteProtocol

/**
 * Factory [RemoteFileSystem] tingkat aplikasi: menangani protokol cloud
 * (NEXTCLOUD) sendiri dan mendelegasikan protokol jaringan klasik
 * (FTP/SFTP/SMB/WebDAV) ke [RemoteFileSystems].
 *
 * Lapisan UI memakai object ini — bukan [RemoteFileSystems] langsung — agar
 * penambahan protokol cloud tidak mengubah kode pemanggil.
 */
object CloudFileSystems {
    /**
     * Bangun klien untuk [connection]; validasi host/port fail-fast.
     *
     * - NEXTCLOUD → [NextcloudRemote] (WebDAV "/remote.php/dav/files/<username>").
     * - Protokol lain → [RemoteFileSystems.connect] (validasi sama diterapkan).
     *
     * Melempar [IllegalArgumentException] bila host blank, port di luar
     * 1..65535, atau protokol NEXTCLOUD tanpa username (fail-fast di dalam
     * [NextcloudRemote] pada operasi pertama).
     */
    fun connect(connection: RemoteConnection): RemoteFileSystem {
        require(connection.host.isNotBlank()) { "Host is required" }
        require(connection.port in 1..65535) { "Invalid port: ${connection.port}" }
        return when (connection.protocol) {
            RemoteProtocol.NEXTCLOUD -> NextcloudRemote(connection)
            RemoteProtocol.FTP,
            RemoteProtocol.SFTP,
            RemoteProtocol.SMB,
            RemoteProtocol.WEBDAV,
            -> RemoteFileSystems.connect(connection)
        }
    }
}
