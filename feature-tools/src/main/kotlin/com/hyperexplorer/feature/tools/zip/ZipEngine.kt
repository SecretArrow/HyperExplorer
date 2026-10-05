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
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Mesin kompresi/dekompresi berkas berbasis java.util.zip (metode DEFLATED).
 * Semua I/O berjalan di [dispatcher] agar UI tidak terblokir.
 */
class ZipEngine(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Mengompresi [sources] menjadi arsip [targetZip].
     * Entri top-level memakai nama entitasnya; isi folder memakai path relatif,
     * entri direktori diakhiri '/'. Arsip target sendiri dilewati bila ikut
     * ada di dalam [sources]. Folder induk [targetZip] dibuat bila perlu.
     * Mengembalikan [Result.success] berisi [targetZip] atau [Result.failure]
     * bila terjadi [IOException].
     */
    suspend fun zipFiles(
        sources: List<File>,
        targetZip: File,
    ): Result<File> =
        withContext(dispatcher) {
            try {
                targetZip.parentFile?.mkdirs()
                ZipOutputStream(BufferedOutputStream(FileOutputStream(targetZip))).use { zip ->
                    for (source in sources) {
                        if (source.absolutePath == targetZip.absolutePath) continue
                        if (!source.exists()) continue
                        writeEntry(zip, source, source.name)
                    }
                }
                Result.success(targetZip)
            } catch (e: IOException) {
                Result.failure(e)
            }
        }

    /**
     * Mengekstrak [zipFile] ke dalam [targetDir] dan mengembalikan jumlah entri
     * (berkas + folder) yang diekstrak.
     * Entri yang menunjuk ke luar [targetDir] (zip-slip) ditolak: pemrosesan
     * berhenti dengan [Result.failure] berisi [SecurityException].
     */
    suspend fun unzip(
        zipFile: File,
        targetDir: File,
    ): Result<Int> =
        withContext(dispatcher) {
            try {
                targetDir.mkdirs()
                var count = 0
                ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val outFile = File(targetDir, entry.name)
                        val targetPath = targetDir.canonicalPath
                        val resolvedPath = outFile.canonicalPath
                        if (resolvedPath != targetPath && !resolvedPath.startsWith(targetPath + File.separator)) {
                            return@withContext Result.failure(
                                SecurityException("Entri zip menunjuk ke luar folder tujuan: ${entry.name}"),
                            )
                        }
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { output -> zip.copyTo(output) }
                        }
                        count++
                        zip.closeEntry()
                    }
                }
                Result.success(count)
            } catch (e: IOException) {
                Result.failure(e)
            }
        }

    private fun writeEntry(
        zip: ZipOutputStream,
        file: File,
        entryPath: String,
    ) {
        val name = if (file.isDirectory) "$entryPath/" else entryPath
        zip.putNextEntry(ZipEntry(name))
        if (file.isFile) {
            FileInputStream(file).use { input -> input.copyTo(zip) }
        }
        zip.closeEntry()
        if (file.isDirectory) {
            val children = file.listFiles().orEmpty().sortedBy { it.name }
            for (child in children) {
                writeEntry(zip, child, "$entryPath/${child.name}")
            }
        }
    }
}
