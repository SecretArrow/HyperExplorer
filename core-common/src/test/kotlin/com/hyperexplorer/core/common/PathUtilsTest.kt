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

package com.hyperexplorer.core.common

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PathUtilsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `sanitize removes separators`() {
        assertEquals("a_b_c", PathUtils.sanitizeName("a/b\\c"))
        assertEquals("nama", PathUtils.sanitizeName("  nama "))
        assertEquals("untitled", PathUtils.sanitizeName(""))
    }

    @Test
    fun `unique name returns same name when absent`() {
        val dir = tmp.newFolder()
        assertEquals("file.txt", PathUtils.uniqueName(dir, "file.txt"))
    }

    @Test
    fun `unique name appends counter`() {
        val dir = tmp.newFolder()
        File(dir, "file.txt").createNewFile()
        File(dir, "file (1).txt").createNewFile()
        assertEquals("file (2).txt", PathUtils.uniqueName(dir, "file.txt"))
    }

    @Test
    fun `unique name handles name without extension`() {
        val dir = tmp.newFolder()
        File(dir, "folder").mkdir()
        assertEquals("folder (1)", PathUtils.uniqueName(dir, "folder"))
    }

    @Test
    fun `parent name extracts direct parent`() {
        assertEquals("Download", PathUtils.parentName("/storage/emulated/0/Download/a.txt"))
    }
}
