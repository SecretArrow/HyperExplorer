package com.hyperexplorer.feature.browser

import com.hyperexplorer.core.model.FileNode
import com.hyperexplorer.data.local.FileRepository
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserStateTest {

    private fun newTempDir(): File = Files.createTempDirectory("hyper-test").toFile()

    private fun newRepo(): FileRepository {
        // Recycle bin di luar folder root agar tidak ikut muncul dalam listing
        val trash = newTempDir()
        return FileRepository(trash)
    }

    @Test
    fun `refresh loads children folders first`() = runBlocking {
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
    fun `open navigates and up returns`() = runBlocking {
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
    fun `delete selected moves to trash`() = runBlocking {
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
    fun `copy then paste duplicates file`() = runBlocking {
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
    fun `cut then paste moves file`() = runBlocking {
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
