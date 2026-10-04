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
import com.hyperexplorer.core.model.FileNode
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Mesin pencarian indeks murni JVM (tanpa import android.*): membangun indeks
 * metadata berkas dari sebuah folder akar ([buildIndex]) dan menyaring daftar
 * entri dengan filter AND secara murni & total ([search]).
 *
 * Semua operasi defensif: folder tak terbaca tidak menjatuhkan pemasakan,
 * anggaran [ScanBudget] menjamin pemasakan berhingga, dan hasil selalu deterministik.
 */
object SearchEngine {
    /**
     * Membangun indeks dengan DFS iteratif dari [root].
     *
     * Kontrak:
     * - [root] null ditolak oleh sistem tipe Kotlin (param non-null); bila tidak ada
     *   atau bukan direktori -> [IllegalArgumentException] dengan pesan Indonesia.
     * - Root TIDAK termasuk hasil; hanya isinya.
     * - Folder tak terbaca (listFiles()==null) -> [IndexBuildResult.skippedCount]++.
     * - Elemen melewati [ScanBudget.maxDepth] dilewati (BUKAN skippedCount) dan
     *   truncated=true; anggaran entri/waktu tercapai -> truncated=true dan berhenti.
     * - Direktori dengan canonical path yang sudah dikunjungi dilewati (anti loop symlink).
     * - [Thread.interrupted] dicek per direktori -> berhenti dengan truncated=true.
     *
     * @throws IllegalArgumentException bila [root] tidak ada atau bukan direktori.
     */
    fun buildIndex(
        root: File,
        budget: ScanBudget = ScanBudget(),
    ): IndexBuildResult {
        if (!root.exists()) {
            throw IllegalArgumentException("akar indeks tidak ada: ${root.absolutePath}")
        }
        if (!root.isDirectory) {
            throw IllegalArgumentException("akar indeks bukan direktori: ${root.absolutePath}")
        }
        val startMs = System.currentTimeMillis()
        val entries = ArrayList<IndexedEntry>()
        var skippedCount = 0
        var truncated = false
        val visitedCanonical = HashSet<String>()
        val stack = ArrayDeque<Frame>()
        stack.addLast(Frame(root, 0))
        while (stack.isNotEmpty()) {
            if (Thread.interrupted()) {
                // Permintaan pembatalan dari luar: berhenti segera, tandai hasil parsial.
                truncated = true
                break
            }
            if (System.currentTimeMillis() - startMs >= budget.maxDurationMs) {
                // Anggaran waktu habis (dicek >= supaya maxDurationMs=0 deterministik berhenti).
                truncated = true
                break
            }
            val frame = stack.removeLast()
            val canonical =
                try {
                    frame.dir.canonicalPath
                } catch (e: IOException) {
                    // canonicalPath gagal (I/O tak terduga): pakai absolutePath agar
                    // pemasakan tetap jalan; pengaman anti-loop tetap aktif sbg fallback.
                    frame.dir.absolutePath
                }
            if (!visitedCanonical.add(canonical)) {
                // Direktori sudah dikunjungi (symlink melingkar): lewati, jangan rekursif.
                continue
            }
            val children = listChildren(frame.dir)
            if (children == null) {
                // Folder tak terbaca: catat sbg dilewati, lanjut folder lain (jangan crash).
                skippedCount++
                continue
            }
            // Urutkan nama agar hasil parsial (budget/truncated) pun deterministik.
            for (child in children.sortedBy { it.name }) {
                val childDepth = frame.depth + 1
                if (childDepth > budget.maxDepth) {
                    // Kedalaman melewati batas: lewati entri (bukan skippedCount), tandai terpotong.
                    truncated = true
                    continue
                }
                if (entries.size >= budget.maxEntries) {
                    // Anggaran entri habis: berhenti total, kembalikan yang sudah terkumpul.
                    truncated = true
                    return finish(entries, skippedCount, truncated, startMs)
                }
                val isDirectory = child.isDirectory
                entries.add(
                    IndexedEntry(
                        path = child.absolutePath.replace(File.separatorChar, '/'),
                        name = child.name,
                        isDirectory = isDirectory,
                        sizeBytes = if (isDirectory) 0L else child.length().coerceAtLeast(0L),
                        lastModifiedEpochMs = child.lastModified().coerceAtLeast(0L),
                    ),
                )
                if (isDirectory) {
                    stack.addLast(Frame(child, childDepth))
                }
            }
        }
        return finish(entries, skippedCount, truncated, startMs)
    }

