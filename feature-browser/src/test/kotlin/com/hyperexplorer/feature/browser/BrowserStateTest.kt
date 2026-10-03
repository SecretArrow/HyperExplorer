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

package com.hyperexplorer.feature.browser

import com.hyperexplorer.core.model.FileNode
import com.hyperexplorer.data.local.FileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BrowserStateTest {
    private fun newTempDir(): File = Files.createTempDirectory("hyper-test").toFile()

    private fun newRepo(): FileRepository {
        // Recycle bin di luar folder root agar tidak ikut muncul dalam listing
        val trash = newTempDir()
        return FileRepository(trash)
    }

    @Test
    fun `refresh loads children folders first`() =
        runBlocking {
            val root = newTempDir()
            File(root, "zeta.txt").writeText("x")
            File(root, "alpha").mkdir()
            val state = BrowserState(newRepo(), root, ioDispatcher = Dispatchers.Unconfined)

            val ui = state.ui.value
            assertFalse(ui.loading)
            assertEquals(2, ui.items.size)
            assertEquals("alpha", ui.items[0].name)
            root.deleteRecursively()
            Unit
        }

    @Test
    fun `open navigates and up returns`() =
        runBlocking {
            val root = newTempDir()
            val sub = File(root, "sub").apply { mkdir() }
            File(sub, "inner.txt").writeText("x")
            val state = BrowserState(newRepo(), root, ioDispatcher = Dispatchers.Unconfined)

            state.open(FileNode.from(sub))
            assertEquals(sub, state.ui.value.current)
            assertEquals("inner.txt", state.ui.value.items[0].name)

            state.up()
            assertEquals(root, state.ui.value.current)
            root.deleteRecursively()
            Unit
        }

    @Test
    fun `delete selected moves to trash`() =
        runBlocking {
            val root = newTempDir()
            val file = File(root, "doomed.txt").apply { writeText("x") }
            val state = BrowserState(newRepo(), root, ioDispatcher = Dispatchers.Unconfined)

            state.setSelectionMode(true)
            state.toggleSelection(FileNode.from(file))
            state.deleteSelected()

            assertFalse(file.exists())
            assertEquals(0, state.ui.value.selection.size)
            root.deleteRecursively()
            Unit
        }

    @Test
    fun `copy then paste duplicates file`() =
        runBlocking {
            val root = newTempDir()
            val sub = File(root, "sub").apply { mkdir() }
            val file = File(root, "orig.txt").apply { writeText("data") }
            val state = BrowserState(newRepo(), root, ioDispatcher = Dispatchers.Unconfined)

            state.setSelectionMode(true)
            state.toggleSelection(FileNode.from(file))
            state.copySelected()

            state.open(FileNode.from(sub))
            state.paste()

            assertEquals("data", File(sub, "orig.txt").readText())
            assertTrue(file.exists())
            root.deleteRecursively()
            Unit
        }

    @Test
    fun `cut then paste moves file`() =
        runBlocking {
            val root = newTempDir()
            val sub = File(root, "sub").apply { mkdir() }
            val file = File(root, "moveme.txt").apply { writeText("data") }
            val state = BrowserState(newRepo(), root, ioDispatcher = Dispatchers.Unconfined)

            state.setSelectionMode(true)
            state.toggleSelection(FileNode.from(file))
            state.cutSelected()

            state.open(FileNode.from(sub))
            state.paste()

            assertTrue(File(sub, "moveme.txt").exists())
            assertFalse(file.exists())
            root.deleteRecursively()
            Unit
        }
}
