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

package com.hyperexplorer.feature.browser.search

import com.hyperexplorer.core.model.FileCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Uji [SearchController] murni JVM dengan engine ASLI ([SearchEngine] +
 * [SearchIndexStore]) — sekaligus menguji integrasi kontrak engine Task 11-a.
 * Seluruh coroutine controller berjalan di satu [TestCoroutineScheduler] yang sama
 * (StandardTestDispatcher), sehingga setiap langkah asinkron bisa disinkronkan
 * dengan runCurrent/advanceUntilIdle secara deterministik.
 *
 * Asumsi semantik engine yang diuji di sini (sesuai kontrak Task 11-a):
 * pencarian tak-peduli-kapital berbasis nama, nameQuery kosong = tanpa filter nama,
 * hasil terpotong tetap melaporkan totalMatches penuh, dan skippedCount == 0 untuk
 * berkas yang seluruhnya terbaca.
 */
class SearchControllerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scheduler = TestCoroutineScheduler()

    private lateinit var root: File
    private lateinit var indexFile: File

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        root = tmp.newFolder("root")
        indexFile = File(tmp.root, "search-index.bin")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `init dengan indeks tersimpan menghasilkan READY dan jumlah benar`() {
        val expected = seedIndex()
        val controller = newController()

        scheduler.advanceUntilIdle()

        val state = controller.ui.value
        assertEquals(SearchStatus.READY, state.status)
        assertEquals("jumlah indeks harus sama dengan hasil seeding", expected, state.indexCount)
        assertNull(state.error)
    }

    @Test
    fun `init tanpa indeks menghasilkan IDLE dan jumlah nol`() {
        val controller = newController()

        scheduler.advanceUntilIdle()

        val state = controller.ui.value
        assertEquals(SearchStatus.IDLE, state.status)
        assertEquals(0, state.indexCount)
        assertNull(state.error)
    }

    @Test
    fun `search sukses kueri sebagian beda kapital`() {
        newFile("Laporan.txt", "satu")
        newFile("laporan-keuangan.txt", "dua")
        newFile("foto.jpg", "gambar")
        seedIndex()
        val controller = newController()
        scheduler.advanceUntilIdle()
        assertEquals(SearchStatus.READY, controller.ui.value.status)

        controller.search(SearchFilters(nameQuery = "LAPORAN"))
        assertEquals("status SEARCHING dipasang sinkron", SearchStatus.SEARCHING, controller.ui.value.status)
        scheduler.advanceUntilIdle()

        val state = controller.ui.value
        assertEquals(SearchStatus.READY, state.status)
        assertNull(state.error)
        assertFalse(state.truncated)
        assertEquals(
            "dua berkas laporan cocok tanpa mempedulikan kapitalisasi",
            setOf("Laporan.txt", "laporan-keuangan.txt"),
            state.entries.map { it.name }.toSet(),
        )
        assertEquals(2, state.totalMatches)
    }

    @Test
    fun `search saat indeks kosong menghasilkan INDEX_EMPTY tanpa memanggil engine`() {
        val controller = newController()
        scheduler.advanceUntilIdle()
        assertEquals(SearchStatus.IDLE, controller.ui.value.status)

        controller.search(SearchFilters(nameQuery = "apa pun"))
        scheduler.advanceUntilIdle()

        val state = controller.ui.value
        assertEquals(SearchError.INDEX_EMPTY, state.error)
        assertEquals("status tidak boleh berubah menjadi SEARCHING", SearchStatus.IDLE, state.status)
        assertEquals(0, state.entries.size)
        assertEquals(0, state.totalMatches)
    }

    @Test
    fun `search saat INDEXING diabaikan tanpa runCurrent kedua`() {
        newFile("a.txt", "satu")
        val controller = newController()

        controller.buildIndex()
        assertEquals(SearchStatus.INDEXING, controller.ui.value.status)

        // Tanpa menjalankan scheduler sedikit pun: status INDEXING sudah dipasang
        // sinkron oleh buildIndex, sehingga pencarian berikutnya harus tertolak.
        controller.search(SearchFilters(nameQuery = "a"))
        scheduler.advanceUntilIdle()

        val state = controller.ui.value
        assertEquals(SearchStatus.READY, state.status)
        assertEquals("pencarian tidak boleh dieksekusi", 0, state.entries.size)
        assertEquals(0, state.totalMatches)
        assertNull(state.error)
    }

    @Test
    fun `buildIndex sukses menghasilkan READY dan menulis berkas indeks`() {
        newFile("buku.pdf", "isi")
        newFile("foto.jpg", "biner")
        val expected = SearchEngine.buildIndex(root).entries.size
        val controller = newController()
        scheduler.advanceUntilIdle()

        controller.buildIndex()
        scheduler.advanceUntilIdle()

        val state = controller.ui.value
        assertEquals(SearchStatus.READY, state.status)
        assertEquals("jumlah indeks harus sama dengan hasil pemindaian engine", expected, state.indexCount)
        assertEquals("seluruh berkas terbaca, tidak ada yang dilewati", 0, state.skippedCount)
        assertTrue("indeks harus tersimpan ke indexFile", indexFile.exists())
        assertNull(state.error)
    }

    @Test
    fun `buildIndex root tak valid menghasilkan INDEX_FAILED`() {
        val missingRoot = File(tmp.root, "tidak-ada")
        val controller = newController(rootDir = missingRoot)
        scheduler.advanceUntilIdle()
        assertEquals(SearchStatus.IDLE, controller.ui.value.status)

        controller.buildIndex()
        scheduler.advanceUntilIdle()

        val state = controller.ui.value
        assertEquals(SearchError.INDEX_FAILED, state.error)
        assertEquals("indeks kosong -> status kembali IDLE", SearchStatus.IDLE, state.status)
    }

    @Test
    fun `clearError menghapus galat`() {
        val controller = newController()
        scheduler.advanceUntilIdle()
        controller.search(SearchFilters(nameQuery = "x"))
        assertEquals(SearchError.INDEX_EMPTY, controller.ui.value.error)

        controller.clearError()

        assertNull(controller.ui.value.error)
        assertEquals(SearchStatus.IDLE, controller.ui.value.status)
    }

    @Test
    fun `clearResults dengan indeks kembali READY`() {
        newFile("catatan.txt", "isi")
        seedIndex()
        val controller = newController()
        scheduler.advanceUntilIdle()

        controller.search(SearchFilters(nameQuery = "catatan"))
        scheduler.advanceUntilIdle()
        assertTrue("pra-syarat: harus ada hasil", controller.ui.value.entries.isNotEmpty())

        controller.clearResults()

        val state = controller.ui.value
        assertEquals(SearchStatus.READY, state.status)
        assertEquals(0, state.entries.size)
        assertEquals(0, state.totalMatches)
        assertFalse(state.truncated)
        assertTrue("indeks tetap terpasang", state.indexCount > 0)
    }

    @Test
    fun `clearResults tanpa indeks kembali IDLE`() {
        val controller = newController()
        scheduler.advanceUntilIdle()
        assertEquals(SearchStatus.IDLE, controller.ui.value.status)

        controller.clearResults()

        assertEquals(SearchStatus.IDLE, controller.ui.value.status)
    }

    @Test
    fun `search terpotong sesuai maxResults`() {
        newFile("laporan-1.txt", "satu")
        newFile("laporan-2.txt", "dua")
        seedIndex()
        val controller = newController()
        scheduler.advanceUntilIdle()

        controller.search(SearchFilters(nameQuery = "laporan", maxResults = 1))
        scheduler.advanceUntilIdle()

        val state = controller.ui.value
        assertEquals(SearchStatus.READY, state.status)
        assertTrue("hasil harus ditandai terpotong", state.truncated)
        assertEquals("hanya satu hasil yang ditampilkan", 1, state.entries.size)
        assertEquals("totalMatches tetap menghitung seluruh kecocokan", 2, state.totalMatches)
    }

    @Test
    fun `search dengan filter kategori hanya mengembalikan kategori itu`() {
        newFile("photo.jpg", "gambar")
        newFile("notes.txt", "teks")
        newFile("movie.mp4", "video")
        seedIndex()
        val controller = newController()
        scheduler.advanceUntilIdle()

        controller.search(SearchFilters(category = FileCategory.IMAGE))
        scheduler.advanceUntilIdle()

        val state = controller.ui.value
        assertEquals(SearchStatus.READY, state.status)
        assertEquals(
            "hanya berkas kategori gambar yang cocok",
            setOf("photo.jpg"),
            state.entries.map { it.name }.toSet(),
        )
        assertEquals(1, state.totalMatches)
    }

    @Test
    fun `search kedua saat SEARCHING diabaikan`() {
        newFile("laporan.txt", "satu")
        newFile("lain.txt", "dua")
        seedIndex()
        val controller = newController()
        scheduler.advanceUntilIdle()

        controller.search(SearchFilters(nameQuery = "laporan"))
        // Tanpa menjalankan scheduler: pencarian kedua harus tertolak karena
        // status SEARCHING sudah dipasang secara sinkron oleh pencarian pertama.
        controller.search(SearchFilters(nameQuery = "lain"))
        assertEquals(SearchStatus.SEARCHING, controller.ui.value.status)
        scheduler.advanceUntilIdle()

        val state = controller.ui.value
        assertEquals(
            "hanya pencarian pertama yang dieksekusi",
            setOf("laporan.txt"),
            state.entries.map { it.name }.toSet(),
        )
        assertEquals(1, state.totalMatches)
    }

    @Test
    fun `buildIndex ganda hanya menjalankan satu operasi`() {
        newFile("a.txt", "satu")
        val expected = SearchEngine.buildIndex(root).entries.size
        val controller = newController()
        scheduler.advanceUntilIdle()
        val mutator = TestScope(StandardTestDispatcher(scheduler))

        controller.buildIndex()
        // Belum ada runCurrent: build kedua harus tertolak karena status INDEXING
        // sudah dipasang secara sinkron oleh build pertama (anti-reentrancy).
        controller.buildIndex()
        assertEquals(SearchStatus.INDEXING, controller.ui.value.status)
        // Tugas penulis "b.txt" antre SETELAH build pertama; bila build kedua ikut
        // dieksekusi, ia memindai setelah b.txt ada sehingga jumlahnya berubah.
        mutator.launch { newFile("b.txt", "dua") }
        scheduler.advanceUntilIdle()

        assertEquals("build kedua tidak boleh ikut terhitung", expected, controller.ui.value.indexCount)
        assertEquals(SearchStatus.READY, controller.ui.value.status)
    }

    /** Membuat berkas teks di dalam [root] untuk keperluan indeks. */
    private fun newFile(
        name: String,
        content: String,
    ): File = File(root, name).apply { writeText(content) }

    /**
     * Membangun indeks via engine asli lalu menyimpannya ke [indexFile] (jalur yang
     * sama dengan yang dipakai controller saat build); mengembalikan jumlah entri
     * sebagai ekspektasi jumlah indeks.
     */
    private fun seedIndex(): Int {
        val built = SearchEngine.buildIndex(root)
        SearchIndexStore.write(built.entries, indexFile)
        return built.entries.size
    }

    /** Membuat controller baru di atas scheduler uji yang sama. */
    private fun newController(rootDir: File = root): SearchController =
        SearchController(rootDir, indexFile, StandardTestDispatcher(scheduler))
}
