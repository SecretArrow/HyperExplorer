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

import com.hyperexplorer.core.common.ConflictStrategy
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Uji [VaultEngine] murni JVM dengan [SoftwareKeyProvider]: roundtrip impor-ekspor
 * streaming, validasi nama, strategi konflik ekspor, integritas GCM (magic,
 * versi, pemotongan, AAD binding, kunci salah), idempotensi hapus, rename,
 * dan ketahanan indeks (self-heal + fail-closed).
 */
class VaultEngineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var vaultDir: File
    private lateinit var sourceDir: File
    private lateinit var engine: VaultEngine

    @Before
    fun setUp() {
        vaultDir = tmp.newFolder("vault")
        sourceDir = tmp.newFolder("sumber")
        engine = VaultEngine(vaultDir, SoftwareKeyProvider(ByteArray(32) { it.toByte() }))
    }

    // ------------------------------------------------------------------ impor

    @Test
    fun `impor lalu ekspor menghasilkan berkas identik byte-per-byte`() {
        val content = Random(42).nextBytes(50_000)

        val outcome = importEntry("rahasia.bin", content, deleteSource = false)
        val exported = valueOf(
            engine.exportEntry(outcome.entry.id, tmp.newFolder("ekspor"), ConflictStrategy.RENAME),
            "ekspor roundtrip",
        )

        assertFalse("sumber tidak boleh dihapus", outcome.sourceDeleted)
        assertArrayEquals("isi ekspor harus identik byte-per-byte", content, exported.readBytes())
        assertEquals("rahasia.bin", exported.name)
    }

    @Test
    fun `impor dengan deleteSource menghapus berkas sumber`() {
        val source = File(sourceDir, "pindah.txt").apply { writeText("isi pindah") }

        val outcome = valueOf(engine.importFile(source, deleteSource = true), "impor dengan hapus sumber")

        assertTrue("sourceDeleted harus true saat penghapusan berhasil", outcome.sourceDeleted)
        assertFalse("berkas sumber harus hilang", source.exists())
        assertEquals(1, entriesNow().size)
    }

    @Test
    fun `impor dengan induk read-only tetap sukses dan sourceDeleted false`() {
        val parent = tmp.newFolder("induk")
        val source = File(parent, "terkunci.txt").apply { writeText("data") }
        parent.setWritable(false)
        try {
            // Bila FS tidak menegakkan bit writable (mis. dijalankan sebagai root),
            // skenario ini tidak dapat disimulasikan dan uji dilewati secara jujur.
            Assume.assumeTrue("bit writable direktori tidak ditegakkan oleh FS", !parent.canWrite())

            val outcome = valueOf(engine.importFile(source, deleteSource = true), "impor dengan sumber terkunci")

            assertFalse("gagal menghapus sumber bukan kegagalan impor", outcome.sourceDeleted)
            assertTrue("sumber tetap ada", source.exists())
            assertEquals("entri tetap terkomit di indeks", 1, entriesNow().size)
        } finally {
            parent.setWritable(true)
        }
    }

    @Test
    fun `berkas nol byte dapat diimpor dan diekspor utuh`() {
        val outcome = importEntry("kosong.bin", ByteArray(0))
        val exported = valueOf(
            engine.exportEntry(outcome.entry.id, tmp.newFolder("ekspor"), ConflictStrategy.RENAME),
            "ekspor berkas kosong",
        )

        assertEquals(0L, outcome.entry.sizeBytes)
        assertEquals(0, exported.length())
        assertEquals(VaultResult.Ok(Unit), engine.verifyEntry(outcome.entry.id))
    }

    @Test
    fun `impor berkas sumber yang tidak ada menghasilkan SourceMissing`() {
        val result = engine.importFile(File(sourceDir, "hilang.txt"), deleteSource = false)

        val error = errorOf(result, "impor tanpa sumber")
        assertTrue("harus SourceMissing, aktual: $error", error is VaultError.SourceMissing)
        assertTrue("path sumber harus disebut", error.toString().contains("hilang.txt"))
    }

    @Test
    fun `impor direktori menghasilkan InvalidInput`() {
        val directory = File(sourceDir, "folder").apply { mkdirs() }

        val error = errorOf(engine.importFile(directory, deleteSource = false), "impor direktori")

        assertTrue("harus InvalidInput, aktual: $error", error is VaultError.InvalidInput)
    }

    @Test
    fun `impor nama blank menghasilkan InvalidInput`() {
        val source = File(sourceDir, "   ").apply { writeText("nama aneh") }

        assertEquals(VaultNameError.BLANK, VaultNames.validate(source.name))
        val error = errorOf(engine.importFile(source, deleteSource = false), "impor nama blank")

        assertTrue("harus InvalidInput, aktual: $error", error is VaultError.InvalidInput)
    }

    @Test
    fun `impor nama memuat backslash menghasilkan InvalidInput`() {
        val source = File(sourceDir, "back\\slash.txt").apply { writeText("nama aneh") }

        assertEquals(VaultNameError.ILLEGAL_CHAR, VaultNames.validate(source.name))
        val error = errorOf(engine.importFile(source, deleteSource = false), "impor nama backslash")

        assertTrue("harus InvalidInput, aktual: $error", error is VaultError.InvalidInput)
    }

    @Test
    fun `rename dengan nama memuat garis miring menghasilkan InvalidInput`() {
        val entry = importEntry("dasar.txt", "x".toByteArray()).entry

        assertEquals(VaultNameError.ILLEGAL_CHAR, VaultNames.validate("ba/d.txt"))
        val error = errorOf(engine.renameEntry(entry.id, "ba/d.txt"), "rename nama garis miring")

        assertTrue("harus InvalidInput, aktual: $error", error is VaultError.InvalidInput)
        assertEquals("nama lama tidak boleh berubah", "dasar.txt", entryNow(entry.id).originalName)
    }

    @Test
    fun `rename dengan nama dua titik tercadang menghasilkan InvalidInput`() {
        val entry = importEntry("dasar.txt", "x".toByteArray()).entry

        assertEquals(VaultNameError.RESERVED, VaultNames.validate(".."))
        val error = errorOf(engine.renameEntry(entry.id, ".."), "rename nama tercadang")

        assertTrue("harus InvalidInput, aktual: $error", error is VaultError.InvalidInput)
    }

    @Test
    fun `nama 256 byte ditolak dan 255 byte diterima`() {
        val entry = importEntry("dasar.txt", "x".toByteArray()).entry
        val tooLong = "n".repeat(256)
        val maxLength = "n".repeat(255)

        assertEquals("256 byte harus ditolak sebagai TOO_LONG", VaultNameError.TOO_LONG, VaultNames.validate(tooLong))
        assertTrue(
            "galat rename 256 byte harus InvalidInput",
            errorOf(engine.renameEntry(entry.id, tooLong), "rename 256 byte") is VaultError.InvalidInput,
        )
        assertNullValidate(maxLength)
        val renamed = valueOf(engine.renameEntry(entry.id, maxLength), "rename 255 byte")
        assertEquals("nama 255 byte harus diterima", maxLength, renamed.originalName)
    }

    @Test
    fun `rename dengan karakter NUL menghasilkan InvalidInput`() {
        val entry = importEntry("dasar.txt", "x".toByteArray()).entry

        assertEquals(VaultNameError.ILLEGAL_CHAR, VaultNames.validate("na\u0000me"))
        val error = errorOf(engine.renameEntry(entry.id, "na\u0000me"), "rename nama NUL")

        assertTrue("harus InvalidInput, aktual: $error", error is VaultError.InvalidInput)
    }

    @Test
    fun `berkas dua mega pseudo-random roundtrip membuktikan streaming`() {
        val content = Random(42).nextBytes(2_000_000)

        val outcome = importEntry("besar.bin", content)
        val exported = valueOf(
            engine.exportEntry(outcome.entry.id, tmp.newFolder("ekspor"), ConflictStrategy.RENAME),
            "ekspor 2MB",
        )

        assertEquals("ukuran plaintext harus tercatat tepat", 2_000_000L, outcome.entry.sizeBytes)
        assertArrayEquals("roundtrip 2MB harus identik (buffer 8KB dipakai berulang)", content, exported.readBytes())
    }

    // ----------------------------------------------------------------- ekspor

    @Test
    fun `ekspor sukses mempertahankan nama asli berkas`() {
        val entry = importEntry("laporan keuangan.txt", "angka".toByteArray()).entry

        val exported = valueOf(
            engine.exportEntry(entry.id, tmp.newFolder("ekspor"), ConflictStrategy.RENAME),
            "ekspor sukses",
        )

        assertEquals("nama asli harus dipertahankan", entry.originalName, exported.name)
        assertEquals("angka", exported.readText())
    }

    @Test
    fun `ekspor ke direktori tujuan tidak ada menghasilkan InvalidInput`() {
        val entry = importEntry("ada.txt", "x".toByteArray()).entry
        val missingDir = File(tmp.root, "tidak-ada")

        val error = errorOf(engine.exportEntry(entry.id, missingDir, ConflictStrategy.RENAME), "ekspor tujuan hilang")

        assertTrue("harus InvalidInput, aktual: $error", error is VaultError.InvalidInput)
    }

    @Test
    fun `ekspor ke tujuan berupa berkas biasa menghasilkan InvalidInput`() {
        val entry = importEntry("ada.txt", "x".toByteArray()).entry
        val notADirectory = File(tmp.root, "biasa.txt").apply { writeText("bukan direktori") }

        val error = errorOf(engine.exportEntry(entry.id, notADirectory, ConflictStrategy.RENAME), "ekspor ke berkas")

        assertTrue("harus InvalidInput, aktual: $error", error is VaultError.InvalidInput)
    }

    @Test
    fun `ekspor OVERWRITE menimpa isi target lama`() {
        val entry = importEntry("target.txt", "BARU".toByteArray()).entry
        val destDir = tmp.newFolder("ekspor")
        File(destDir, "target.txt").writeText("LAMA-YANG-HARUS-HILANG")

        val exported = valueOf(engine.exportEntry(entry.id, destDir, ConflictStrategy.OVERWRITE), "ekspor OVERWRITE")

        assertEquals("isi lama harus tertimpa", "BARU", exported.readText())
        assertEquals("target.txt", exported.name)
    }

    @Test
    fun `ekspor RENAME menghasilkan nama bernomor 1`() {
        val entry = importEntry("catatan.txt", "isi".toByteArray()).entry
        val destDir = tmp.newFolder("ekspor")

        val first = valueOf(engine.exportEntry(entry.id, destDir, ConflictStrategy.RENAME), "ekspor pertama")
        val second = valueOf(engine.exportEntry(entry.id, destDir, ConflictStrategy.RENAME), "ekspor kedua")

        assertEquals("catatan.txt", first.name)
        assertEquals("konflik pertama harus menjadi varian (1)", "catatan (1).txt", second.name)
    }

    @Test
    fun `ekspor RENAME dua kali menghasilkan nama bernomor 2`() {
        val entry = importEntry("catatan.txt", "isi".toByteArray()).entry
        val destDir = tmp.newFolder("ekspor")

        val names = (1..3).map { run ->
            valueOf(engine.exportEntry(entry.id, destDir, ConflictStrategy.RENAME), "ekspor ke-$run").name
        }

        assertEquals(listOf("catatan.txt", "catatan (1).txt", "catatan (2).txt"), names)
    }

    @Test
    fun `ekspor SKIP dengan target ada mengembalikan berkas lama tanpa perubahan`() {
        val entry = importEntry("target.txt", "BARU".toByteArray()).entry
        val destDir = tmp.newFolder("ekspor")
        val oldTarget = File(destDir, "target.txt").apply { writeText("LAMA") }

        val exported = valueOf(engine.exportEntry(entry.id, destDir, ConflictStrategy.SKIP), "ekspor SKIP")

        assertEquals("SKIP harus mengembalikan target lama", oldTarget.absolutePath, exported.absolutePath)
        assertEquals("isi target lama tidak boleh berubah", "LAMA", exported.readText())
    }

    @Test
    fun `ekspor id asing menghasilkan EntryNotFound`() {
        importEntry("ada.txt", "x".toByteArray())
        val destDir = tmp.newFolder("ekspor")

        val error = errorOf(engine.exportEntry("id-tidak-ada", destDir, ConflictStrategy.RENAME), "ekspor id asing")

        assertTrue("harus EntryNotFound, aktual: $error", error is VaultError.EntryNotFound)
    }

    // ------------------------------------------------------------- integritas

    @Test
    fun `blob dengan magic dirusak menghasilkan CorruptEntry`() {
        val entry = importEntry("korup-magic.txt", "payload".toByteArray()).entry
        mutateBlob(entry) { bytes ->
            bytes.also { it[0] = 'X'.code.toByte() }
        }

        val error = errorOf(engine.verifyEntry(entry.id), "verify magic rusak")

        assertTrue("harus CorruptEntry, aktual: $error", error is VaultError.CorruptEntry)
    }

    @Test
    fun `blob dengan versi 2 menghasilkan CorruptEntry`() {
        val entry = importEntry("korup-versi.txt", "payload".toByteArray()).entry
        mutateBlob(entry) { bytes ->
            bytes.also { it[4] = 2 }
        }

        val error = errorOf(engine.verifyEntry(entry.id), "verify versi 2")

        assertTrue("harus CorruptEntry, aktual: $error", error is VaultError.CorruptEntry)
    }

    @Test
    fun `blob terpotong di header maupun di tengah payload menghasilkan CorruptEntry`() {
        val headEntry = importEntry("potong-header.bin", "kecil".toByteArray()).entry
        mutateBlob(headEntry) { bytes -> bytes.copyOfRange(0, 10) }
        val headError = errorOf(engine.verifyEntry(headEntry.id), "verify terpotong <19 byte")
        assertTrue("terpotong <19 byte harus CorruptEntry, aktual: $headError", headError is VaultError.CorruptEntry)

        val payloadEntry = importEntry("potong-payload.bin", Random(7).nextBytes(4_096)).entry
        mutateBlob(payloadEntry) { bytes -> bytes.copyOfRange(0, bytes.size / 2) }
        val payloadError = errorOf(engine.verifyEntry(payloadEntry.id), "verify terpotong di payload")
        assertTrue("terpotong di tengah payload harus CorruptEntry, aktual: $payloadError", payloadError is VaultError.CorruptEntry)
    }

    @Test
    fun `satu byte payload diubah menghasilkan CorruptEntry`() {
        val entry = importEntry("flip.bin", Random(11).nextBytes(1_024)).entry
        mutateBlob(entry) { bytes ->
            bytes.also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
        }

        val error = errorOf(engine.verifyEntry(entry.id), "verify satu byte diubah")

        assertTrue("tag GCM harus gagal, aktual: $error", error is VaultError.CorruptEntry)
    }

    @Test
    fun `wrapped DEK ditukar antar dua entri menghasilkan CorruptEntry karena AAD binding`() {
        val first = importEntry("satu.txt", "satu".toByteArray()).entry
        val second = importEntry("dua.txt", "dua".toByteArray()).entry
        val bytes1 = blobOf(first).readBytes()
        val bytes2 = blobOf(second).readBytes()
        val wrappedLength = wrappedDekLength(bytes1)
        assertEquals("panjang wrapped DEK kedua blob harus sama agar bisa ditukar", wrappedLength, wrappedDekLength(bytes2))
        val swapped1 = bytes1.copyOf()
        val swapped2 = bytes2.copyOf()
        System.arraycopy(bytes2, HEADER_SIZE_BYTES, swapped1, HEADER_SIZE_BYTES, wrappedLength)
        System.arraycopy(bytes1, HEADER_SIZE_BYTES, swapped2, HEADER_SIZE_BYTES, wrappedLength)
        blobOf(first).writeBytes(swapped1)
        blobOf(second).writeBytes(swapped2)

        val error = errorOf(engine.verifyEntry(first.id), "verify setelah DEK ditukar")

        assertTrue("AAD harus mengikat DEK ke entri pemiliknya, aktual: $error", error is VaultError.CorruptEntry)
    }

    @Test
    fun `kunci master berbeda menghasilkan galat dekripsi`() {
        val entry = importEntry("rahasia.txt", "isi".toByteArray()).entry
        val wrongKeyEngine = VaultEngine(vaultDir, SoftwareKeyProvider(ByteArray(32) { (it + 7).toByte() }))

        val error = errorOf(wrongKeyEngine.verifyEntry(entry.id), "verify dengan kunci salah")

        assertTrue(
            "harus CorruptEntry atau KeyUnavailable, aktual: $error",
            error is VaultError.CorruptEntry || error is VaultError.KeyUnavailable,
        )
    }

    @Test
    fun `verify entri sehat sukses lalu blob dirusak menghasilkan CorruptEntry`() {
        val entry = importEntry("utuh.bin", Random(5).nextBytes(2_048)).entry

        assertEquals("verify entri sehat harus Ok", VaultResult.Ok(Unit), engine.verifyEntry(entry.id))

        mutateBlob(entry) { bytes ->
            bytes.also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0x55).toByte() }
        }
        val error = errorOf(engine.verifyEntry(entry.id), "verify setelah korupsi")

        assertTrue("harus CorruptEntry, aktual: $error", error is VaultError.CorruptEntry)
    }

    // ------------------------------------------------------------------ hapus

    @Test
    fun `hapus entri idempoten dan id asing ditolak`() {
        val first = importEntry("satu.txt", "1".toByteArray()).entry

        assertEquals("hapus pertama harus Ok", VaultResult.Ok(Unit), engine.deleteEntry(first.id))
        assertFalse("blob harus ikut terhapus", blobOf(first).exists())
        assertTrue("indeks harus kosong setelah hapus", entriesNow().isEmpty())
        assertTrue(
            "hapus id yang sama lagi harus EntryNotFound",
            errorOf(engine.deleteEntry(first.id), "hapus ulang") is VaultError.EntryNotFound,
        )

        val second = importEntry("dua.txt", "2".toByteArray()).entry
        assertTrue("blob kedua harus ada sebelum dihapus manual", blobOf(second).exists())
        assertTrue(blobOf(second).delete())
        assertEquals("hapus tanpa blob tetap Ok (idempoten)", VaultResult.Ok(Unit), engine.deleteEntry(second.id))
        assertTrue(entriesNow().isEmpty())
    }

    // ----------------------------------------------------------------- rename

    @Test
    fun `rename valid memperbarui nama tanpa mengubah blob`() {
        val entry = importEntry("lama.txt", "isi lama".toByteArray()).entry
        val blobBytesBefore = blobOf(entry).readBytes()

        val renamed = valueOf(engine.renameEntry(entry.id, "baru.txt"), "rename valid")

        assertEquals("nama baru harus tersimpan", "baru.txt", renamed.originalName)
        assertEquals("storedFileName tidak boleh berubah", entry.storedFileName, renamed.storedFileName)
        assertEquals("indeks harus memuat nama baru", "baru.txt", entryNow(entry.id).originalName)
        assertArrayEquals("blob tidak boleh tersentuh", blobBytesBefore, blobOf(entry).readBytes())

        assertTrue(
            "rename ke nama sama case-insensitive harus InvalidInput",
            errorOf(engine.renameEntry(entry.id, "BARU.TXT"), "rename sama case") is VaultError.InvalidInput,
        )
        assertTrue(
            "rename ke nama blank harus InvalidInput",
            errorOf(engine.renameEntry(entry.id, "   "), "rename blank") is VaultError.InvalidInput,
        )
    }

    // ---------------------------------------------------------------- indeks

    @Test
    fun `engine baru atas direktori yang sama membaca indeks dengan konsisten`() {
        val first = importEntry("pertama.txt", "satu".toByteArray()).entry
        val second = importEntry("kedua.txt", "dua".toByteArray()).entry
        val freshEngine = VaultEngine(vaultDir, SoftwareKeyProvider(ByteArray(32) { it.toByte() }))

        val entries = valueOf(freshEngine.listEntries(), "listEntries engine baru")
        val exported = valueOf(
            freshEngine.exportEntry(second.id, tmp.newFolder("ekspor"), ConflictStrategy.RENAME),
            "ekspor engine baru",
        )

        assertEquals("kedua entri harus terbaca lintas instance", setOf(first.id, second.id), entries.map { it.id }.toSet())
        assertArrayEquals("engine baru harus dapat mendekripsi", "dua".toByteArray(), exported.readBytes())
    }

    @Test
    fun `indeks utama rusak dipulihkan otomatis dari cadangan`() {
        val first = importEntry("pertama.txt", "satu".toByteArray()).entry
        importEntry("kedua.txt", "dua".toByteArray())
        File(vaultDir, VaultIndex.INDEX_FILE_NAME).writeBytes("RUSAK-TOTAL".toByteArray())

        val entries = valueOf(engine.listEntries(), "listEntries dengan utama rusak")

        assertEquals("cadangan memuat versi dengan satu entri", listOf(first.id), entries.map { it.id })
        assertEquals(
            "utama harus dipulihkan dari cadangan",
            VaultResult.Ok(entries),
            engine.listEntries(),
        )
        assertTrue("blob entri kedua tidak boleh disentuh oleh pemulihan", blobOf(entriesNow().first()).exists())
    }

    @Test
    fun `indeks utama dan cadangan rusak menolak operasi tanpa merusak blob lama`() {
        val first = importEntry("pertama.txt", "satu".toByteArray()).entry
        val second = importEntry("kedua.txt", "dua".toByteArray()).entry
        val blob1 = blobOf(first).readBytes()
        val blob2 = blobOf(second).readBytes()
        File(vaultDir, VaultIndex.INDEX_FILE_NAME).writeBytes("RUSAK-UTAMA".toByteArray())
        File(vaultDir, VaultIndex.INDEX_FILE_NAME + VaultIndex.BACKUP_SUFFIX).writeBytes("RUSAK-CADANGAN".toByteArray())

        val listError = errorOf(engine.listEntries(), "listEntries indeks rusak total")
        assertTrue("harus IndexCorrupt, aktual: $listError", listError is VaultError.IndexCorrupt)

        val importError = errorOf(
            engine.importFile(File(sourceDir, "ketiga.txt").apply { writeText("3") }, deleteSource = false),
            "impor saat indeks rusak",
        )
        assertTrue("impor harus fail-closed, aktual: $importError", importError is VaultError.IndexCorrupt)

        assertArrayEquals("blob pertama tidak boleh disentuh", blob1, blobOf(first).readBytes())
        assertArrayEquals("blob kedua tidak boleh disentuh", blob2, blobOf(second).readBytes())
    }

    @Test
    fun `vault baru tanpa indeks menghasilkan daftar kosong`() {
        assertTrue("vault baru harus punya daftar kosong", entriesNow().isEmpty())
    }

    // ---------------------------------------------------------------- helper

    /** Offset byte wrapped DEK di header blob: magic 4 + versi 1 + nonce 12 + wrapMethod 1 + u16 len 2. */
    private companion object {
        const val HEADER_SIZE_BYTES = 4 + 1 + VaultFormat.CONTENT_NONCE_SIZE + 1 + 2
    }

    /** Membuat berkas sumber bernama [name] berisi [content] lalu mengimpornya. */
    private fun importEntry(name: String, content: ByteArray, deleteSource: Boolean = false): ImportOutcome {
        val source = File(sourceDir, name).apply { writeBytes(content) }
        return valueOf(engine.importFile(source, deleteSource), "impor $name")
    }

    /** Mengambil nilai [VaultResult.Ok] atau menggagalkan uji bila hasilnya Err. */
    private fun <T> valueOf(result: VaultResult<T>, context: String): T =
        (result as? VaultResult.Ok)?.value ?: throw AssertionError("$context seharusnya sukses, aktual: $result")

    /** Mengambil [VaultError] dari [VaultResult.Err] atau menggagalkan uji bila hasilnya Ok. */
    private fun errorOf(result: VaultResult<*>, context: String): VaultError =
        (result as? VaultResult.Err)?.error ?: throw AssertionError("$context seharusnya gagal, aktual: $result")

    /** Daftar entri saat ini; gagalkan uji bila listEntries gagal. */
    private fun entriesNow(): List<VaultEntry> = valueOf(engine.listEntries(), "listEntries")

    /** Entri dengan id [id]; gagalkan uji bila tidak ditemukan. */
    private fun entryNow(id: String): VaultEntry {
        val found = entriesNow().firstOrNull { it.id == id }
        return found ?: throw AssertionError("entri $id harus ada di indeks, aktual: ${entriesNow()}")
    }

    /** Berkas blob milik [entry] di dalam direktori vault. */
    private fun blobOf(entry: VaultEntry): File = File(vaultDir, entry.storedFileName)

    /** Menulis ulang blob milik [entry] dengan byte hasil [transform]. */
    private fun mutateBlob(entry: VaultEntry, transform: (ByteArray) -> ByteArray) {
        val blob = blobOf(entry)
        blob.writeBytes(transform(blob.readBytes()))
    }

    /** Panjang wrapped DEK di header blob: u16 big-endian pada offset 18..19. */
    private fun wrappedDekLength(bytes: ByteArray): Int =
        ((bytes[18].toInt() and 0xFF) shl 8) or (bytes[19].toInt() and 0xFF)

    /** Menegaskan [name] valid menurut [VaultNames] (bantuan kasus 255 byte). */
    private fun assertNullValidate(name: String) {
        val error = VaultNames.validate(name)
        assertEquals("nama ${name.toByteArray().size} byte harus valid, aktual: $error", null, error)
    }
}
