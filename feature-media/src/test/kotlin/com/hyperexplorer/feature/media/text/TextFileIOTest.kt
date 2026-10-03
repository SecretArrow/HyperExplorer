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

package com.hyperexplorer.feature.media.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TextFileIOTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `write then read roundtrip preserves content`() {
        val file = temp.newFile("notes.txt")
        val content = "Halo dunia\nBaris kedua ✓ — tanda baca non-ASCII"

        val result = TextFileIO.write(file, content)
        val loaded = TextFileIO.read(file)

        assertTrue(result.isSuccess)
        assertEquals(Result.success(Unit), result)
        assertFalse(loaded.truncated)
        assertEquals(content, loaded.text)
    }

    @Test
    fun `read empty file returns blank content without truncation`() {
        val file = temp.newFile("kosong.txt")

        val loaded = TextFileIO.read(file)

        assertEquals("", loaded.text)
        assertFalse(loaded.truncated)
    }

    @Test
    fun `read file larger than limit is truncated to limit`() {
        val file = temp.newFile("besar.log")
        file.writeText("a".repeat(TextFileIO.MAX_READ_BYTES.toInt() + 500))

        val loaded = TextFileIO.read(file)

        assertTrue(loaded.truncated)
        assertEquals(TextFileIO.MAX_READ_BYTES.toInt(), loaded.text.length)
    }

    @Test
    fun `write creates missing parent directories`() {
        val file = File(temp.root, "terdalam/jauh/notes.txt")

        val result = TextFileIO.write(file, "x")

        assertTrue(result.isSuccess)
        assertTrue(file.exists())
        assertEquals("x", file.readText())
    }

    @Test
    fun `write leaves no temporary file behind`() {
        val file = temp.newFile("atomik.txt")

        val result = TextFileIO.write(file, "data")

        assertTrue(result.isSuccess)
        val siblings = file.parentFile?.listFiles()?.map { it.name } ?: emptyList()
        assertEquals(listOf("atomik.txt"), siblings)
    }
}
