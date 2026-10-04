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

import kotlin.jvm.Volatile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * Pengendali UI pencarian indeks: membangun indeks pada [root] via
 * [SearchEngine.buildIndex], menyimpannya ke [indexFile] via [SearchIndexStore.write],
 * lalu menjalankan [SearchEngine.search] di atas salinan indeks dalam memori.
 * Seluruh I/O berjalan di [ioDispatcher]; scope internal tidak pernah dibatalkan
 * dari method publik (pola BrowserState/VaultController).
 *
 * Kontrak anti-reentrancy (pola VaultController): status [SearchStatus.INDEXING] atau
 * [SearchStatus.SEARCHING] dipasang SECARA SINKRON di thread pemanggil sebelum
 * coroutine diluncurkan, sehingga operasi kedua yang dipanggil sebelum scheduler
 * berjalan pun sudah tertolak. Hanya satu operasi (build/search) yang berjalan pada
 * satu waktu; [clearError] dan [clearResults] sengaja tidak di-guard karena tidak
 * memicu I/O (lihat KDoc masing-masing).
 *
 * Setiap pemanggilan engine dibungkus runCatching: kegagalan build — termasuk
 * IllegalArgumentException dari root tidak valid dan IOException penulisan indeks —
 * menjadi [SearchError.INDEX_FAILED]. [SearchEngine.search] berkontrak murni dan
 * tidak melempar; jalur gagalnya murni fail-safe dan ditampilkan sebagai hasil kosong
 * karena [SearchError] tidak memiliki jenis galat pencarian (keputusan sadar).
 *
 * Pemuatan awal: init membaca [indexFile] via [SearchIndexStore.readOrEmpty] di
 * [ioDispatcher]; bila berisi, state menjadi READY dengan [SearchUiState.indexCount]
 * terisi, selain itu tetap IDLE. Pemuatan hanya diterapkan bila state masih persis
 * kondisi default (compareAndSet) agar tidak pernah menimpa operasi yang lebih dulu
 * dimulai pengguna.
 *
 * @param root folder akar yang diindeks (keabsahan divalidasi oleh engine).
 * @param indexFile berkas penyimpanan indeks (ditulis saat build, dibaca saat init).
 * @param ioDispatcher dispatcher untuk seluruh pemanggilan engine.
 */
