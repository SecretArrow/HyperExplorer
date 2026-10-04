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

package com.hyperexplorer.feature.sync

import com.hyperexplorer.data.remote.RemoteEntry
import com.hyperexplorer.data.remote.RemoteFileSystem
import com.hyperexplorer.data.remote.RemotePath
import com.hyperexplorer.feature.sync.engine.SyncDirection
import com.hyperexplorer.feature.sync.engine.SyncEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** Simpul in-memory untuk [FakeRemoteFileSystem]. */
private class FakeNode(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0L,
    val lastModified: Long = 0L,
    val content: ByteArray = ByteArray(0),
) {
    fun toEntry(): RemoteEntry = RemoteEntry(name, path, isDirectory, size, lastModified)
}

/**
 * RemoteFileSystem palsu in-memory (peta path -> simpul) yang mengimplementasikan
 * seluruh kontrak: list/makeDirectory/delete/rename/download/upload/close.
 * Punya sakelar simulasi kegagalan ([failListFor], [failUploadFor]) dan pencatat
 * panggilan mkdir ([mkdirCalls]) untuk assert per level.
 */
private class FakeRemoteFileSystem : RemoteFileSystem {
    val nodes = LinkedHashMap<String, FakeNode>()
    val mkdirCalls = mutableListOf<String>()

    /** Path folder yang jika di-list akan melempar IOException ("" = root). */
    var failListFor: String? = null

    /** Nama berkas lokal yang jika di-upload akan melempar IOException. */
    var failUploadFor: String? = null

    @Volatile
    private var closed = false

    fun addDirectory(path: String) {
        val p = RemotePath.normalize(path)
        nodes[p] = FakeNode(p.substringAfterLast('/'), p, isDirectory = true)
    }

    fun addFile(
        path: String,
        content: String,
        lastModified: Long = 0L,
        forcedName: String? = null,
    ) {
        val p = RemotePath.normalize(path)
        val bytes = content.toByteArray()
        nodes[p] = FakeNode(forcedName ?: p.substringAfterLast('/'), p, isDirectory = false, bytes.size.toLong(), lastModified, bytes)
    }

    private fun ensureOpen() {
        if (closed) throw IllegalStateException("Koneksi remote sudah ditutup")
    }

    private fun parentOf(path: String): String = path.substringBeforeLast('/', "")

    override suspend fun list(path: String): List<RemoteEntry> {
        ensureOpen()
        val p = RemotePath.normalize(path)
        if (failListFor != null && p == failListFor) throw IOException("Simulasi gagal listing folder '$p'")
        if (p.isNotEmpty()) {
            val node = nodes[p] ?: throw IOException("Folder remote tidak ditemukan: '$p'")
            if (!node.isDirectory) throw IOException("Bukan folder remote: '$p'")
        }
        return nodes.values.filter { parentOf(it.path) == p }.map { it.toEntry() }
    }

    override suspend fun makeDirectory(
        parent: String,
        name: String,
    ) {
        ensureOpen()
        val p = RemotePath.join(parent, name)
        if (nodes.containsKey(p)) throw IOException("Folder remote sudah ada: '$p'")
        if (p.isNotEmpty() && parentOf(p) != RemotePath.normalize(parent)) {
            throw IOException("Folder induk remote tidak ada: '$p'")
        }
        nodes[p] = FakeNode(name, p, isDirectory = true)
        mkdirCalls.add(p)
    }

    override suspend fun delete(
        path: String,
        isDirectory: Boolean,
    ) {
        ensureOpen()
        val p = RemotePath.normalize(path)
        val node = nodes.remove(p) ?: throw IOException("Tidak ditemukan: '$p'")
        if (node.isDirectory != isDirectory) throw IOException("Jenis target tidak cocok: '$p'")
        if (node.isDirectory) {
            nodes.keys.filter { it.startsWith("$p/") }.forEach { nodes.remove(it) }
        }
    }

