package com.hyperexplorer.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun newRepo(): Pair<FileRepository, File> {
        val trash = tmp.newFolder("trash-" + System.nanoTime())
        return Pair(FileRepository(trash), trash)
    }

    @Test
    fun `list sorts folders first then names`() {
        val (repo, _) = newRepo()
        val dir = tmp.newFolder("list")
        File(dir, "zeta.txt").writeText("x")
        File(dir, "alpha").mkdir()
        File(dir, "beta.txt").writeText("x")
        File(dir, "alpha.txt").writeText("x")

        val items = repo.list(dir)

        assertEquals(listOf("alpha", "alpha.txt", "beta.txt", "zeta.txt"), items.map { it.name })
        assertTrue(items[0].isDirectory)
    }

    @Test
    fun `list returns empty for unreadable dir`() {
        val (repo, _) = newRepo()
        val missing = File(tmp.root, "does-not-exist")

        assertTrue(repo.list(missing).isEmpty())
    }

    @Test
    fun `move to trash and restore original path`() {
        val (repo, _) = newRepo()
        val dir = tmp.newFolder("docs")
        val file = File(dir, "report.txt").apply { writeText("isi") }

        assertEquals(1, repo.moveToTrash(listOf(file)))
        assertFalse(file.exists())
        assertEquals(1, repo.listTrash().size)

        val entry = repo.listTrash()[0]
        assertTrue(repo.restore(File(entry.path)))
        assertEquals("isi", file.readText())
        assertEquals(0, repo.listTrash().size)
    }

    @Test
    fun `move to trash handles multiple files`() {
        val (repo, _) = newRepo()
        val dir = tmp.newFolder("many")
        val files = (1..3).map { File(dir, "f$it.txt").apply { writeText("x") } }

        assertEquals(3, repo.moveToTrash(files))
        assertEquals(3, repo.listTrash().size)
    }

    @Test
    fun `empty trash removes all entries`() {
        val (repo, _) = newRepo()
        val dir = tmp.newFolder("clean")
        val files = (1..2).map { File(dir, "g$it.txt").apply { writeText("x") } }
        repo.moveToTrash(files)

        assertEquals(2, repo.emptyTrash())
        assertEquals(0, repo.listTrash().size)
    }

    @Test
    fun `move to trash skips missing files`() {
        val (repo, _) = newRepo()
        val ghost = File(tmp.root, "ghost.txt")

        assertEquals(0, repo.moveToTrash(listOf(ghost)))
    }
}
