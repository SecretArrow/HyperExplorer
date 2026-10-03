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

package com.hyperexplorer.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class FileNodeTest {
    @Test
    fun `category detects media and documents`() {
        assertEquals(FileCategory.IMAGE, FileNode.categoryOf("photo.JPG"))
        assertEquals(FileCategory.VIDEO, FileNode.categoryOf("clip.mp4"))
        assertEquals(FileCategory.AUDIO, FileNode.categoryOf("song.flac"))
        assertEquals(FileCategory.ARCHIVE, FileNode.categoryOf("backup.7z"))
        assertEquals(FileCategory.APK, FileNode.categoryOf("app.apk"))
        assertEquals(FileCategory.DOCUMENT, FileNode.categoryOf("report.pdf"))
        assertEquals(FileCategory.DOCUMENT, FileNode.categoryOf("README.md"))
        assertEquals(FileCategory.OTHER, FileNode.categoryOf("noext"))
        assertEquals(FileCategory.OTHER, FileNode.categoryOf("data.bin"))
    }

    @Test
    fun `folder category for directory`() {
        val node = FileNode("/tmp/some-folder", true, 0, 0)
        assertEquals(FileCategory.FOLDER, node.category)
        assertEquals("some-folder", node.name)
    }

    @Test
    fun `human size formats bytes`() {
        assertEquals("0 B", FileNode.humanSize(0))
        assertEquals("512 B", FileNode.humanSize(512))
        assertEquals("1.0 KB", FileNode.humanSize(1024))
        assertEquals("1.5 KB", FileNode.humanSize(1536))
        assertEquals("1.0 MB", FileNode.humanSize(1024L * 1024))
        assertEquals("2.0 GB", FileNode.humanSize(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun `name extracted from path`() {
        val node = FileNode("/storage/emulated/0/Download/file.txt", false, 10, 0)
        assertEquals("file.txt", node.name)
    }
}