    override suspend fun rename(
        path: String,
        oldName: String,
        newName: String,
    ) {
        ensureOpen()
        val oldPath = RemotePath.join(path, oldName)
        val newPath = RemotePath.join(path, newName)
        val node = nodes.remove(oldPath) ?: throw IOException("Tidak ditemukan: '$oldPath'")
        if (nodes.containsKey(newPath)) throw IOException("Nama tujuan sudah dipakai: '$newPath'")
        nodes[newPath] = FakeNode(newName, newPath, node.isDirectory, node.size, node.lastModified, node.content)
    }

    override suspend fun download(
        remotePath: String,
        target: File,
        sizeHint: Long,
    ) {
        ensureOpen()
        val node = nodes[RemotePath.normalize(remotePath)] ?: throw IOException("Berkas remote tidak ditemukan: '$remotePath'")
        if (node.isDirectory) throw IOException("Target adalah folder remote: '$remotePath'")
        target.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
        target.writeBytes(node.content)
    }

    override suspend fun upload(
        local: File,
        remoteDir: String,
    ) {
        ensureOpen()
        if (failUploadFor != null && failUploadFor == local.name) throw IOException("Simulasi gagal unggah: ${local.name}")
        val dir = RemotePath.normalize(remoteDir)
        if (dir.isNotEmpty()) {
            val dirNode = nodes[dir] ?: throw IOException("Folder remote tujuan tidak ada: '$dir'")
            if (!dirNode.isDirectory) throw IOException("Tujuan unggah bukan folder: '$dir'")
        }
        if (!local.isFile) throw IOException("Berkas lokal tidak ada: ${local.absolutePath}")
        val content = local.readBytes()
        val p = RemotePath.join(dir, local.name)
        nodes[p] = FakeNode(local.name, p, isDirectory = false, content.size.toLong(), local.lastModified(), content)
    }

    override fun close() {
        closed = true
    }
}

/**
 * Test JVM murni untuk [SyncEngine] memakai remote in-memory + TemporaryFolder.
 * Semua badan test berupa blok `runBlocking<Unit>` agar method @Test tetap void
 * (pelajaran CI Task 9).
 */
class SyncEngineTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var localRoot: File
    private lateinit var fake: FakeRemoteFileSystem
    private lateinit var engine: SyncEngine

    private val baseMtime = 1_700_000_000_000L

    @Before
    fun setUp() {
        localRoot = temporaryFolder.newFolder("lokal")
        fake = FakeRemoteFileSystem()
        engine = SyncEngine()
    }

    private fun newLocalFile(
        relPath: String,
        content: String,
        mtime: Long? = null,
    ): File {
        val file = File(localRoot, relPath)
        file.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
        file.writeText(content)
        if (mtime != null) file.setLastModified(mtime)
        return file
    }

    @Test
    fun `push mengunggah berkas lokal baru dan hasil bersih`() =
        runBlocking<Unit> {
            newLocalFile("catatan.txt", "halo", baseMtime)

            val result = engine.sync(localRoot, "", fake, SyncDirection.PUSH_TO_REMOTE)

            assertTrue(result.isSuccess)
            val stats = result.getOrThrow()
            assertEquals(1, stats.uploaded)
            assertEquals(0, stats.skipped)
            assertTrue(stats.failures.isEmpty())
            assertTrue(stats.isClean())
            assertTrue(fake.nodes.containsKey("catatan.txt"))
        }

    @Test
    fun `push membuat folder bersarang per level lalu mengunggah berkasnya`() =
        runBlocking<Unit> {
            newLocalFile("a/b/c.txt", "isi", baseMtime)

            val result = engine.sync(localRoot, "", fake, SyncDirection.PUSH_TO_REMOTE)

            assertTrue(result.isSuccess)
            val stats = result.getOrThrow()
            assertEquals(listOf("a", "a/b"), fake.mkdirCalls)
            assertEquals(2, stats.createdDirectories)
            assertEquals(1, stats.uploaded)
            assertTrue(fake.nodes.containsKey("a/b/c.txt"))
        }

    @Test
    fun `push kedua melewati berkas yang identik`() =
        runBlocking<Unit> {
            newLocalFile("data.bin", "sama", baseMtime)
            engine.sync(localRoot, "", fake, SyncDirection.PUSH_TO_REMOTE)

            val second = engine.sync(localRoot, "", fake, SyncDirection.PUSH_TO_REMOTE)

            assertTrue(second.isSuccess)
            val stats = second.getOrThrow()
            assertEquals(0, stats.uploaded)
            assertEquals(1, stats.skipped)
        }

    @Test
    fun `push mengunggah ulang bila ukuran berubah`() =
        runBlocking<Unit> {
            val file = newLocalFile("data.bin", "pendek", baseMtime)
            engine.sync(localRoot, "", fake, SyncDirection.PUSH_TO_REMOTE)

            file.writeText("isi yang jauh lebih panjang dari sebelumnya")
            val second = engine.sync(localRoot, "", fake, SyncDirection.PUSH_TO_REMOTE)

            assertTrue(second.isSuccess)
            val stats = second.getOrThrow()
            assertEquals(1, stats.uploaded)
            assertEquals("isi yang jauh lebih panjang dari sebelumnya".toByteArray().size.toLong(), fake.nodes.getValue("data.bin").size)
        }

    @Test
    fun `push melewati selisih mtime di bawah ambang dan unggah ulang di atas ambang`() =
        runBlocking<Unit> {
            val file = newLocalFile("waktu.txt", "stabil", baseMtime)
            engine.sync(localRoot, "", fake, SyncDirection.PUSH_TO_REMOTE)
            val remoteMtime = fake.nodes.getValue("waktu.txt").lastModified

            // Selisih 800 ms <= 1000 ms: dianggap identik (granularitas filesystem).
            file.setLastModified(remoteMtime + 800L)
            val below = engine.sync(localRoot, "", fake, SyncDirection.PUSH_TO_REMOTE)
            assertTrue(below.isSuccess)
            assertEquals(0, below.getOrThrow().uploaded)
            assertEquals(1, below.getOrThrow().skipped)

            // Selisih 5000 ms > 1000 ms: berubah, unggah ulang (ukuran tetap sama).
            file.setLastModified(remoteMtime + 5000L)
            val above = engine.sync(localRoot, "", fake, SyncDirection.PUSH_TO_REMOTE)
            assertTrue(above.isSuccess)
            assertEquals(1, above.getOrThrow().uploaded)
        }

    @Test
    fun `push membiarkan berkas ekstra di remote tetap utuh`() =
        runBlocking<Unit> {
            fake.addFile("extra.txt", "hanya di remote", baseMtime)
            newLocalFile("lokal.txt", "isi lokal", baseMtime)

            val result = engine.sync(localRoot, "", fake, SyncDirection.PUSH_TO_REMOTE)

            assertTrue(result.isSuccess)
            val stats = result.getOrThrow()
            assertEquals(1, stats.uploaded)
            assertTrue(stats.failures.isEmpty())
            assertTrue("Berkas remote ekstra wajib tetap ada (v1 non-destruktif)", fake.nodes.containsKey("extra.txt"))
        }

    @Test
    fun `pull mengunduh berkas baru dan membuat folder bersarang`() =
        runBlocking<Unit> {
            fake.addDirectory("docs")
            fake.addFile("docs/readme.txt", "bantuan", baseMtime)
            fake.addFile("top.txt", "atas", baseMtime)

            val result = engine.sync(localRoot, "", fake, SyncDirection.PULL_TO_LOCAL)

            assertTrue(result.isSuccess)
            val stats = result.getOrThrow()
            assertEquals(2, stats.downloaded)
            assertEquals(1, stats.createdDirectories)
            assertEquals("bantuan", File(localRoot, "docs/readme.txt").readText())
            assertEquals("atas", File(localRoot, "top.txt").readText())
        }

    @Test
    fun `pull menolak entri remote dengan nama traversal`() =
        runBlocking<Unit> {
            fake.addFile("evil", "jahat", baseMtime, forcedName = "../evil")

            val result = engine.sync(localRoot, "", fake, SyncDirection.PULL_TO_LOCAL)

            assertTrue(result.isSuccess)
            val stats = result.getOrThrow()
            assertEquals(1, stats.failures.size)
            assertTrue(stats.failures.first().contains("anti-traversal"))
            assertFalse(stats.isClean())
            assertFalse("Tidak boleh menulis berkas di dalam root", File(localRoot, "evil").exists())
            val outside = File(localRoot.parentFile, "evil")
            assertFalse("Tidak boleh menulis berkas di luar root", outside.exists())
        }

    @Test
    fun `push gagal bila folder lokal tidak ada`() =
        runBlocking<Unit> {
            val missing = File(temporaryFolder.root, "tidak-ada")

            val result = engine.sync(missing, "", fake, SyncDirection.PUSH_TO_REMOTE)

            assertTrue(result.isFailure)
            val message = result.exceptionOrNull()?.message ?: ""
            assertTrue(message.contains("tidak ada"))
            assertTrue(message.contains(missing.absolutePath))
        }

    @Test
    fun `pull membuat folder tujuan bila belum ada`() =
        runBlocking<Unit> {
            fake.addFile("top.txt", "atas", baseMtime)
            val target = File(temporaryFolder.root, "tujuan/baru")
            assertFalse(target.exists())

            val result = engine.sync(target, "", fake, SyncDirection.PULL_TO_LOCAL)

            assertTrue(result.isSuccess)
            assertTrue(target.isDirectory)
            assertEquals("atas", File(target, "top.txt").readText())
        }

    @Test
    fun `sinkronisasi gagal bila listing folder remote melempar`() =
        runBlocking<Unit> {
            newLocalFile("catatan.txt", "halo", baseMtime)
            fake.failListFor = ""

            val pushResult = engine.sync(localRoot, "", fake, SyncDirection.PUSH_TO_REMOTE)

            assertTrue(pushResult.isFailure)
            val error = pushResult.exceptionOrNull()
            assertTrue(error is IOException)
            assertTrue((error?.message ?: "").contains("Gagal membaca folder remote"))
        }

    @Test
    fun `kegagalan satu berkas tidak menggagalkan run`() =
        runBlocking<Unit> {
            newLocalFile("rusak.txt", "akan gagal", baseMtime)
            newLocalFile("baik.txt", "akan sukses", baseMtime)
            fake.failUploadFor = "rusak.txt"

            val result = engine.sync(localRoot, "", fake, SyncDirection.PUSH_TO_REMOTE)

            assertTrue(result.isSuccess)
            val stats = result.getOrThrow()
            assertEquals(1, stats.uploaded)
            assertEquals(1, stats.failures.size)
            assertTrue(stats.failures.first().startsWith("rusak.txt:"))
            assertFalse(stats.isClean())
            assertTrue(fake.nodes.containsKey("baik.txt"))
            assertFalse(fake.nodes.containsKey("rusak.txt"))
        }

    @Test
    fun `sinkronisasi dengan kedua sisi kosong tetap sukses tanpa transfer`() =
        runBlocking<Unit> {
            val result = engine.sync(localRoot, "", fake, SyncDirection.PUSH_TO_REMOTE)

            assertTrue(result.isSuccess)
            val stats = result.getOrThrow()
            assertEquals(0, stats.uploaded)
            assertEquals(0, stats.createdDirectories)
            assertEquals(0, stats.skipped)
            assertTrue(stats.isClean())
            assertNull(stats.failures.firstOrNull())
        }
}
