package com.hyperexplorer.data.local

import com.hyperexplorer.core.common.ConflictStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileOperationsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `copy file to another folder`() {
        val src = tmp.newFolder("src")
        val dst = tmp.newFolder("dst")
        val file = File(src, "a.txt").apply { writeText("hello") }

        val result = FileOperations.copy(file, dst)

        assertEquals("a.txt", result.name)
        assertEquals("hello", result.readText())
        assertTrue(file.exists())
    }

    @Test
    fun `copy folder tree recursively`() {
        val src = tmp.newFolder("tree")
        File(src, "sub").mkdirs()
        File(src, "sub/deep.txt").writeText("deep")
        File(src, "top.txt").writeText("top")
        val dst = tmp.newFolder("elsewhere")

        val result = FileOperations.copy(src, dst)

        assertEquals("tree", result.name)
        assertEquals("deep", File(result, "sub/deep.txt").readText())
        assertEquals("top", File(result, "top.txt").readText())
    }

    @Test
    fun `move file removes source`() {
        val src = tmp.newFolder("m1")
        val dst = tmp.newFolder("m2")
        val file = File(src, "b.txt").apply { writeText("data") }

        val result = FileOperations.move(file, dst)

        assertEquals("data", result.readText())
        assertFalse(file.exists())
    }

    @Test
    fun `conflict rename produces unique name`() {
        val src = tmp.newFolder("c1")
        val dst = tmp.newFolder("c2")
        File(src, "x.txt").writeText("new")
        File(dst, "x.txt").writeText("old")

        val result = FileOperations.copy(File(src, "x.txt"), dst, ConflictStrategy.RENAME)

        assertEquals("x (1).txt", result.name)
        assertEquals("old", File(dst, "x.txt").readText())
    }

    @Test
    fun `conflict overwrite replaces target`() {
        val src = tmp.newFolder("o1")
        val dst = tmp.newFolder("o2")
        File(src, "x.txt").writeText("new")
        File(dst, "x.txt").writeText("old")

        val result = FileOperations.copy(File(src, "x.txt"), dst, ConflictStrategy.OVERWRITE)

        assertEquals("x.txt", result.name)
        assertEquals("new", result.readText())
    }

    @Test
    fun `conflict skip returns source untouched`() {
        val src = tmp.newFolder("s1")
        val dst = tmp.newFolder("s2")
        File(src, "x.txt").writeText("new")
        File(dst, "x.txt").writeText("old")

        val result = FileOperations.copy(File(src, "x.txt"), dst, ConflictStrategy.SKIP)

        assertEquals(File(src, "x.txt").absolutePath, result.absolutePath)
        assertEquals("old", File(dst, "x.txt").readText())
    }

    @Test
    fun `delete recursively removes tree`() {
        val dir = tmp.newFolder("del")
        File(dir, "nested").mkdirs()
        File(dir, "nested/f.txt").writeText("x")

        assertTrue(FileOperations.delete(dir))
        assertFalse(dir.exists())
    }

    @Test
    fun `rename changes name only`() {
        val dir = tmp.newFolder()
        val file = File(dir, "old.txt").apply { writeText("x") }

        val result = FileOperations.rename(file, "new.txt")

        assertEquals("new.txt", result.name)
        assertFalse(file.exists())
    }

    @Test
    fun `mkdir sanitizes and uniquifies`() {
        val dir = tmp.newFolder()
        File(dir, "data").mkdir()

        val created = FileOperations.mkdir(dir, "data")

        assertEquals("data (1)", created.name)
        assertTrue(created.isDirectory)
    }
}
