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

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.UnzipParameters
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipOutputStream

/**
 * Mesin arsip ZIP terenkripsi (AES-256) berbasis zip4j 2.11.5 (Apache-2.0).
 *
 * Kontrak keamanan:
 * - Enkripsi AES-256 ([EncryptionMethod.AES] + [AesKeyStrength.KEY_STRENGTH_256]); entri direktori
 *   ikut tercatat di arsip namun selalu disimpan tanpa enkripsi (perilaku zip4j, metode STORE).
 * - Sandi diterima sebagai [CharArray], hanya dibaca, tidak pernah diubah isinya, dan tidak pernah
 *   disalin ke [String] agar tidak lama menempel di String pool / heap dump.
 * - Anti zip-slip: sebelum satu pun entri diekstrak, SEMUA header diperiksa terhadap folder tujuan
 *   (guard atomik); pelanggaran -> [Result.failure] berisi [SecurityException] tanpa ekstraksi apa
 *   pun. Guard zip4j internal (cek canonical path per entri) tetap aktif sebagai lapisan kedua.
 * - Symlink di dalam arsip TIDAK diekstrak (extractSymbolicLinks = false) untuk menutup celah
 *   zip-slip melalui tautan simbolik (entri symlink ditulis lebih dulu, lalu entri lain menimpa
 *   berkas di luar tujuan melalui tautan tersebut).
 * - Tidak ada exception yang dilempar ke pemanggil; setiap kegagalan terdefinisi dipetakan ke
 *   [Result.failure] (fail-closed), kecuali validasi sandi yang gagal cepat lewat
 *   [IllegalArgumentException] sebelum operasi I/O apa pun dimulai.
 *
 * Semua I/O berjalan di [dispatcher] agar UI tidak terblokir.
 */
class ZipCrypto(private val dispatcher: CoroutineDispatcher = Dispatchers.IO) {

    /**
     * Mengompresi [sources] menjadi arsip ZIP terenkripsi [targetZip].
     *
     * Ketentuan perilaku:
     * - Sumber tidak ada / sama dengan [targetZip] dilewati (pola [ZipEngine]).
     * - Urutan entri stabil (sortedBy name) agar arsip deterministik; struktur folder dipertahankan:
     *   berkas top-level ditambahkan lewat satu panggilan [ZipFile.addFiles], sedangkan folder
     *   direkursif lewat [ZipFile.addFolder] (zip4j mengumpulkan isi folder secara rekursif dan
     *   menulis entri direktori, termasuk direktori kosong). Catatan: [ZipFile.addFiles] memakai
     *   nama berkas saja sebagai nama entri (tanpa jalur relatif), sehingga isi folder TIDAK boleh
     *   dilewatkan sebagai daftar berkas datar.
     * - Folder induk [targetZip] dibuat bila perlu. Tanpa sumber valid: arsip ZIP kosong yang valid
     *   tetap dibuat (konsisten dengan pola [ZipEngine] yang membuat arsip tanpa entri).
     * - Target yang sudah berisi arsip akan diperbarui dengan perilaku zip4j (entri nama sama
     *   digantikan) — berbeda dari [ZipEngine] yang menimpa seluruh arsip.
     *
     * Mengembalikan [Result.success] berisi [targetZip], atau [Result.failure] berisi
     * [ZipException]/[IOException] bila operasi gagal (sandi yang kosong/whitespace saja gagal
     * cepat dengan [IllegalArgumentException]).
     */
    suspend fun zipFilesEncrypted(
        sources: List<File>,
        targetZip: File,
        password: CharArray,
    ): Result<File> =
        withContext(dispatcher) {
            // Fail-fast sebelum sentuh I/O; sandi hanya dibaca (tidak dimutasi).
            if (password.isEmpty() || password.all { it.isWhitespace() }) {
                return@withContext Result.failure(
                    IllegalArgumentException("Password is required for encrypted ZIP"),
                )
            }
            try {
                targetZip.parentFile?.mkdirs()
                val orderedSources = sources.asSequence()
                    .filter { it.absolutePath != targetZip.absolutePath }
                    .filter { it.exists() }
                    .sortedBy { it.name }
                    .toList()
                val files = orderedSources.filter { it.isFile }
                val folders = orderedSources.filter { it.isDirectory }

                if (files.isEmpty() && folders.isEmpty()) {
                    // Tidak ada sumber valid: tulis arsip kosong yang valid (hanya End Of Central
                    // Directory) supaya target tetap berupa berkas ZIP sah, bukan berkas 0-bit.
                    ZipOutputStream(BufferedOutputStream(FileOutputStream(targetZip))).use {
                        // Tanpa entri: ZipOutputStream menulis header arsip kosong saat ditutup.
                    }
                    return@withContext Result.success(targetZip)
                }

                val parameters = ZipParameters().apply {
                    compressionMethod = CompressionMethod.DEFLATE
                    encryptFiles = true
                    encryptionMethod = EncryptionMethod.AES
                    aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                    isIncludeRootFolder = true // entri direktori induk ikut tercatat di arsip
                }
                ZipFile(targetZip).use { zip ->
                    zip.setPassword(password)
                    if (files.isNotEmpty()) {
                        zip.addFiles(files, parameters)
                    }
                    for (folder in folders) {
                        zip.addFolder(folder, parameters)
                    }
                }
                Result.success(targetZip)
            } catch (e: ZipException) {
                Result.failure(e)
            } catch (e: IOException) {
                Result.failure(e)
            }
        }

