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

package com.hyperexplorer.feature.tools.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Uji codec indeks biner vault ([VaultIndex]) murni JVM: roundtrip tulis-baca
 * penuh, penolakan struktur rusak (magic, versi, count negatif, byte sisa),
 * dan validnya string kosong pada metadata.
 */
class VaultIndexTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `tulis lalu baca kembali menghasilkan entri identik`() {
        val entries =
            listOf(
                entry("a1b2c3d4", "laporan-ñ.txt", addedAt = 1_700_000_000_000),
                entry("e5f6a7b8", "foto liburan.png", addedAt = 1_700_000_000_001),
            )
        val indexFile = File(tmp.root, VaultIndex.INDEX_FILE_NAME)

        VaultIndex.writeAtomic(indexFile, entries)
        val readBack = VaultIndex.read(indexFile)

        assertEquals(entries, readBack)
        assertFalse("berkas sementara harus dibersihkan setelah commit", File(tmp.root, "index.bin.tmp").exists())
    }

    @Test
    fun `string kosong pada metadata tetap valid`() {
        val entries =
            listOf(
                entry("kosong1", "nama.txt", originalPath = "", mimeType = ""),
                entry("kosong2", "", addedAt = 1_700_000_000_002),
            )
        val indexFile = File(tmp.root, VaultIndex.INDEX_FILE_NAME)

        VaultIndex.writeAtomic(indexFile, entries)

        assertEquals("string kosong harus roundtrip utuh", entries, VaultIndex.read(indexFile))
    }

    @Test
    fun `magic salah ditolak`() {
        val indexFile = validIndex()
        val bytes = indexFile.readBytes()
        bytes[0] = 'X'.code.toByte()
        indexFile.writeBytes(bytes)

        expectFormatError(indexFile, "magic")
    }

    @Test
    fun `versi salah ditolak`() {
        val indexFile = validIndex()
        val bytes = indexFile.readBytes()
        bytes[4] = 2
        indexFile.writeBytes(bytes)

        expectFormatError(indexFile, "versi")
    }

    @Test
    fun `count negatif ditolak`() {
        val indexFile = validIndex()
        val bytes = indexFile.readBytes()
        // i32 count big-endian pada offset 5..8 ditimpa dengan -1 (0xFFFFFFFF).
        for (offset in 5..8) bytes[offset] = 0xFF.toByte()
        indexFile.writeBytes(bytes)

        expectFormatError(indexFile, "jumlah entri")
    }

    @Test
    fun `byte sisa setelah entri ditolak`() {
        val indexFile = validIndex()

        indexFile.appendBytes(0.toByte())

        expectFormatError(indexFile, "sisa")
    }

    /** Menulis indeks valid berisi satu entri dan mengembalikan berkasnya. */
    private fun validIndex(): File {
        val indexFile = File(tmp.root, VaultIndex.INDEX_FILE_NAME)
        VaultIndex.writeAtomic(indexFile, listOf(entry("ab12cd34", "berkas.txt")))
        return indexFile
    }

    /** Memastikan [VaultIndex.read] gagal dengan alasan yang menyebut [detail]. */
    private fun expectFormatError(
        indexFile: File,
        detail: String,
    ) {
        try {
            VaultIndex.read(indexFile)
            throw AssertionError("read seharusnya gagal untuk ${indexFile.path}")
        } catch (e: VaultIndexFormatException) {
            assertTrue(
                "pesan galat harus menyebut \"$detail\", aktual: ${e.message}",
                e.message?.contains(detail) == true,
            )
        }
    }

    /** Membuat entri contoh dengan nilai deterministik. */
    private fun entry(
        id: String,
        name: String,
        addedAt: Long = 1_700_000_000_000,
        originalPath: String = "/sumber/$name",
        mimeType: String = "application/octet-stream",
    ): VaultEntry =
        VaultEntry(
            id = id,
            storedFileName = "$id.hve",
            originalName = name,
            originalPath = originalPath,
            mimeType = mimeType,
            sizeBytes = 42L,
            addedAtEpochMs = addedAt,
            wrapMethod = WrapMethod.KEYSTORE,
        )
}