class SearchController(
    private val root: File,
    private val indexFile: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val _ui = MutableStateFlow(SearchUiState())
    val ui: StateFlow<SearchUiState> = _ui.asStateFlow()

    /**
     * Salinan indeks dalam memori untuk [SearchEngine.search]; ditulis hanya dari
     * coroutine pemilik scope dan dibaca dari thread pemanggil, maka [Volatile].
     */
    @Volatile
    private var indexEntries: List<IndexedEntry> = emptyList()

    init {
        scope.launch { loadInitialIndex() }
    }

    /**
     * Membangun indeks secara asinkron; diabaikan bila status masih
     * [SearchStatus.INDEXING] atau [SearchStatus.SEARCHING]. Setelah selesai: status
     * [SearchStatus.READY] dengan [SearchUiState.indexCount] dan [SearchUiState.skippedCount]
     * terisi; jumlah dihitung ulang dari [indexFile] via [SearchIndexStore.readOrEmpty]
     * agar yang ditampilkan persis sama dengan yang berhasil disimpan (satu sumber kebenaran).
     * Hasil pencarian lama dihapus saat build dimulai. Bila gagal, indeks lama (bila ada)
     * tetap dipakai: status kembali READY/IDLE sesuai jumlah indeks, dengan galat
     * [SearchError.INDEX_FAILED].
     */
    fun buildIndex() {
        val current = _ui.value
        if (current.status == SearchStatus.INDEXING || current.status == SearchStatus.SEARCHING) return
        _ui.value =
            current.copy(
                status = SearchStatus.INDEXING,
                entries = emptyList(),
                totalMatches = 0,
                truncated = false,
                error = null,
            )
        scope.launch {
            val outcome =
                runCatching {
                    val result = SearchEngine.buildIndex(root)
                    SearchIndexStore.write(result.entries, indexFile)
                    Pair(SearchIndexStore.readOrEmpty(indexFile), result.skippedCount)
                }
            outcome.fold(
                onSuccess = { (stored, skipped) ->
                    indexEntries = stored
                    _ui.value =
                        SearchUiState(
                            status = SearchStatus.READY,
                            indexCount = stored.size,
                            skippedCount = skipped,
                        )
                },
                onFailure = { _ ->
                    // Detail penyebab tidak dimiliki SearchUiState (kontrak tetap); pesan
                    // galat UI informatif dihasilkan lapisan string, bukan dari exception.
                    _ui.update { state ->
                        state.copy(
                            status = if (state.indexCount > 0) SearchStatus.READY else SearchStatus.IDLE,
                            entries = emptyList(),
                            totalMatches = 0,
                            truncated = false,
                            error = SearchError.INDEX_FAILED,
                        )
                    }
                },
            )
        }
    }

    /**
     * Menjalankan pencarian secara asinkron di atas salinan indeks dalam memori.
     * Pre-validasi sinkron (tanpa menyentuh engine):
     * - status [SearchStatus.INDEXING]/[SearchStatus.SEARCHING] -> diabaikan;
     * - indeks kosong ([SearchUiState.indexCount] == 0 dan salinan dalam memori kosong) ->
     *   [SearchError.INDEX_EMPTY].
     * Hasil sebelumnya dihapus saat pencarian dimulai; [SearchUiState.indexCount] dan
     * [SearchUiState.skippedCount] dipertahankan. [SearchFilters] diteruskan apa adanya;
     * pemangkasan kueri dilakukan lapisan UI.
     */
    fun search(filters: SearchFilters) {
        val current = _ui.value
        if (current.status == SearchStatus.INDEXING || current.status == SearchStatus.SEARCHING) return
        if (current.indexCount == 0 && indexEntries.isEmpty()) {
            _ui.value = current.copy(error = SearchError.INDEX_EMPTY)
            return
        }
        _ui.value =
            current.copy(
                status = SearchStatus.SEARCHING,
                entries = emptyList(),
                totalMatches = 0,
                truncated = false,
                error = null,
            )
        scope.launch {
            val snapshot = indexEntries
            val outcome = runCatching { SearchEngine.search(snapshot, filters) }
            outcome.fold(
                onSuccess = { result ->
                    _ui.update { state ->
                        state.copy(
                            status = SearchStatus.READY,
                            entries = result.entries,
                            totalMatches = result.totalMatches,
                            truncated = result.truncated,
                        )
                    }
                },
                onFailure = { _ -> failSafeSearchResult() },
            )
        }
    }

    /** Menghapus galat aktif (jika ada); tidak mengubah status maupun hasil. */
    fun clearError() {
        _ui.update { it.copy(error = null) }
    }

    /**
     * Mengosongkan hasil pencarian. Status kembali [SearchStatus.READY] bila indeks
     * masih terpasang ([SearchUiState.indexCount] > 0), selain itu IDLE. Metode ini tidak
     * memicu I/O sehingga sengaja tidak di-guard anti-reentrancy; bila dipanggil saat
     * operasi berjalan, hasil akhir operasi tetap menimpa state dengan benar.
     */
    fun clearResults() {
        _ui.update { state ->
            state.copy(
                entries = emptyList(),
                totalMatches = 0,
                truncated = false,
                status = if (state.indexCount > 0) SearchStatus.READY else SearchStatus.IDLE,
            )
        }
    }

    /**
     * Memuat indeks awal dari [indexFile] via [SearchIndexStore.readOrEmpty] (berkontrak
     * tidak pernah melempar; runCatching di sini murni pengaman ganda sesuai aturan
     * defensif). Bila berisi, state diangkat menjadi READY; bila kosong, tetap IDLE.
     */
    private fun loadInitialIndex() {
        val outcome = runCatching { SearchIndexStore.readOrEmpty(indexFile) }
        outcome.fold(
            onSuccess = { stored -> adoptInitialIndex(stored) },
            onFailure = { _ ->
                // Fail-safe murni: readOrEmpty berkontrak tidak pernah melempar, jalur ini
                // hanya tercapai bila perilaku implementasi engine berubah di masa depan.
                _ui.update { state ->
                    if (state.status == SearchStatus.IDLE) state.copy(error = SearchError.LOAD_FAILED) else state
                }
            },
        )
    }

    /** Menerapkan indeks awal hanya bila state masih persis kondisi default. */
    private fun adoptInitialIndex(entries: List<IndexedEntry>) {
        if (entries.isEmpty()) return
        val initial = SearchUiState(status = SearchStatus.READY, indexCount = entries.size)
        if (_ui.compareAndSet(SearchUiState(), initial)) {
            indexEntries = entries
        }
    }

    /** Hasil kosong fail-safe bila pencarian (yang berkontrak tidak melempar) tetap gagal. */
    private fun failSafeSearchResult() {
        _ui.update { state ->
            state.copy(
                status = SearchStatus.READY,
                entries = emptyList(),
                totalMatches = 0,
                truncated = false,
            )
        }
    }
}
