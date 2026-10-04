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
import java.util.Locale

/**
 * Satu baris hasil pemindaian indeks: metadata berkas/folder yang cukup untuk
 * pencarian tanpa menyentuh sistem berkas lagi.
 *
 * Model ini MURNI JVM (tanpa import android.*) agar seluruh mesin pencarian
 * dapat diuji di CI sebagai unit test JVM biasa.
 *
 * @property path                jalur absolut dengan pemisah '/' (bukan '\').
 * @property name                nama berkas/folder (segmen terakhir [path]).
 * @property isDirectory         true bila entri adalah direktori.
 * @property sizeBytes           ukuran berkas dalam byte; selalu 0 utk direktori.
 * @property lastModifiedEpochMs waktu modifikasi terakhir epoch ms; di-clamp 0 bila negatif.
 */
data class IndexedEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModifiedEpochMs: Long,
) {
    /**
     * Jalur induk: [path] sebelum '/' terakhir; "" bila path root-level
     * (mis. "/buku.txt" -> "", "buku.txt" -> "").
     */
    val parent: String
        get() = path.substringBeforeLast('/', "")

    /**
     * Ekstensi huruf kecil (Locale.ROOT) tanpa titik; "" bila nama tanpa titik.
     * Sengaja leksikal murni (tidak membedakan direktori/berkas) — pemetaan
     * kategori sudah menangani direktori lewat [FileCategory.FOLDER].
     */
    val extension: String
        get() = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
}

/**
 * Filter pencarian AND: semua kriteria yang diisi HARUS terpenuhi.
 * Kriteria null/blank berarti "abaikan filter ini".
 *
 * @property nameQuery             kueri nama; blank = cocok semua nama, selain itu
 *                                 dicocokkan sbg substring case-insensitive (Locale.ROOT).
 * @property category              kategori [FileCategory]; null = semua kategori.
 *                                 Direktori selalu [FileCategory.FOLDER]; berkas memakai
 *                                 FileNode.categoryOf(name) (reuse, tanpa duplikasi pemetaan).
 * @property minSizeBytes          ukuran minimum INKLUSIF (minSizeBytes <= sizeBytes); null = tanpa batas.
 * @property maxSizeBytes          ukuran maksimum INKLUSIF (sizeBytes <= maxSizeBytes); null = tanpa batas.
 * @property modifiedAfterEpochMs  waktu minimum INKLUSIF; null = tanpa batas.
 * @property modifiedBeforeEpochMs waktu maksimum INKLUSIF; null = tanpa batas.
 * @property maxResults            batas jumlah hasil; <= 0 defensif: hasil kosong,
 *                                 totalMatches tetap dihitung (lihat [SearchResult]).
 */
data class SearchFilters(
    val nameQuery: String = "",
    val category: FileCategory? = null,
    val minSizeBytes: Long? = null,
    val maxSizeBytes: Long? = null,
    val modifiedAfterEpochMs: Long? = null,
    val modifiedBeforeEpochMs: Long? = null,
    val maxResults: Int = 200,
)

/**
 * Hasil pencarian.
 *
 * @property entries       hasil terpotong [maxResults]; URUTAN DETERMINISTIK selalu
 *                         diberlakukan (direktori dulu, nama lowercase asc, path asc
 *                         sebagai tie-break) — termasuk saat terpotong spt maxResults=1.
 * @property totalMatches  jumlah kecocokan SEBELUM pemotongan.
 * @property truncated     true bila ada kecocokan yang tidak muat dalam [SearchFilters.maxResults].
 */
data class SearchResult(
    val entries: List<IndexedEntry>,
    val totalMatches: Int,
    val truncated: Boolean,
)

/**
 * Anggaran pemindaian [SearchEngine.buildIndex] supaya pindai pohon tak pernah tak terbatas.
 *
 * @property maxEntries    jumlah entri maksimum yang dikumpulkan; mencapai batas ->
 *                         berhenti dan truncated=true (hasil parsial tetap dikembalikan).
 * @property maxDepth      kedalaman maksimum: root = 0, anak root = 1; entri pada
 *                         kedalaman > maxDepth dilewati (bukan skippedCount) dan truncated=true.
 * @property maxDurationMs batas waktu dinding pemasakan; setiap memproses direktori,
 *                         waktu berjalan >= nilai ini -> berhenti dan truncated=true.
 */
data class ScanBudget(
    val maxEntries: Int = 100_000,
    val maxDepth: Int = 64,
    val maxDurationMs: Long = 60_000L,
)

/**
 * Hasil pembangunan indeks.
 *
 * @property entries       entri yang terkumpul (TIDAK termasuk root itu sendiri).
 * @property skippedCount  entri/folder yang tidak dapat dibaca (listFiles()==null atau
 *                         galat I/O per entri) — tidak menghentikan pemasakan.
 * @property truncated     true bila anggaran ([ScanBudget]) tercapai: entri, kedalaman,
 *                         atau waktu — artinya indeks kemungkinan belum lengkap.
 * @property durationMs    durasi pemasakan ms (>= 0).
 */
data class IndexBuildResult(
    val entries: List<IndexedEntry>,
    val skippedCount: Int,
    val truncated: Boolean,
    val durationMs: Long,
)
