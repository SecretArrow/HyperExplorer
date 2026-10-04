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

package com.hyperexplorer.feature.tools.zip

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

class ZipCryptoTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val engine = ZipCrypto(Dispatchers.Unconfined)
    private val password = "rahasia".toCharArray()

    @Test
    fun `zip encrypted roundtrip preserves files and nested folder byte per byte`() =
        runBlocking {
            val sourceDir = tmp.newFolder("source")
            val sources = createSourceTree(sourceDir)
            val targetZip = File(tmp.newFolder("out"), "arsip.zip")

            val zipResult = engine.zipFilesEncrypted(sources, targetZip, password)

            assertTrue(zipResult.isSuccess)
            assertEquals(targetZip.absolutePath, zipResult.getOrNull()?.absolutePath)
            assertTrue(zipResult.getOrNull()?.exists() == true)

            // Hapus sumber lalu ekstrak ke folder yang belum ada (dibuat otomatis oleh engine).
            sourceDir.deleteRecursively()
            val extractDir = File(tmp.root, "extract")

            val unzipResult = engine.unzip(targetZip, extractDir, password)

            assertTrue(unzipResult.isSuccess)
            // Entri: a.txt, b.bin, folder/ (dari includeRootFolder), folder/inner.txt.
            assertEquals(4, unzipResult.getOrNull())
            assertArrayEquals("teks biasa".toByteArray(), File(extractDir, "a.txt").readBytes())
            assertArrayEquals("isi dalam folder".toByteArray(), File(extractDir, "folder/inner.txt").readBytes())
            assertArrayEquals(Random(42).nextBytes(8_192), File(extractDir, "b.bin").readBytes())
            assertTrue(File(extractDir, "folder").isDirectory)
        }

    @Test
    fun `unzip with wrong password fails with security exception`() =
        runBlocking {
            val targetZip = createEncryptedArchive("wrongpw")
            val targetDir = tmp.newFolder("target")

            val result = engine.unzip(targetZip, targetDir, "salah".toCharArray())

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is SecurityException)
            assertEquals("Wrong password or corrupted archive", result.exceptionOrNull()?.message)
            // Entri pertama adalah berkas terenkripsi: gagal saat header lokal dibaca,
            // sebelum ada byte yang ditulis, sehingga folder tujuan tetap kosong.
            assertTrue(targetDir.listFiles().isNullOrEmpty())
        }

    @Test
    fun `zipFilesEncrypted rejects empty password before touching filesystem`() =
        runBlocking {
            val sources = createSourceTree(tmp.newFolder("source"))
            val targetZip = File(tmp.root, "not-created.zip")

            // Kontrak berbasis Result: IAE dibungkus Result.failure, bukan dilempar.
            val result = engine.zipFilesEncrypted(sources, targetZip, CharArray(0))

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
            assertEquals("Password is required for encrypted ZIP", result.exceptionOrNull()?.message)
            assertFalse(targetZip.exists()) // gagal cepat: tidak ada I/O yang dilakukan
        }

    @Test
    fun `unzip rejects empty password`() =
        runBlocking {
            val targetZip = createEncryptedArchive("nopw")
            val targetDir = tmp.newFolder("target")

            // Kontrak berbasis Result: IAE dibungkus Result.failure, bukan dilempar.
            val result = engine.unzip(targetZip, targetDir, CharArray(0))

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
            assertEquals("Password is required for encrypted ZIP", result.exceptionOrNull()?.message)
        }

    @Test
    fun `zipFilesEncrypted rejects whitespace only password`() =
        runBlocking {
            val sources = createSourceTree(tmp.newFolder("source"))
            val targetZip = File(tmp.root, "not-created-either.zip")

            // Kontrak berbasis Result: IAE dibungkus Result.failure, bukan dilempar.
            val result = engine.zipFilesEncrypted(sources, targetZip, "   ".toCharArray())

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
            assertEquals("Password is required for encrypted ZIP", result.exceptionOrNull()?.message)
            assertFalse(targetZip.exists())
        }

    @Test
    fun `unzip on missing archive fails with io exception`() =
        runBlocking {
            val missingZip = File(tmp.root, "absent.zip")
            val targetDir = tmp.newFolder("target")

            val result = engine.unzip(missingZip, targetDir, password)

            assertTrue(result.isFailure)
            val exception = result.exceptionOrNull()
            assertTrue(exception is IOException)
            assertEquals("Encrypted ZIP not found: ${missingZip.absolutePath}", exception?.message)
        }

    @Test
    fun `zipFilesEncrypted skips missing sources and still archives valid ones`() =
        runBlocking {
            val sourceDir = tmp.newFolder("source")
            val valid = File(sourceDir, "valid.txt").apply { writeText("konten valid") }
            val targetZip = File(tmp.newFolder("out"), "arsip.zip")
            val sources = listOf(valid, File(sourceDir, "missing.txt"), targetZip)

            val zipResult = engine.zipFilesEncrypted(sources, targetZip, password)

            assertTrue(zipResult.isSuccess)
            assertTrue(targetZip.exists())

            val extractDir = tmp.newFolder("extract")
            val unzipResult = engine.unzip(targetZip, extractDir, password)

            assertTrue(unzipResult.isSuccess)
            // Hanya valid.txt diekstrak; arsip target sendiri dan sumber hilang dilewati.
            assertEquals(1, unzipResult.getOrNull())
            assertArrayEquals("konten valid".toByteArray(), File(extractDir, "valid.txt").readBytes())
        }

    @Test
    fun `unzip rejects zip slip entry outside target dir and extracts nothing`() =
        runBlocking {
            val zipFile = File(tmp.newFolder("zips"), "evil.zip")
            ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zip ->
                zip.putNextEntry(ZipEntry("../evil.txt"))
                zip.write("pwn".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("nested/ok.txt"))
                zip.write("ok".toByteArray())
                zip.closeEntry()
            }
            val targetDir = tmp.newFolder("target")

            val result = engine.unzip(zipFile, targetDir, password)

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is SecurityException)
            assertTrue(result.exceptionOrNull()?.message?.contains("../evil.txt") == true)
            // Guard atomik: tidak ada entri yang diekstrak, termasuk entri yang sah.
            assertTrue(targetDir.listFiles().isNullOrEmpty())
            assertFalse(File(targetDir.parentFile, "evil.txt").exists())
        }

    @Test
    fun `unzip rejects zip slip via nested traversal segments`() =
        runBlocking {
            val zipFile = File(tmp.newFolder("zips"), "evil-deep.zip")
            ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zip ->
                zip.putNextEntry(ZipEntry("sub/../../evil.txt"))
                zip.write("pwn".toByteArray())
                zip.closeEntry()
            }
            val targetDir = tmp.newFolder("target")

            val result = engine.unzip(zipFile, targetDir, password)

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is SecurityException)
            assertTrue(targetDir.listFiles().isNullOrEmpty())
            assertFalse(File(targetDir.parentFile, "evil.txt").exists())
        }

    @Test
    fun `encrypted archive cannot be read without password by plain zip4j`() =
        runBlocking {
            val targetZip = createEncryptedArchive("cryptocheck")
            val extractDir = tmp.newFolder("extract")

            // Baca arsip langsung dengan zip4j tanpa sandi: wajib gagal sebagai
            // ZipException WRONG_PASSWORD (bukti arsip benar-benar terenkripsi AES).
            val plainZipFile = ZipFile(targetZip)
            val exception = runCatching { plainZipFile.extractAll(extractDir.absolutePath) }.exceptionOrNull()

            assertTrue(exception is ZipException)
            assertEquals(ZipException.Type.WRONG_PASSWORD, (exception as ZipException).type)
        }

    @Test
    fun `zipFilesEncrypted creates missing parent folders of target`() =
        runBlocking {
            val sources = createSourceTree(tmp.newFolder("source"))
            val targetZip = File(tmp.root, "deep/nested/arsip.zip")

            val result = engine.zipFilesEncrypted(sources, targetZip, password)

            assertTrue(result.isSuccess)
            assertTrue(targetZip.exists())
        }

    @Test
    fun `unzip on corrupt archive fails without security exception`() =
        runBlocking {
            val corruptZip = File(tmp.newFolder("zips"), "corrupt.zip")
            corruptZip.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
            val targetDir = tmp.newFolder("target")

            val result = engine.unzip(corruptZip, targetDir, password)

            assertTrue(result.isFailure)
            // ZipException adalah turunan IOException: terpetakan apa adanya, bukan SecurityException.
            assertTrue(result.exceptionOrNull() is IOException)
            assertFalse(result.exceptionOrNull() is SecurityException)
        }

    /**
     * Membuat pohon sumber: 2 berkas top-level + 1 folder bersarang berisi 1 berkas.
     * Daftar yang dikembalikan sengaja tidak terurut; [ZipCrypto] mengurutkan sendiri.
     */
    private fun createSourceTree(root: File): List<File> {
        File(root, "folder").mkdirs()
        val textFile = File(root, "a.txt").apply { writeText("teks biasa") }
        val binaryFile = File(root, "b.bin").apply { writeBytes(Random(42).nextBytes(8_192)) }
        File(root, "folder/inner.txt").writeText("isi dalam folder")
        return listOf(File(root, "folder"), textFile, binaryFile)
    }

    /** Membuat arsip terenkripsi valid via [ZipCrypto] untuk dipakai skenario ekstraksi. */
    private suspend fun createEncryptedArchive(name: String): File {
        val sources = createSourceTree(tmp.newFolder(name))
        val targetZip = File(tmp.root, "$name.zip")
        val result = engine.zipFilesEncrypted(sources, targetZip, password)
        assertTrue(result.isSuccess)
        return targetZip
    }
}
