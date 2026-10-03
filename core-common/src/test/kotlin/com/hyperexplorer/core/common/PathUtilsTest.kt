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
