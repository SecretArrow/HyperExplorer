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

package com.hyperexplorer.feature.network

import com.hyperexplorer.data.remote.RemoteProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit test JVM murni untuk validasi form sambungan.
 * ConnectionStore (EncryptedSharedPreferences/org.json) sengaja tidak diuji di sini
 * karena membutuhkan framework Android.
 */
class NetworkFormValidationTest {
    @Test
    fun `empty or blank host is rejected`() {
        for (host in listOf("", " ", "\t")) {
            val errors = NetworkFormValidator.validate(host = host, port = "22")
            assertTrue("host '$host'", NetworkFormValidator.ValidationError.EMPTY_HOST in errors)
        }
    }

    @Test
    fun `port outside 1 until 65535 is rejected`() {
        for (port in listOf("", "abc", "0", "-1", "65536", "999999", " 22x")) {
            val errors = NetworkFormValidator.validate(host = "server", port = port)
            assertTrue("port '$port'", NetworkFormValidator.ValidationError.INVALID_PORT in errors)
        }
    }

    @Test
    fun `boundary ports 1 and 65535 are accepted`() {
        assertTrue(NetworkFormValidator.validate("server", "1").isEmpty())
        assertTrue(NetworkFormValidator.validate("server", "65535").isEmpty())
    }

    @Test
    fun `valid form has no errors`() {
        assertTrue(NetworkFormValidator.validate("192.168.1.10", "445").isEmpty())
        assertTrue(NetworkFormValidator.validate("server.local", "21").isEmpty())
    }

    @Test
    fun `port text default matches protocol default port`() {
        assertEquals("21", NetworkFormState.defaultPortText(RemoteProtocol.FTP))
        assertEquals("22", NetworkFormState.defaultPortText(RemoteProtocol.SFTP))
        assertEquals("445", NetworkFormState.defaultPortText(RemoteProtocol.SMB))
        assertEquals("80", NetworkFormState.defaultPortText(RemoteProtocol.WEBDAV))
    }

    @Test
    fun `new form state defaults to first protocol and its default port`() {
        val form = NetworkFormState()
        assertEquals(RemoteProtocol.FTP, form.protocol)
        assertEquals(RemoteProtocol.FTP.defaultPort, form.port.trim().toIntOrNull())
    }
}