    /**
     * Menyaring [entries] dengan [filters] secara MURNI & TOTAL: tidak pernah melempar,
     * tidak menyentuh I/O, semua kriteria digabung AND, hasil urut deterministik.
     */
    fun search(
        entries: List<IndexedEntry>,
        filters: SearchFilters,
    ): SearchResult {
        val query = filters.nameQuery.lowercase(Locale.ROOT)
        val queryActive = filters.nameQuery.isNotBlank()
        val matches = ArrayList<IndexedEntry>()
        for (entry in entries) {
            if (queryActive && !entry.name.lowercase(Locale.ROOT).contains(query)) {
                continue
            }
            val category = filters.category
            if (category != null && categorize(entry) != category) {
                continue
            }
            val minSize = filters.minSizeBytes
            if (minSize != null && entry.sizeBytes < minSize) {
                continue
            }
            val maxSize = filters.maxSizeBytes
            if (maxSize != null && entry.sizeBytes > maxSize) {
                continue
            }
            val after = filters.modifiedAfterEpochMs
            if (after != null && entry.lastModifiedEpochMs < after) {
                continue
            }
            val before = filters.modifiedBeforeEpochMs
            if (before != null && entry.lastModifiedEpochMs > before) {
                continue
            }
            matches.add(entry)
        }
        // Urutan deterministik: direktori dulu, nama lowercase asc, path asc sebagai tie-break.
        val sorted =
            matches.sortedWith(
                compareBy({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }, { it.path }),
            )
        val totalMatches = sorted.size
        val limit = filters.maxResults
        val resultEntries =
            if (limit <= 0) {
                // Defensif: limit tidak sah -> hasil kosong, totalMatches tetap dihitung.
                emptyList()
            } else {
                sorted.take(limit)
            }
        val truncated =
            if (limit <= 0) {
                totalMatches > 0
            } else {
                totalMatches > resultEntries.size
            }
        return SearchResult(entries = resultEntries, totalMatches = totalMatches, truncated = truncated)
    }

    /** Kategori entri: folder selalu FOLDER, berkas memakai pemetaan inti (FileNode.categoryOf). */
    private fun categorize(entry: IndexedEntry): FileCategory =
        if (entry.isDirectory) FileCategory.FOLDER else FileNode.categoryOf(entry.name)

    /**
     * ListChildren defensif: null berarti folder tak terbaca; SecurityException
     * (manajer keamanan menolak baca, tidak ada di Android) diperlakukan sama.
     */
    private fun listChildren(dir: File): Array<File>? =
        try {
            dir.listFiles()
        } catch (e: SecurityException) {
            // Manajer keamanan menolak akses baca: perlakukan sama spt folder tak terbaca
            // agar pemasakan tetap berjalan (bukan swallow diam-diam — dihitung skippedCount).
            null
        }

    private fun finish(
        entries: List<IndexedEntry>,
        skippedCount: Int,
        truncated: Boolean,
        startMs: Long,
    ): IndexBuildResult =
        IndexBuildResult(
            entries = entries,
            skippedCount = skippedCount,
            truncated = truncated,
            durationMs = System.currentTimeMillis() - startMs,
        )

    /** Bingkai DFS: direktori yang menunggu diproses beserta kedalamannya (root = 0). */
    private data class Frame(
        val dir: File,
        val depth: Int,
    )
}
