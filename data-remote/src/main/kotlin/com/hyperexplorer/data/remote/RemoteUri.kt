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

import java.net.URI
import java.net.URISyntaxException

/** Util URI remote: parse string URI menjadi [RemoteConnection] dan sebaliknya (tampilan). */
object RemoteUri {
    private val protocolsByScheme = RemoteProtocol.entries.associateBy { it.scheme }

    /**
     * Parse "smb://host/share/dir", "ftp://user:pass@host:port/dir", "sftp://host/dir",
     * atau "dav://host/basepath". Port default diambil dari [RemoteProtocol]; kredensial
     * dari userinfo bila ada (menandai non-anonim). Melempar [IllegalArgumentException]
     * bila URI tidak valid.
     */
    fun parse(raw: String): RemoteConnection {
        val trimmed = raw.trim()
        val uri = try {
            URI(trimmed)
        } catch (e: URISyntaxException) {
            throw IllegalArgumentException("Invalid remote URI: $trimmed", e)
        }
        val scheme = uri.scheme?.lowercase()
        val protocol = protocolsByScheme[scheme]
            ?: throw IllegalArgumentException("Unsupported scheme '$scheme' in remote URI: $trimmed")
        val host = uri.host?.takeUnless { it.isEmpty() }
            ?: throw IllegalArgumentException("Missing host in remote URI: $trimmed")
        if (uri.port < -1 || uri.port > 65535) {
            throw IllegalArgumentException("Invalid port in remote URI: $trimmed")
        }
        val segments = (uri.path ?: "").trim('/')
        val (share, basePath) = when (protocol) {
            RemoteProtocol.SMB -> segments.substringBefore('/') to segments.substringAfter('/', "")
            RemoteProtocol.WEBDAV -> segments to ""
            RemoteProtocol.FTP, RemoteProtocol.SFTP -> "" to segments
        }
        val userInfo = uri.userInfo
        val credentials = if (userInfo == null) {
            RemoteCredentials()
        } else {
            RemoteCredentials(
                username = userInfo.substringBefore(':'),
                password = userInfo.substringAfter(':', ""),
                anonymous = false,
            )
        }
        return RemoteConnection(
            protocol = protocol,
            host = host,
            port = if (uri.port == -1) protocol.defaultPort else uri.port,
            share = share,
            basePath = basePath,
            credentials = credentials,
            displayName = "",
        )
    }

    /**
     * Tampilan URI yang rapi tanpa kredensial, mis. "smb://host/share/dir".
     * Port hanya ditampilkan bila bukan port default protokolnya.
     */
    fun toDisplayString(connection: RemoteConnection): String {
        val builder = StringBuilder(connection.protocol.scheme).append("://").append(connection.host)
        if (connection.port != connection.protocol.defaultPort) {
            builder.append(':').append(connection.port)
        }
        val pathSegments = when (connection.protocol) {
            RemoteProtocol.SMB -> listOf(connection.share, connection.basePath)
            RemoteProtocol.WEBDAV -> listOf(connection.share)
            RemoteProtocol.FTP, RemoteProtocol.SFTP -> listOf(connection.basePath)
        }.filter { it.isNotEmpty() }
        for (segment in pathSegments) {
            builder.append('/').append(segment)
        }
        return builder.toString()
    }
}
