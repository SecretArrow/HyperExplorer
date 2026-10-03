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
