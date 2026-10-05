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

package com.hyperexplorer.feature.tools.storage

import com.hyperexplorer.core.model.FileCategory
import com.hyperexplorer.core.model.FileNode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

private const val MAX_LARGEST_FILES = 20
private const val MAX_DUPLICATE_GROUPS = 10
private const val MAX_HASH_FILE_BYTES = 50L * 1024L * 1024L
private const val MAX_HASH_TOTAL_BYTES = 300L * 1024L * 1024L
private const val HASH_BUFFER_BYTES = 8 * 1024

/** Hasil analisis isi sebuah folder [root]. */
data class StorageReport(
    val root: File,
    val totalBytes: Long,
    val usableBytes: Long,
    val fileCount: Int,
    val categorySizes: Map<FileCategory, Long>,
    val largestFiles: List<FileNode>,
    val duplicateGroups: List<List<String>>,
)

/**
 * Menganalisis isi [root]: total ukuran, jumlah berkas, ukuran per kategori,
 * 20 berkas terbesar, dan grup berkas berpotensi duplikat
 * (ukuran sama lalu dibandingkan lewat MD5, dengan batas hashing).
 */
class StorageAnalyzer(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun analyze(root: File): StorageReport =
        withContext(dispatcher) {
            val files = root.walkTopDown().filter { it.isFile }.toList()
            val totalBytes = files.sumOf { it.length() }
            val categorySizes = mutableMapOf<FileCategory, Long>()
            for (file in files) {
                val category = FileNode.categoryOf(file.name)
                categorySizes[category] = (categorySizes[category] ?: 0L) + file.length()
            }
            val largestFiles =
                files
                    .sortedWith(compareByDescending<File> { it.length() }.thenBy { it.absolutePath })
                    .take(MAX_LARGEST_FILES)
                    .map { FileNode.from(it) }
            StorageReport(
                root = root,
                totalBytes = totalBytes,
                usableBytes = root.usableSpace,
                fileCount = files.size,
                categorySizes = categorySizes,
                largestFiles = largestFiles,
                duplicateGroups = findDuplicateGroups(files),
            )
        }

    /**
     * Mengelompokkan berkas berukuran sama (size > 0) lalu memverifikasi tiap
     * kandidat dengan MD5. Hanya berkas <= [MAX_HASH_FILE_BYTES] yang di-hash,
     * dengan total byte hash dibatasi [MAX_HASH_TOTAL_BYTES]; berkas yang tidak
     * sempat di-hash dikeluarkan dari kandidat duplikat.
     * Hanya grup >= 2 berkas yang dikembalikan, maksimal [MAX_DUPLICATE_GROUPS]
     * grup, urut menurun berdasarkan ukuran, tiap grup berisi absolute path.
     */
    private fun findDuplicateGroups(files: List<File>): List<List<String>> {
        val sizeGroups =
            files
                .filter { it.length() > 0L }
                .groupBy { it.length() }
                .filterValues { candidates -> candidates.size >= 2 }
                .entries
                .sortedByDescending { entry -> entry.key }
        var budget = MAX_HASH_TOTAL_BYTES
        val digests = mutableMapOf<String, String>()
        for ((_, candidates) in sizeGroups) {
            for (candidate in candidates.sortedBy { it.absolutePath }) {
                val size = candidate.length()
                if (size > MAX_HASH_FILE_BYTES || size > budget) continue
                val digest = md5(candidate) ?: continue
                digests[candidate.absolutePath] = digest
                budget -= size
            }
        }
        val groups = mutableListOf<List<String>>()
        for ((_, candidates) in sizeGroups) {
            val byDigest =
                candidates
                    .filter { it.absolutePath in digests }
                    .groupBy { digests.getValue(it.absolutePath) }
            for ((_, same) in byDigest) {
                if (same.size >= 2) {
                    groups += same.map { it.absolutePath }
                    if (groups.size >= MAX_DUPLICATE_GROUPS) return groups
                }
            }
        }
        return groups
    }

    private fun md5(file: File): String? =
        try {
            val digest = MessageDigest.getInstance("MD5")
            file.inputStream().use { input ->
                val buffer = ByteArray(HASH_BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
        } catch (e: IOException) {
            null
        } catch (e: NoSuchAlgorithmException) {
            null
        }
}
