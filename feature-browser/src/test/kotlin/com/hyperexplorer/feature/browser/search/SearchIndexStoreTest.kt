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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * Uji [SearchIndexStore] murni JVM: roundtrip biner (unicode + spasi), tulis atomik
 * (.tmp dibersihkan, overwrite, daftar kosong), penolakan berkas rusak (magic,
 * versi, CRC, terpotong header/entri, count negatif, flag tidak sah), dan
 * fail-safe [SearchIndexStore.readOrEmpty].
 */
class SearchIndexStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `roundtrip tiga entri dgn unicode dan spasi menghasilkan data identik`() {
        val entries =
            listOf(
                entry("/data/ñ folder/berkas lama.txt", size = 1_234L, mtime = 1_700_000_000_000L),
                entry("/data/Pictures", isDirectory = true),
                entry("/data/lain.bin", size = 42L, mtime = 1_700_000_000_001L),
            )
        val indexFile = File(tmp.root, "index.bin")

        SearchIndexStore.write(entries, indexFile)
        val readBack = SearchIndexStore.read(indexFile)

        assertEquals("seluruh entri harus roundtrip utuh", entries, readBack)
        val first = readBack.first()
        assertEquals("induk turunan harus benar", "/data/ñ folder", first.parent)
        assertEquals("ekstensi turunan harus benar", "txt", first.extension)
        assertFalse("berkas sementara harus dibersihkan", File(tmp.root, "index.bin.tmp").exists())
    }

    @Test
    fun `read pada berkas tak ada melempar IOException berpesan tidak ditemukan`() {
        val missing = File(tmp.root, "hilang.bin")

        val error =
            assertThrows(IOException::class.java) {
                SearchIndexStore.read(missing)
            }
        assertTrue(
            "pesan harus menyebut berkas tidak ditemukan, aktual: ${error.message}",
            error.message?.contains("tidak ditemukan") == true,
        )
    }

    @Test
    fun `readOrEmpty pada berkas tak ada mengembalikan list kosong`() {
        val missing = File(tmp.root, "hilang.bin")

        val result = SearchIndexStore.readOrEmpty(missing)

        assertTrue("fail-safe harus mengembalikan list kosong", result.isEmpty())
    }

    @Test
    fun `write ke direktori induk tak ada atau tanpa induk melempar IOException`() {
        val missingParent = File(File(tmp.root, "tidak-ada"), "index.bin")
        val noParent = File("index-tanpa-induk.bin")

        val error =
            assertThrows(IOException::class.java) {
                SearchIndexStore.write(emptyList(), missingParent)
            }
        assertTrue(
            "pesan harus menyebut direktori induk, aktual: ${error.message}",
            error.message?.contains("direktori induk") == true,
        )
        assertThrows("target tanpa berkas induk juga harus ditolak", IOException::class.java) {
            SearchIndexStore.write(emptyList(), noParent)
        }
    }

    @Test
    fun `magic dirusak ditolak dengan pesan magic`() {
        val indexFile = validIndexFile()
        val bytes = indexFile.readBytes()
        bytes[0] = 'X'.code.toByte()
        indexFile.writeBytes(bytes)

        expectFormatError(indexFile, "magic")
    }

    @Test
    fun `versi 2 ditolak dengan pesan versi`() {
        val indexFile = validIndexFile()
        val bytes = indexFile.readBytes()
        // u16 versi big-endian pada offset 4..5; 0x0001 -> 0x0002.
        bytes[5] = 2
        indexFile.writeBytes(bytes)

        expectFormatError(indexFile, "versi")
    }

    @Test
    fun `payload dirusak ditolak dengan pesan CRC`() {
        val indexFile = validIndexFile()
        val bytes = indexFile.readBytes()
        // Ubah satu byte di kolom sizeBytes (offset 24) — struktur tetap valid saat parse,
        // sehingga galat yang muncul harus berasal dari verifikasi CRC, bukan EOF.
        bytes[24] = (bytes[24].toInt() xor 0x01).toByte()
        indexFile.writeBytes(bytes)

        expectFormatError(indexFile, "CRC")
    }

    @Test
    fun `berkas lebih pendek dari header minimal ditolak sbg terpotong`() {
        val indexFile = validIndexFile()
        val bytes = indexFile.readBytes()
        indexFile.writeBytes(bytes.copyOfRange(0, 10))

        expectFormatError(indexFile, "terpotong")
    }

    @Test
    fun `berkas terpotong di tengah entri ditolak sbg terpotong`() {
        val indexFile = validIndexFile()
        val bytes = indexFile.readBytes()
        indexFile.writeBytes(bytes.copyOfRange(0, bytes.size - 3))

        expectFormatError(indexFile, "terpotong")
    }

    @Test
    fun `count negatif ditolak dengan pesan jumlah entri`() {
        val indexFile = validIndexFile()
        val bytes = indexFile.readBytes()
        // i32 count big-endian pada offset 6..9 ditimpa -1 (0xFFFFFFFF).
        for (offset in 6..9) {
            bytes[offset] = 0xFF.toByte()
        }
        indexFile.writeBytes(bytes)

        expectFormatError(indexFile, "jumlah entri")
    }

    @Test
    fun `flag byte 2 ditolak dengan pesan flag`() {
        val indexFile = validIndexFile()
        val bytes = indexFile.readBytes()
        // Header 10 byte + u16 panjang path (2) + path "/data/a.txt" (11) = flag di offset 23.
        assertEquals("asumsi offset flag harus benar", "/data/a.txt".toByteArray(Charsets.UTF_8).size, 11)
        bytes[23] = 2
        indexFile.writeBytes(bytes)

        expectFormatError(indexFile, "flag")
    }

    @Test
    fun `write berulang menimpa tanpa meninggalkan tmp dan mendukung daftar kosong`() {
        val indexFile = File(tmp.root, "index.bin")
        val first = listOf(entry("/data/a.txt", size = 1L), entry("/data/b.txt", size = 2L))
        val second = listOf(entry("/data/ganti.txt", size = 3L))

        SearchIndexStore.write(first, indexFile)
        assertFalse("tidak ada .tmp setelah tulis pertama", File(tmp.root, "index.bin.tmp").exists())
        SearchIndexStore.write(second, indexFile)
        assertEquals("tulis kedua harus menimpa isi lama", second, SearchIndexStore.read(indexFile))
        assertFalse("tidak ada .tmp setelah overwrite", File(tmp.root, "index.bin.tmp").exists())
        SearchIndexStore.write(emptyList(), indexFile)
        assertTrue("roundtrip daftar kosong menghasilkan indeks kosong", SearchIndexStore.read(indexFile).isEmpty())
        assertEquals(
            "folder target hanya berisi berkas indeks (tanpa jejak .tmp)",
            listOf("index.bin"),
            tmp.root
                .listFiles()
                ?.map { it.name }
                ?.sorted(),
        )
    }

    @Test
    fun `readOrEmpty pada berkas rusak mengembalikan list kosong tanpa melempar`() {
        val indexFile = validIndexFile()
        val bytes = indexFile.readBytes()
        bytes[0] = 'X'.code.toByte()
        indexFile.writeBytes(bytes)

        val result = SearchIndexStore.readOrEmpty(indexFile)

        assertTrue("indeks rusak harus self-heal menjadi kosong", result.isEmpty())
    }

    @Test
    fun `readOrEmpty pada berkas kosong 0 byte mengembalikan list kosong`() {
        val emptyFile = File(tmp.root, "kosong.bin").apply { writeBytes(ByteArray(0)) }

        val result = SearchIndexStore.readOrEmpty(emptyFile)

        assertTrue("berkas 0 byte adalah indeks tidak valid -> kosong tanpa lempar", result.isEmpty())
    }

    /** Menulis indeks valid berisi satu entri ASCII "/data/a.txt" dan mengembalikan berkasnya. */
    private fun validIndexFile(): File {
        val indexFile = File(tmp.root, "index.bin")
        SearchIndexStore.write(listOf(entry("/data/a.txt", size = 42L, mtime = 1_700_000_000_000L)), indexFile)
        return indexFile
    }

    /** Memastikan [SearchIndexStore.read] gagal sbg [IndexFormatException] yang menyebut [detail]. */
    private fun expectFormatError(
        indexFile: File,
        detail: String,
    ) {
        try {
            SearchIndexStore.read(indexFile)
            throw AssertionError("read seharusnya gagal untuk ${indexFile.path}")
        } catch (e: IndexFormatException) {
            assertTrue(
                "pesan galat harus menyebut \"$detail\", aktual: ${e.message}",
                e.message?.contains(detail) == true,
            )
        }
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