    /**
     * Mengekstrak [zipFile] terenkripsi ke dalam [targetDir] dan mengembalikan jumlah entri yang
     * diproses (berkas + folder; entri symlink dilewati zip4j namun tetap terhitung).
     *
     * Ketentuan keamanan:
     * - GUARD ATOMIK ANTI ZIP-SLIP: setiap [net.lingala.zip4j.model.FileHeader] dicek terlebih
     *   dahulu — File(targetDir, header.fileName).canonicalPath wajib sama dengan
     *   targetDir.canonicalPath atau berada di dalamnya. Bila satu saja pelanggaran, TIDAK ada
     *   entri yang diekstrak dan fungsi mengembalikan [Result.failure] [SecurityException]
     *   dengan pesan "Entri zip menunjuk ke luar folder tujuan: <nama>".
     * - Header dengan nama kosong/null dianggap tidak valid dan ditolak (fail-closed).
     * - Ekstraksi dilakukan per-header ([ZipFile.extractFile], bukan extractAll) agar guard
     *   canonical path milik zip4j tetap aktif untuk setiap entri.
     * - Sandi salah/sandi kosong pada arsip AES terdeteksi zip4j sebagai
     *   [ZipException.Type.WRONG_PASSWORD] dan dipetakan ke [SecurityException]
     *   "Wrong password or corrupted archive"; [ZipException] lain dan [IOException]
     *   dikembalikan apa adanya di dalam [Result.failure].
     *
     * Sandi kosong/whitespace saja gagal cepat dengan [IllegalArgumentException]; arsip yang
     * tidak ada / bukan berkas gagal dengan [IOException] ("Encrypted ZIP not found").
     */
    suspend fun unzip(
        zipFile: File,
        targetDir: File,
        password: CharArray,
    ): Result<Int> =
        withContext(dispatcher) {
            if (password.isEmpty() || password.all { it.isWhitespace() }) {
                return@withContext Result.failure(
                    IllegalArgumentException("Password is required for encrypted ZIP"),
                )
            }
            if (!zipFile.exists() || !zipFile.isFile) {
                return@withContext Result.failure(
                    IOException("Encrypted ZIP not found: ${zipFile.absolutePath}"),
                )
            }
            try {
                targetDir.mkdirs()
                val unzipParameters = UnzipParameters().apply { isExtractSymbolicLinks = false }
                ZipFile(zipFile).use { zip ->
                    zip.setPassword(password)
                    val targetPath = targetDir.canonicalPath
                    val headers = zip.fileHeaders.orEmpty()
                    // GUARD ATOMIK: periksa SEMUA header sebelum mengekstrak satu pun entri.
                    for (header in headers) {
                        val entryName = header.fileName
                        if (entryName.isNullOrEmpty()) {
                            return@withContext Result.failure(
                                SecurityException("Entri zip tidak valid: nama entri kosong"),
                            )
                        }
                        val resolvedPath = File(targetDir, entryName).canonicalPath
                        val insideTarget = resolvedPath == targetPath ||
                            resolvedPath.startsWith(targetPath + File.separator)
                        if (!insideTarget) {
                            return@withContext Result.failure(
                                SecurityException("Entri zip menunjuk ke luar folder tujuan: $entryName"),
                            )
                        }
                    }
                    // Ekstraksi per-header (bukan extractAll) agar guard zip4j aktif per entri.
                    var count = 0
                    for (header in headers) {
                        zip.extractFile(header, targetDir.absolutePath, unzipParameters)
                        count++
                    }
                    Result.success(count)
                }
            } catch (e: ZipException) {
                if (e.type == ZipException.Type.WRONG_PASSWORD) {
                    Result.failure(SecurityException("Wrong password or corrupted archive"))
                } else {
                    Result.failure(e)
                }
            } catch (e: IOException) {
                Result.failure(e)
            }
        }
}
