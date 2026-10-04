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
import org.junit.Assert.assertThrows
import org.junit.Test

class RemotePathTest {
    @Test
    fun `normalize strips leading and trailing slashes`() {
        assertEquals("", RemotePath.normalize("/"))
        assertEquals("", RemotePath.normalize("///"))
        assertEquals("pub/files", RemotePath.normalize("/pub/files/"))
        assertEquals("pub/files", RemotePath.normalize("pub/files"))
    }

    @Test
    fun `normalize collapses duplicate separators and dot segments`() {
        assertEquals("a/b", RemotePath.normalize("//a//b//"))
        assertEquals("a/b", RemotePath.normalize("a/./b"))
    }

    @Test
    fun `normalize clamps traversal at root`() {
        assertEquals("a/b", RemotePath.normalize("a/b/../c"))
        assertEquals("x", RemotePath.normalize("a/../../x"))
        assertEquals("", RemotePath.normalize(".."))
        assertEquals("", RemotePath.normalize("a/.."))
    }

    @Test
    fun `join concatenates parent and child`() {
        assertEquals("pub/files", RemotePath.join("pub", "files"))
        assertEquals("files", RemotePath.join("", "files"))
        assertEquals("pub", RemotePath.join("pub", ""))
        assertEquals("a/b/c", RemotePath.join("a/", "/b/c"))
    }

    @Test
    fun `join never escapes root`() {
        assertEquals("a/c", RemotePath.join("a/b", "../c"))
        assertEquals("x", RemotePath.join("a", "../../x"))
        assertEquals("", RemotePath.join("a", ".."))
    }

    @Test
    fun `requireSafe rejects traversal`() {
        assertThrows(IllegalArgumentException::class.java) { RemotePath.requireSafe("..") }
        assertThrows(IllegalArgumentException::class.java) { RemotePath.requireSafe("../etc/passwd") }
        assertThrows(IllegalArgumentException::class.java) { RemotePath.requireSafe("a/../../b") }
        assertThrows(IllegalArgumentException::class.java) { RemotePath.requireSafe("/a/../..") }
    }

    @Test
    fun `requireSafe rejects backslash and NUL`() {
        assertThrows(IllegalArgumentException::class.java) { RemotePath.requireSafe("dir\\file") }
        assertThrows(IllegalArgumentException::class.java) { RemotePath.requireSafe("a\u0000b") }
    }

    @Test
    fun `requireSafe accepts normal paths`() {
        RemotePath.requireSafe("")
        RemotePath.requireSafe("pub/files")
        RemotePath.requireSafe("/pub/files")
        RemotePath.requireSafe("a..b/notes..1")
    }
}
