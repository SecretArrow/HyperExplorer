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

/** Siklus hidup layar pencarian indeks. */
enum class SearchStatus {
    /** Belum ada indeks terpasang; pengguna perlu membangun indeks lebih dulu. */
    IDLE,

    /** Indeks sedang dibangun di latar belakang. */
    INDEXING,

    /** Indeks terpasang dan siap dicari. */
    READY,

    /** Pencarian sedang berjalan. */
    SEARCHING,
}

/** Jenis galat layar pencarian; teksnya dipetakan ke resource string di lapisan UI. */
enum class SearchError {
    /** Pencarian dilakukan saat indeks masih kosong. */
    INDEX_EMPTY,

    /** Pembangunan indeks gagal (root tidak valid, penulisan indeks gagal, atau galat tak terduga). */
    INDEX_FAILED,

    /**
     * Pembacaan indeks awal gagal total. Kontrak [SearchIndexStore.readOrEmpty]
     * tidak pernah melempar, sehingga nilai ini murni fail-safe; tetap dipertahankan
     * sesuai kontrak UI agar jalur kegagalan ekstrem pun punya tempat yang jelas.
     */
    LOAD_FAILED,
}

/**
 * State UI layar pencarian indeks (kontrak tetap Task 11-b).
 *
 * @property status fase siklus hidup saat ini.
 * @property entries hasil pencarian yang tampil (bukan salinan indeks; indeks disimpan
 *   privat di [SearchController]).
 * @property totalMatches jumlah kecocokan penuh menurut engine (bisa lebih besar dari
 *   [entries] bila hasil terpotong oleh [SearchFilters.maxResults]).
 * @property truncated true bila [entries] terpotong dan tidak menampilkan seluruh kecocokan.
 * @property indexCount jumlah entri indeks yang berhasil dimuat/dibangun.
 * @property skippedCount jumlah entri yang dilewati saat pembangunan indeks terakhir.
 * @property error galat aktif untuk ditampilkan; null berarti tidak ada galat.
 */
data class SearchUiState(
    val status: SearchStatus = SearchStatus.IDLE,
    val entries: List<IndexedEntry> = emptyList(),
    val totalMatches: Int = 0,
    val truncated: Boolean = false,
    val indexCount: Int = 0,
    val skippedCount: Int = 0,
    val error: SearchError? = null,
)
