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

package com.hyperexplorer.feature.tools.storage

import com.hyperexplorer.core.model.FileCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StorageAnalyzerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val analyzer = StorageAnalyzer(Dispatchers.Unconfined)

    @Test
    fun `analyze sums sizes categories largest files and duplicates`() =
        runBlocking {
            val root = tmp.newFolder("root")
            File(root, "img1.jpg").writeBytes("AAAA".repeat(25).toByteArray())
            File(root, "img2.jpg").writeBytes("AAAA".repeat(25).toByteArray())
            File(root, "doc.txt").writeText("B".repeat(50))
            File(root, "vid.mp4").writeBytes("V".repeat(200).toByteArray())

            val report = analyzer.analyze(root)

            assertEquals(root, report.root)
            assertEquals(450L, report.totalBytes)
            assertEquals(4, report.fileCount)
            assertTrue(report.usableBytes >= 0L)
            assertEquals(200L, report.categorySizes[FileCategory.IMAGE])
            assertEquals(50L, report.categorySizes[FileCategory.DOCUMENT])
            assertEquals(200L, report.categorySizes[FileCategory.VIDEO])
            assertEquals(
                listOf("vid.mp4", "img1.jpg", "img2.jpg", "doc.txt"),
                report.largestFiles.map { node -> node.name },
            )
            assertEquals(1, report.duplicateGroups.size)
            assertEquals(
                setOf(File(root, "img1.jpg").absolutePath, File(root, "img2.jpg").absolutePath),
                report.duplicateGroups.first().toSet(),
            )
        }
}
