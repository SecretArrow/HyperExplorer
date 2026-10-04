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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * Uji [SearchEngine] murni JVM: pemasakan indeks (normal, kosong, galat akar,
 * kedalaman/entri/waktu/interrupt budget, folder tak terbaca, symlink loop) dan
 * penyaringan (query, kategori, batas ukuran/tanggal inklusif, kombinasi AND,
 * pemotongan maxResults, urutan deterministik).
 */
class SearchEngineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // ---------------------------------------------------------------- buildIndex

    @Test
    fun `build index pada root kosong menghasilkan indeks kosong`() {
        val root = tmp.newFolder("root")

        val result = SearchEngine.buildIndex(root)

        assertTrue("root kosong harus menghasilkan entries kosong", result.entries.isEmpty())
        assertEquals("tidak ada yang dilewati pada root kosong", 0, result.skippedCount)
        assertFalse("root kosong tidak terpotong", result.truncated)
        assertTrue("durasi harus >= 0", result.durationMs >= 0)
    }

    @Test
    fun `root tidak ada melempar IllegalArgumentException dengan pesan informatif`() {
        val missing = File(tmp.root, "tidak-ada")

        val error =
            assertThrows(IllegalArgumentException::class.java) {
                SearchEngine.buildIndex(missing)
            }
        assertTrue(
            "pesan harus menyebut akar tidak ada, aktual: ${error.message}",
            error.message?.contains("tidak ada") == true,
        )
    }

    @Test
    fun `root berkas bukan direktori melempar IllegalArgumentException dengan pesan informatif`() {
        val plainFile = tmp.newFile("berkas.txt")

        val error =
            assertThrows(IllegalArgumentException::class.java) {
                SearchEngine.buildIndex(plainFile)
            }
        assertTrue(
            "pesan harus menyebut bukan direktori, aktual: ${error.message}",
            error.message?.contains("bukan direktori") == true,
        )
    }

    @Test
    fun `build index nested 3 level berisi metadata lengkap tanpa root`() {
        val root = tmp.newFolder("root")
        val dirA = File(root, "dirA").apply { mkdir() }
        val dirB = File(dirA, "dirB").apply { mkdir() }
        val file1 = File(root, "f1.txt").apply { writeBytes(ByteArray(10)) }
        val image = File(dirA, "a.png").apply { writeBytes(ByteArray(100)) }
        val note = File(dirB, "notes.md").apply { writeBytes(ByteArray(5)) }
        assertTrue("setLastModified harus didukung filesystem uji", image.setLastModified(1_700_000_000_000))

        val result = SearchEngine.buildIndex(root)

        assertEquals("lima entri (2 folder + 3 berkas)", 5, result.entries.size)
        assertFalse("root tidak boleh ikut terindeks", result.entries.any { it.name == root.name })
        assertFalse("pemasakan lengkap tidak boleh terpotong", result.truncated)
        assertEquals("tidak ada folder yang gagal dibaca", 0, result.skippedCount)
        val byName = result.entries.associateBy { it.name }
        val dirEntry = byName["dirA"] ?: error("dirA harus terindeks")
        val imageEntry = byName["a.png"] ?: error("a.png harus terindeks")
        val fileEntry = byName["f1.txt"] ?: error("f1.txt harus terindeks")
        val noteEntry = byName["notes.md"] ?: error("notes.md harus terindeks")
        assertTrue("dirA adalah direktori", dirEntry.isDirectory)
        assertEquals("ukuran direktori selalu 0", 0L, dirEntry.sizeBytes)
        assertEquals("path absolut dirA", dirA.absolutePath, dirEntry.path)
        assertEquals("induk dirA adalah root", root.absolutePath, dirEntry.parent)
        assertEquals("folder tanpa titik punya ekstensi kosong", "", dirEntry.extension)
        assertTrue("a.png bukan direktori", !imageEntry.isDirectory)
        assertEquals("ukuran a.png", 100L, imageEntry.sizeBytes)
        assertEquals("mtime a.png tersimpan utuh", 1_700_000_000_000L, imageEntry.lastModifiedEpochMs)
        assertEquals("ekstensi a.png", "png", imageEntry.extension)
        assertEquals("induk a.png", dirA.absolutePath, imageEntry.parent)
        assertEquals("ukuran f1.txt", 10L, fileEntry.sizeBytes)
        assertEquals("ekstensi notes.md", "md", noteEntry.extension)
        assertEquals("notes.md berada dua level di bawah root", dirB.absolutePath, noteEntry.parent)
    }

    @Test
    fun `nama unicode tersimpan utuh pada path dan name`() {
        val root = tmp.newFolder("root")
        val unicode = File(root, "ñoño 空间.txt").apply { writeText("isi") }

        val result = SearchEngine.buildIndex(root)

        assertEquals("satu entri", 1, result.entries.size)
        val entry = result.entries.first()
        assertEquals("nama unicode utuh", "ñoño 空间.txt", entry.name)
        assertEquals("path unicode utuh", unicode.absolutePath, entry.path)
        assertEquals("ekstensi tetap terbaca", "txt", entry.extension)
    }

    @Test
    fun `kedalaman melebihi maxDepth dilewati dan truncated tanpa skippedCount`() {
        val root = tmp.newFolder("root")
        val l1 = File(root, "l1").apply { mkdir() }
        val l2 = File(l1, "l2").apply { mkdir() }
        val l3 = File(l2, "l3").apply { mkdir() }
        File(l3, "dalam.txt").writeText("x")
        File(l2, "dalam-juga.txt").writeText("x")
        File(l1, "tepat.txt").writeText("x")

        val result = SearchEngine.buildIndex(root, ScanBudget(maxDepth = 2))

        assertTrue("memotong kedalaman harus menandai truncated", result.truncated)
        assertEquals("lewati kedalaman BUKAN skippedCount", 0, result.skippedCount)
        val names = result.entries.map { it.name }.toSet()
        assertTrue("l1 (depth 1) harus ada", "l1" in names)
        assertTrue("l2 (depth 2, tepat di batas) harus ada", "l2" in names)
        assertTrue("berkas depth 2 (tepat.txt) tetap diindeks", "tepat.txt" in names)
        assertFalse("l3 (depth 3) harus dilewati", "l3" in names)
        assertFalse("isi l3 tidak boleh ikut", "dalam.txt" in names)
        assertFalse("berkas depth 3 (dalam-juga.txt) juga dilewati", "dalam-juga.txt" in names)
    }

    @Test
    fun `entry budget memotong hasil dan menandai truncated`() {
        val root = tmp.newFolder("root")
        File(root, "d1").mkdir()
        File(root, "d2").mkdir()
        File(root, "d3").mkdir()
        File(root, "f1.txt").writeText("x")
        File(root, "f2.txt").writeText("x")

        val result = SearchEngine.buildIndex(root, ScanBudget(maxEntries = 2))

        assertEquals("hanya 2 entri pertama (urut nama) yang dikumpulkan", 2, result.entries.size)
        assertTrue("anggaran entri habis harus truncated", result.truncated)
        assertEquals("anak-anak root terurut nama: d1, d2", listOf("d1", "d2"), result.entries.map { it.name })
    }

    @Test
    fun `folder tak terbaca dihitung skippedCount tanpa menjatuhkan pemasakan`() {
        val root = tmp.newFolder("root")
        val locked = File(root, "locked").apply { mkdir() }
        val readable = File(root, "readable.txt").apply { writeText("x") }
        locked.setReadable(false)
        // Lewati bila filesystem tidak menegakkan bit readable (mis. dijalankan sbg root).
        assumeTrue("filesystem tidak menegakkan bit readable", locked.listFiles() == null)
        try {
            val result = SearchEngine.buildIndex(root)

            assertEquals("folder terkunci harus terhitung skippedCount", 1, result.skippedCount)
            assertFalse("pemasakan tetap selesai, bukan truncated", result.truncated)
            val names = result.entries.map { it.name }
            assertTrue("folder terkunci tetap terdaftar sbg entri", "locked" in names)
            assertTrue("berkas setara tetap terindeks", readable.name in names)
        } finally {
            locked.setReadable(true)
        }
    }

    @Test
    fun `symlink melingkar tidak membuat pemasakan menggantung`() {
        val root = tmp.newFolder("root")
        val inner = File(root, "inner").apply { mkdir() }
        File(inner, "isi.txt").writeText("x")
        Files.createSymbolicLink(File(root, "loop").toPath(), root.toPath())

        val result = SearchEngine.buildIndex(root)

        assertFalse("loop harus diputus oleh guard canonical", result.truncated)
        val paths = result.entries.map { it.path }
        assertEquals("setiap entri fisik muncul tepat sekali", paths.size, paths.toSet().size)
        assertEquals(
            "berkas di inner tetap terindeks",
            File(inner, "isi.txt").absolutePath,
            result.entries.first { it.name == "isi.txt" }.path,
        )
    }

    @Test
    fun `anggaran waktu nol menghentikan pemasakan sebelum memproses root`() {
        val root = tmp.newFolder("root")
        File(root, "f.txt").writeText("x")

        val result = SearchEngine.buildIndex(root, ScanBudget(maxDurationMs = 0L))

        assertTrue("anggaran waktu 0 ms harus langsung truncated", result.truncated)
        assertTrue("belum ada entri yang sempat dikumpulkan", result.entries.isEmpty())
    }

    @Test
    fun `thread terputus menghentikan pemasakan dengan truncated`() {
        val root = tmp.newFolder("root")
        File(root, "f.txt").writeText("x")
        try {
            Thread.currentThread().interrupt()

            val result = SearchEngine.buildIndex(root)

            assertTrue("interupsi harus menandai truncated", result.truncated)
            assertTrue("interupsi sebelum memproses apa pun -> entries kosong", result.entries.isEmpty())
        } finally {
            // Bersihkan flag interupsi agar tidak meracuni test lain di thread yang sama.
            Thread.interrupted()
        }
    }

    // ---------------------------------------------------------------- search

    @Test
    fun `search tanpa query cocok semua entri`() {
        val entries =
            listOf(
                entry("/sdcard/a.txt", size = 1L),
                entry("/sdcard/Backup", isDirectory = true),
                entry("/sdcard/b.zip", size = 2L),
            )

        val result = SearchEngine.search(entries, SearchFilters())

        assertEquals("query blank harus cocok semua", 3, result.totalMatches)
        assertEquals(
            "semua entri dikembalikan dengan urutan deterministik (dir dulu)",
            listOf("/sdcard/Backup", "/sdcard/a.txt", "/sdcard/b.zip"),
            result.entries.map { it.path },
        )
        assertFalse("hasil tidak terpotong", result.truncated)
    }

    @Test
    fun `search substring case-insensitive pada nama`() {
        val entries =
            listOf(
                entry("/sdcard/Laporan.TXT"),
                entry("/sdcard/laporan-q1.md"),
                entry("/sdcard/alpha.txt"),
            )

        val result = SearchEngine.search(entries, SearchFilters(nameQuery = "LAPORAN"))

        assertEquals("dua nama mengandung 'laporan' tanpa peduli huruf besar", 2, result.totalMatches)
        assertEquals(
            "entri yang cocok benar",
            listOf("/sdcard/Laporan.TXT", "/sdcard/laporan-q1.md"),
            result.entries.map { it.path },
        )
    }

    @Test
    fun `search kategori IMAGE dan FOLDER memetakan folder selalu FOLDER`() {
        val entries =
            listOf(
                entry("/sdcard/a.png"),
                entry("/sdcard/cat.jpg"),
                entry("/sdcard/notes.txt"),
                entry("/sdcard/Pictures", isDirectory = true),
            )

        val images = SearchEngine.search(entries, SearchFilters(category = FileCategory.IMAGE))
        val folders = SearchEngine.search(entries, SearchFilters(category = FileCategory.FOLDER))

        assertEquals("IMAGE hanya berkas gambar", listOf("/sdcard/a.png", "/sdcard/cat.jpg"), images.entries.map { it.path })
        assertEquals("FOLDER hanya direktori", listOf("/sdcard/Pictures"), folders.entries.map { it.path })
    }

    @Test
    fun `minSizeBytes boundary inklusif`() {
        val entries =
            listOf(
                entry("/sdcard/kecil.bin", size = 99L),
                entry("/sdcard/pas.bin", size = 100L),
            )

        val result = SearchEngine.search(entries, SearchFilters(minSizeBytes = 100L))

        assertEquals("ukuran tepat 100 harus ikut (inklusif)", listOf("/sdcard/pas.bin"), result.entries.map { it.path })
        assertEquals(1, result.totalMatches)
    }

    @Test
    fun `maxSizeBytes boundary inklusif`() {
        val entries =
            listOf(
                entry("/sdcard/pas.bin", size = 100L),
                entry("/sdcard/besar.bin", size = 101L),
            )

        val result = SearchEngine.search(entries, SearchFilters(maxSizeBytes = 100L))

        assertEquals("ukuran tepat 100 harus ikut (inklusif)", listOf("/sdcard/pas.bin"), result.entries.map { it.path })
        assertEquals(1, result.totalMatches)
    }

    @Test
    fun `rentang tanggal boundary inklusif dua sisi`() {
        val entries =
            listOf(
                entry("/sdcard/t1.txt", mtime = 1_000L),
                entry("/sdcard/t2.txt", mtime = 2_000L),
                entry("/sdcard/t3.txt", mtime = 3_000L),
            )

        val all = SearchEngine.search(entries, SearchFilters(modifiedAfterEpochMs = 1_000L, modifiedBeforeEpochMs = 3_000L))
        val afterOnly = SearchEngine.search(entries, SearchFilters(modifiedAfterEpochMs = 2_000L))
        val beforeOnly = SearchEngine.search(entries, SearchFilters(modifiedBeforeEpochMs = 2_000L))

        assertEquals("batas tepat di kedua sisi harus ikut", 3, all.totalMatches)
        assertEquals("modifiedAfter inklusif", listOf("/sdcard/t2.txt", "/sdcard/t3.txt"), afterOnly.entries.map { it.path })
        assertEquals("modifiedBefore inklusif", listOf("/sdcard/t1.txt", "/sdcard/t2.txt"), beforeOnly.entries.map { it.path })
    }

    @Test
    fun `kombinasi filter digabung AND`() {
        val entries =
            listOf(
                entry("/sdcard/laporan.txt", mtime = 100L),
                entry("/sdcard/resep.png", mtime = 100L),
                entry("/sdcard/rekap kecil.txt", mtime = 100L),
                entry("/sdcard/laporan lama.txt", mtime = 50L),
            )

        val result =
            SearchEngine.search(
                entries,
                SearchFilters(nameQuery = "lap", category = FileCategory.DOCUMENT, minSizeBytes = 0L, modifiedAfterEpochMs = 100L),
            )

        assertEquals(
            "hanya laporan.txt: nama cocok + DOCUMENT + >= 0 byte + mtime >= 100",
            listOf("/sdcard/laporan.txt"),
            result.entries.map { it.path },
        )
        assertEquals(1, result.totalMatches)
        assertFalse(result.truncated)
    }

    @Test
    fun `maxResults memotong dengan totalMatches dan truncated benar`() {
        val entries =
            listOf(
                entry("/sdcard/Backup", isDirectory = true),
                entry("/sdcard/a.txt"),
                entry("/sdcard/b.txt"),
                entry("/sdcard/c.txt"),
            )

        val result = SearchEngine.search(entries, SearchFilters(maxResults = 2))

        assertEquals("total kecocokan dihitung sebelum pemotongan", 4, result.totalMatches)
        assertEquals("hanya 2 teratas", 2, result.entries.size)
        assertTrue("ada yang terpotong", result.truncated)
        assertEquals(
            "entri terpotong tetap urut deterministik (dir dulu)",
            listOf("/sdcard/Backup", "/sdcard/a.txt"),
            result.entries.map { it.path },
        )
    }

    @Test
    fun `maxResults nol dan negatif defensif menghasilkan entries kosong`() {
        val entries =
            listOf(
                entry("/sdcard/a.txt"),
                entry("/sdcard/b.txt"),
            )

        val zero = SearchEngine.search(entries, SearchFilters(maxResults = 0))
        val negative = SearchEngine.search(entries, SearchFilters(maxResults = -5))
        val zeroOnEmpty = SearchEngine.search(emptyList(), SearchFilters(maxResults = 0))

        assertEquals("maxResults=0: totalMatches tetap dihitung", 2, zero.totalMatches)
        assertTrue("maxResults=0: entries kosong", zero.entries.isEmpty())
        assertTrue("maxResults=0: truncated karena ada kecocokan", zero.truncated)
        assertEquals("maxResults negatif: totalMatches tetap dihitung", 2, negative.totalMatches)
        assertTrue("maxResults negatif: entries kosong", negative.entries.isEmpty())
        assertTrue("maxResults negatif: truncated", negative.truncated)
        assertFalse("tidak ada kecocokan berarti tidak truncated", zeroOnEmpty.truncated)
    }

    @Test
    fun `urutan deterministik dir dulu nama lowercase asc lalu path tie-break`() {
        val entries =
            listOf(
                entry("/p/B.txt"),
                entry("/r/A.txt"),
                entry("/q/a.txt"),
                entry("/s/zeta", isDirectory = true),
                entry("/t/alpha", isDirectory = true),
            )

        val result = SearchEngine.search(entries, SearchFilters())

        assertEquals(
            "urutan: dir (alpha, zeta) lalu berkas (a.txt /q, a.txt /r via path, b.txt)",
            listOf("/t/alpha", "/s/zeta", "/q/a.txt", "/r/A.txt", "/p/B.txt"),
            result.entries.map { it.path },
        )
    }

    @Test
    fun `search pada daftar kosong menghasilkan hasil kosong tanpa truncated`() {
        val result = SearchEngine.search(emptyList(), SearchFilters(nameQuery = "apa pun"))

        assertTrue("entries kosong", result.entries.isEmpty())
        assertEquals("totalMatches 0", 0, result.totalMatches)
        assertFalse("tidak ada yang terpotong", result.truncated)
    }

    /** Membuat IndexedEntry dengan nama konsisten terhadap path (aturan format indeks). */
    private fun entry(
        path: String,
        isDirectory: Boolean = false,
        size: Long = 0L,
        mtime: Long = 0L,
    ): IndexedEntry =
        IndexedEntry(
            path = path,
            name = path.substringAfterLast('/'),
            isDirectory = isDirectory,
            sizeBytes = size,
            lastModifiedEpochMs = mtime,
        )
}
