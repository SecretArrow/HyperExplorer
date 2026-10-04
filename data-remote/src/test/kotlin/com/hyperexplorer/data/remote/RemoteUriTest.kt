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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteUriTest {
    @Test
    fun `parse smb uri splits share and base path`() {
        val connection = RemoteUri.parse("smb://server.lan/share/dir/x")
        assertEquals(RemoteProtocol.SMB, connection.protocol)
        assertEquals("server.lan", connection.host)
        assertEquals(445, connection.port)
        assertEquals("share", connection.share)
        assertEquals("dir/x", connection.basePath)
        assertEquals(0L, connection.id)
        assertEquals("", connection.displayName)
        assertTrue(connection.credentials.anonymous)
    }

    @Test
    fun `parse ftp uri with userinfo and custom port`() {
        val connection = RemoteUri.parse("ftp://user:p%40ss@host:2121/pub")
        assertEquals(RemoteProtocol.FTP, connection.protocol)
        assertEquals("host", connection.host)
        assertEquals(2121, connection.port)
        assertEquals("", connection.share)
        assertEquals("pub", connection.basePath)
        assertEquals("user", connection.credentials.username)
        assertEquals("p@ss", connection.credentials.password)
        assertFalse(connection.credentials.anonymous)
    }

    @Test
    fun `parse uses default port per protocol`() {
        assertEquals(21, RemoteUri.parse("ftp://host").port)
        assertEquals(22, RemoteUri.parse("sftp://host").port)
        assertEquals(445, RemoteUri.parse("smb://host/share").port)
        assertEquals(80, RemoteUri.parse("dav://host/base").port)
    }

    @Test
    fun `parse sftp uri without path has empty base path`() {
        val connection = RemoteUri.parse("sftp://host")
        assertEquals(RemoteProtocol.SFTP, connection.protocol)
        assertEquals("", connection.basePath)
        assertEquals("", connection.share)
        assertTrue(connection.credentials.anonymous)
    }

    @Test
    fun `parse dav uri puts whole base path in share`() {
        val connection = RemoteUri.parse("dav://nas.local/remote.php/webdav")
        assertEquals(RemoteProtocol.WEBDAV, connection.protocol)
        assertEquals("nas.local", connection.host)
        assertEquals("remote.php/webdav", connection.share)
        assertEquals("", connection.basePath)
    }

    @Test
    fun `parse rejects invalid remote uris`() {
        assertThrows(IllegalArgumentException::class.java) { RemoteUri.parse("nonsense") }
        assertThrows(IllegalArgumentException::class.java) { RemoteUri.parse("http://host/x") }
        assertThrows(IllegalArgumentException::class.java) { RemoteUri.parse("smb://host:abc/x") }
        assertThrows(IllegalArgumentException::class.java) { RemoteUri.parse("ftp://") }
        assertThrows(IllegalArgumentException::class.java) { RemoteUri.parse("") }
    }

    @Test
    fun `toDisplayString omits credentials and default port`() {
        val ftp = RemoteUri.parse("ftp://user:secret@host:21/pub/files")
        assertEquals("ftp://host/pub/files", RemoteUri.toDisplayString(ftp))
    }

    @Test
    fun `toDisplayString keeps custom port and path segments`() {
        val smb = RemoteUri.parse("smb://host/share/dir")
        assertEquals("smb://host/share/dir", RemoteUri.toDisplayString(smb))
        val dav = RemoteUri.parse("dav://host:8080/base")
        assertEquals("dav://host:8080/base", RemoteUri.toDisplayString(dav))
        val sftp = RemoteUri.parse("sftp://host:2222/docs")
        assertEquals("sftp://host:2222/docs", RemoteUri.toDisplayString(sftp))
    }

    @Test
    fun `toDisplayString is stable after reparse`() {
        val display = RemoteUri.toDisplayString(RemoteUri.parse("smb://host/share/dir"))
        assertEquals(display, RemoteUri.toDisplayString(RemoteUri.parse(display)))
    }
}
