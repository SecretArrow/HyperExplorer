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

package com.hyperexplorer.feature.tools.vault.ui

import com.hyperexplorer.feature.tools.vault.SoftwareKeyProvider
import com.hyperexplorer.feature.tools.vault.VaultEngine
import com.hyperexplorer.feature.tools.vault.VaultEntry
import com.hyperexplorer.feature.tools.vault.VaultNameError
import com.hyperexplorer.feature.tools.vault.VaultResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Uji [VaultController] murni JVM: engine dipakai langsung (via [VaultEngine])
 * untuk menyiapkan data, sementara seluruh coroutine dikendalikan satu
 * [TestCoroutineScheduler] yang sama untuk dispatcher I/O maupun Dispatchers.Main.
 */
class VaultControllerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scheduler = TestCoroutineScheduler()
    private val testScope = TestScope(StandardTestDispatcher(scheduler))

    private lateinit var vaultDir: File
    private lateinit var sourceDir: File
    private lateinit var seedEngine: VaultEngine
    private lateinit var controller: VaultController

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        vaultDir = tmp.newFolder("vault")
        sourceDir = tmp.newFolder("sumber")
        val keyProvider = SoftwareKeyProvider(ByteArray(32) { it.toByte() })
        seedEngine = VaultEngine(vaultDir, keyProvider)
        controller = VaultController(vaultDir, keyProvider, testScope, StandardTestDispatcher(scheduler))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `refresh pada vault kosong menghasilkan Ready tanpa entri`() {
        assertNull(controller.messages.value)
        assertEquals(VaultUiState.Loading, controller.state.value)

        val ready = refreshAndWait()

        assertTrue(ready.entries.isEmpty())
        assertNull(ready.busy)
        assertNull(controller.messages.value)
    }

    @Test
    fun `refresh mengurutkan entri berdasarkan waktu tambah menurun`() {
        seedVaultEntry("pertama.txt", "satu".toByteArray())
        Thread.sleep(25)
        seedVaultEntry("kedua.txt", "dua".toByteArray())

        val ready = refreshAndWait()

        assertTrue(
            "pra-syarat uji: waktu tambah kedua entri harus berbeda",
            ready.entries.first().addedAtEpochMs >= ready.entries.last().addedAtEpochMs,
        )
        assertEquals(listOf("kedua.txt", "pertama.txt"), ready.entries.map { it.originalName })
    }

    @Test
    fun `importFrom jalur valid menambah entri dan mengirim IMPORTED`() {
        refreshAndWait()
        val source = File(sourceDir, "catatan.txt").apply { writeText("isi penting") }

        controller.importFrom(source.absolutePath, deleteSource = false)
        scheduler.advanceUntilIdle()

        val ready = currentReady()
        assertEquals(1, ready.entries.size)
        assertEquals("catatan.txt", ready.entries.first().originalName)
        assertNull(ready.busy)
        val message = controller.messages.value
        assertNotNull(message)
        assertEquals(VaultSuccess.IMPORTED, message?.success)
        assertEquals("catatan.txt", message?.detailArg)
        assertNull(message?.error)
    }

    @Test
    fun `importFrom jalur kosong mengirim INVALID_INPUT tanpa menyentuh engine`() {
        refreshAndWait()

        controller.importFrom("   ", deleteSource = false)
        scheduler.advanceUntilIdle()

        val message = controller.messages.value
        assertNotNull(message)
        assertEquals(VaultErrorKind.INVALID_INPUT, message?.error?.kind)
        assertEquals("jalur sumber kosong", message?.error?.detail)
        assertNull(message?.success)
        assertEquals("engine tidak boleh menambah entri", 0, currentReady().entries.size)
    }

    @Test
    fun `importFrom berkas sumber tidak ada mengirim SOURCE_MISSING`() {
        refreshAndWait()
        val missing = File(sourceDir, "hilang.txt")

        controller.importFrom(missing.absolutePath, deleteSource = false)
        scheduler.advanceUntilIdle()

        assertEquals(
            VaultErrorKind.SOURCE_MISSING,
            controller.messages.value
                ?.error
                ?.kind,
        )
        assertEquals(0, currentReady().entries.size)
    }

    @Test
    fun `importFrom direktori mengirim INVALID_INPUT`() {
        refreshAndWait()
        val directory = File(sourceDir, "folder").apply { mkdirs() }
        assertTrue(directory.isDirectory)

        controller.importFrom(directory.absolutePath, deleteSource = false)
        scheduler.advanceUntilIdle()

        assertEquals(
            VaultErrorKind.INVALID_INPUT,
            controller.messages.value
                ?.error
                ?.kind,
        )
        assertEquals(0, currentReady().entries.size)
    }

    @Test
    fun `exportTo folder tujuan valid mengirim EXPORTED dengan path hasil`() {
        seedVaultEntry("dokumen.txt", "ekspor saya".toByteArray())
        refreshAndWait()
        val id = currentReady().entries.single().id
        val destDir = tmp.newFolder("ekspor")

        controller.exportTo(id, destDir)
        scheduler.advanceUntilIdle()

        val message = controller.messages.value
        assertNotNull(message)
        assertEquals(VaultSuccess.EXPORTED, message?.success)
        assertNull(message?.error)
        val exportedPath = message?.detailArg
        assertNotNull("pesan EXPORTED harus membawa path hasil", exportedPath)
        val exportedFile = exportedPath?.let(::File) ?: throw AssertionError("path hasil ekspor null")
        assertTrue("berkas hasil ekspor harus ada: $exportedFile", exportedFile.exists())
        assertEquals(destDir.absolutePath, exportedFile.parent)
        assertEquals(1, currentReady().entries.size)
    }

    @Test
    fun `exportTo folder tujuan tidak ada mengirim galat IO atau INVALID_INPUT`() {
        seedVaultEntry("lain.txt", "data".toByteArray())
        refreshAndWait()
        val id = currentReady().entries.single().id
        val missingDir = File(tmp.root, "tidak-ada/anak")

        controller.exportTo(id, missingDir)
        scheduler.advanceUntilIdle()

        val message = controller.messages.value
        assertNotNull(message)
        assertNull(message?.success)
        val kind = message?.error?.kind
        assertTrue("jenis galat tak terduga: $kind", kind == VaultErrorKind.IO || kind == VaultErrorKind.INVALID_INPUT)
        assertEquals(1, currentReady().entries.size)
    }

    @Test
    fun `deleteEntry valid menghapus entri dan mengirim DELETED`() {
        seedVaultEntry("hapus-saya.txt", "bye".toByteArray())
        refreshAndWait()
        val id = currentReady().entries.single().id

        controller.deleteEntry(id)
        scheduler.advanceUntilIdle()

        assertTrue(currentReady().entries.isEmpty())
        val message = controller.messages.value
        assertEquals(VaultSuccess.DELETED, message?.success)
        assertEquals(id, message?.detailArg)
        assertNull(message?.error)
    }

    @Test
    fun `deleteEntry id asing mengirim ENTRY_NOT_FOUND`() {
        seedVaultEntry("tetap.txt", "ada".toByteArray())
        refreshAndWait()

        controller.deleteEntry("id-tidak-ada")
        scheduler.advanceUntilIdle()

        assertEquals(
            VaultErrorKind.ENTRY_NOT_FOUND,
            controller.messages.value
                ?.error
                ?.kind,
        )
        assertEquals(1, currentReady().entries.size)
    }

    @Test
    fun `renameEntry nama traversal mengirim INVALID_INPUT tanpa mengubah nama`() {
        seedVaultEntry("asli.txt", "x".toByteArray())
        refreshAndWait()
        val id = currentReady().entries.single().id

        controller.renameEntry(id, "../x")
        scheduler.advanceUntilIdle()

        val message = controller.messages.value
        assertEquals(VaultErrorKind.INVALID_INPUT, message?.error?.kind)
        val detail = message?.error?.detail
        assertTrue(
            "detail harus jenis VaultNameError, aktual: $detail",
            VaultNameError.values().any { it.name == detail },
        )
        assertEquals("asli.txt", currentReady().entries.single().originalName)
    }

    @Test
    fun `renameEntry valid mengubah nama dan mengirim RENAMED`() {
        seedVaultEntry("lama.txt", "isi".toByteArray())
        refreshAndWait()
        val id = currentReady().entries.single().id

        controller.renameEntry(id, "baru.txt")
        scheduler.advanceUntilIdle()

        assertEquals("baru.txt", currentReady().entries.single().originalName)
        val message = controller.messages.value
        assertEquals(VaultSuccess.RENAMED, message?.success)
        assertEquals("baru.txt", message?.detailArg)
        assertNull(message?.error)
    }

    @Test
    fun `verifyEntry entri sehat mengirim VERIFIED`() {
        seedVaultEntry("utuh.txt", "integritas".toByteArray())
        refreshAndWait()
        val id = currentReady().entries.single().id

        controller.verifyEntry(id)
        scheduler.advanceUntilIdle()

        val message = controller.messages.value
        assertEquals(VaultSuccess.VERIFIED, message?.success)
        assertNull(message?.error)
    }

    @Test
    fun `verifyEntry blob dirusak mengirim CORRUPT`() {
        seedVaultEntry("korup.txt", "payload".toByteArray())
        refreshAndWait()
        val entry = currentReady().entries.single()
        val storedFile = File(vaultDir, entry.storedFileName)
        assertTrue("berkas tersimpan harus ada: ${storedFile.absolutePath}", storedFile.exists())
        val bytes = storedFile.readBytes()
        assertTrue(bytes.isNotEmpty())
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x01).toByte()
        storedFile.writeBytes(bytes)

        controller.verifyEntry(entry.id)
        scheduler.advanceUntilIdle()

        assertEquals(
            VaultErrorKind.CORRUPT,
            controller.messages.value
                ?.error
                ?.kind,
        )
    }

    @Test
    fun `op kedua diabaikan saat op pertama masih berjalan`() {
        seedVaultEntry("awal.txt", "seed".toByteArray())
        refreshAndWait()
        val first = File(sourceDir, "pertama.txt").apply { writeText("1") }
        val second = File(sourceDir, "kedua.txt").apply { writeText("2") }

        controller.importFrom(first.absolutePath, deleteSource = false)
        // Tanpa menjalankan scheduler sedikit pun: busy sudah dipasang secara
        // sinkron, sehingga op kedua harus tertolak (anti-reentrancy).
        controller.importFrom(second.absolutePath, deleteSource = true)
        scheduler.advanceUntilIdle()

        val ready = currentReady()
        assertEquals(2, ready.entries.size)
        assertEquals(setOf("awal.txt", "pertama.txt"), ready.entries.map { it.originalName }.toSet())
        assertFalse(
            "impor kedua tidak boleh diproses",
            ready.entries.any { it.originalName == second.name },
        )
        assertTrue("sumber kedua tidak boleh terhapus", second.exists())
        assertNull(ready.busy)
        assertEquals("hanya impor pertama yang mengirim pesan", "pertama.txt", controller.messages.value?.detailArg)
    }

    @Test
    fun `consumeMessage mengosongkan pesan`() {
        seedVaultEntry("pesan.txt", "hai".toByteArray())
        refreshAndWait()
        controller.verifyEntry(currentReady().entries.single().id)
        scheduler.advanceUntilIdle()
        assertNotNull(controller.messages.value)

        controller.consumeMessage()

        assertNull(controller.messages.value)
    }

    @Test
    fun `openEntry menyiapkan berkas dan memanggil onReady di Main`() {
        seedVaultEntry("buka.txt", "dekripsi saya".toByteArray())
        refreshAndWait()
        val cacheDir = tmp.newFolder("cache-vault")
        var opened: File? = null

        controller.openEntry(currentReady().entries.single().id, cacheDir) { opened = it }
        scheduler.advanceUntilIdle()

        val message = controller.messages.value
        assertEquals(VaultSuccess.OPENED, message?.success)
        assertNull(message?.error)
        val file = opened ?: throw AssertionError("onReady harus dipanggil dengan berkas hasil dekripsi")
        assertTrue("berkas hasil dekripsi harus ada: $file", file.exists())
    }

    @Test
    fun `op saat belum Ready diabaikan`() {
        val source = File(sourceDir, "belum.txt").apply { writeText("x") }

        controller.importFrom(source.absolutePath, deleteSource = false)
        scheduler.advanceUntilIdle()

        assertEquals(VaultUiState.Loading, controller.state.value)
        assertNull(controller.messages.value)
        controller.refresh()
        scheduler.advanceUntilIdle()
        assertEquals("impor pra-Ready tidak boleh menambah entri", 0, currentReady().entries.size)
    }

    /** Menyiapkan satu entri vault langsung lewat [VaultEngine] (tanpa controller). */
    private fun seedVaultEntry(
        fileName: String,
        content: ByteArray,
    ): VaultEntry {
        val source = File(sourceDir, fileName).apply { writeBytes(content) }
        val result = seedEngine.importFile(source, deleteSource = false)
        return when (result) {
            is VaultResult.Ok -> result.value.entry
            is VaultResult.Err -> throw AssertionError("gagal menyiapkan entri uji $fileName: ${result.error}")
        }
    }

    /** Menjalankan refresh sampai tuntas dan mengembalikan state Ready. */
    private fun refreshAndWait(): VaultReadyState {
        controller.refresh()
        scheduler.advanceUntilIdle()
        return currentReady()
    }

    /** Mengambil state Ready atau gagalkan uji bila state bukan Ready. */
    private fun currentReady(): VaultReadyState {
        val state = controller.state.value
        val ready = state as? VaultUiState.Ready
        return ready?.state ?: throw AssertionError("state seharusnya Ready, aktual: $state")
    }
}
