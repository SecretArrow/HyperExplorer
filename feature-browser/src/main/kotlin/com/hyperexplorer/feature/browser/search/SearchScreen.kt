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

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hyperexplorer.core.model.FileCategory
import com.hyperexplorer.core.model.FileNode
import com.hyperexplorer.core.ui.components.EmptyState
import com.hyperexplorer.core.ui.components.FileRow
import com.hyperexplorer.feature.browser.R
import java.io.File

/**
 * Layar pencarian indeks: membangun indeks pada [root], menyimpannya ke [indexFile],
 * lalu memfilter hasil berdasarkan nama dan kategori berkas.
 *
 * Keputusan sadar (berbeda/eksplisit dari pola lain):
 * - Pesan galat TIDAK memakai toast (beda dari VaultScreen): seluruh galat
 *   ditampilkan lewat state ([SearchUiState.error] -> pane galat dengan tombol coba
 *   lagi) sehingga tetap terlihat sampai ditindaklanjuti, bukan hilang sekali tampil.
 * - Kategori tersimpan sebagai state Compose lokal dan TIDAK otomatis memicu
 *   pencarian; pengguna menekan tombol cari secara eksplisit agar pemindaian yang
 *   berpotensi berat hanya berjalan saat diminta.
 * - [SearchController] menginisialisasi dirinya sendiri (memuat indeks awal di blok
 *   init-nya), sehingga tidak ada LaunchedEffect di layar ini.
 * - Kunci string `search_index_truncated` tersedia untuk paritas kontrak, tetapi
 *   belum ter-wire: [SearchUiState] (kontrak tetap) tidak memiliki penanda truncation
 *   indeks terpisah dari truncation hasil pencarian; terdokumentasi di laporan Task 11-b.
 *
 * @param root folder akar yang diindeks.
 * @param indexFile lokasi berkas penyimpanan indeks.
 * @param onOpen dipanggil saat pengguna membuka satu hasil pencarian.
 * @param onClose dipanggil saat pengguna menutup layar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    root: File,
    indexFile: File,
    onOpen: (IndexedEntry) -> Unit,
    onClose: () -> Unit,
) {
    val controller = remember(root, indexFile) { SearchController(root, indexFile) }
    val state by controller.ui.collectAsState()
    var query by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<FileCategory?>(null) }
    val busy = state.status == SearchStatus.INDEXING || state.status == SearchStatus.SEARCHING

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.search_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.search_cd_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            IndexStatusCard(state = state, onRebuild = controller::buildIndex)
            QueryInput(
                query = query,
                onQueryChange = { query = it },
                busy = busy,
                onSearch = {
                    controller.search(SearchFilters(nameQuery = query.trim(), category = selectedCategory))
                },
            )
            CategoryChips(selected = selectedCategory, onSelect = { selectedCategory = it })
            ResultsSection(
                state = state,
                controller = controller,
                onOpen = onOpen,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }
}

/**
 * Kartu status indeks: progres + keterangan saat membangun, atau jumlah entri
 * (plus jumlah dilewati bila ada) beserta tombol bangun ulang saat siap/idle.
 * Tombol dinonaktifkan saat [SearchStatus.INDEXING]/[SearchStatus.SEARCHING].
 */
@Composable
private fun IndexStatusCard(
    state: SearchUiState,
    onRebuild: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            when (state.status) {
                SearchStatus.INDEXING -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = stringResource(R.string.search_indexing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                SearchStatus.IDLE,
                SearchStatus.READY,
                SearchStatus.SEARCHING,
                -> {
                    Text(
                        text = stringResource(R.string.search_index_count, state.indexCount),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (state.skippedCount > 0) {
                        Text(
                            text = stringResource(R.string.search_index_skipped, state.skippedCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = onRebuild,
                        enabled = state.status != SearchStatus.INDEXING && state.status != SearchStatus.SEARCHING,
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.search_cd_rebuild),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = stringResource(R.string.search_rebuild))
                    }
                }
            }
        }
    }
}

/** Baris input kueri: kolom nama berkas/folder + tombol pencarian berbentuk ikon. */
@Composable
private fun QueryInput(
    query: String,
    onQueryChange: (String) -> Unit,
    busy: Boolean,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text(text = stringResource(R.string.search_query_hint)) },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onSearch, enabled = !busy) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = stringResource(R.string.search_cd_search),
            )
        }
    }
}

/**
 * Baris chip kategori (digulir horizontal, satu chip per [FileCategory.entries]).
 * Mengetuk chip memilih kategori; mengetuk chip yang sama lagi mengosongkannya
 * (null = semua kategori). Perubahan kategori sengaja TIDAK langsung memicu
 * pencarian — lihat KDoc layar.
 */
@Composable
private fun CategoryChips(
    selected: FileCategory?,
    onSelect: (FileCategory?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FileCategory.entries.forEach { category ->
            FilterChip(
                selected = selected == category,
                onClick = { onSelect(if (selected == category) null else category) },
                label = { Text(text = stringResource(categoryLabelRes(category))) },
            )
        }
    }
}

/** Pemetaan ekshaustif kategori berkas ke string `search_cat_*`. */
private fun categoryLabelRes(category: FileCategory): Int =
    when (category) {
        FileCategory.FOLDER -> R.string.search_cat_folder
        FileCategory.IMAGE -> R.string.search_cat_image
        FileCategory.VIDEO -> R.string.search_cat_video
        FileCategory.AUDIO -> R.string.search_cat_audio
        FileCategory.DOCUMENT -> R.string.search_cat_document
        FileCategory.ARCHIVE -> R.string.search_cat_archive
        FileCategory.APK -> R.string.search_cat_apk
        FileCategory.OTHER -> R.string.search_cat_other
    }

/**
 * Area hasil dengan prioritas cabang: spinner saat mencari -> pane galat -> daftar
 * hasil -> pesan tanpa-hasil (READY, tanpa kecocokan, tanpa galat) -> kotak kosong
 * untuk IDLE/INDEXING tanpa galat (belum ada yang dicari / indeks sedang dibangun,
 * memang belum ada konten untuk ditampilkan — cabang else defensif).
 */
@Composable
private fun ResultsSection(
    state: SearchUiState,
    controller: SearchController,
    onOpen: (IndexedEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val error = state.error
    when {
        state.status == SearchStatus.SEARCHING ->
            Box(modifier = modifier, contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        error != null -> ErrorPane(error = error, controller = controller, modifier = modifier)
        state.status == SearchStatus.READY && (state.entries.isNotEmpty() || state.totalMatches > 0) ->
            ResultsList(state = state, onOpen = onOpen, modifier = modifier)
        state.status == SearchStatus.READY ->
            EmptyState(message = stringResource(R.string.search_no_results), modifier = modifier)
        else -> Box(modifier = modifier)
    }
}

/**
 * Pane galat: pesan sesuai jenis galat (when ekshaustif) + tombol coba lagi.
 * INDEX_FAILED/LOAD_FAILED -> bangun ulang indeks; INDEX_EMPTY -> cukup hapus galat
 * karena tidak ada operasi yang gagal (indeks memang belum ada).
 */
@Composable
private fun ErrorPane(
    error: SearchError,
    controller: SearchController,
    modifier: Modifier = Modifier,
) {
    val message =
        when (error) {
            SearchError.INDEX_EMPTY -> stringResource(R.string.search_error_empty)
            SearchError.INDEX_FAILED -> stringResource(R.string.search_error_index)
            SearchError.LOAD_FAILED -> stringResource(R.string.search_error_load)
        }
    Column(modifier = modifier.fillMaxWidth()) {
        EmptyState(message = message, modifier = Modifier.weight(1f))
        Button(
            onClick = {
                when (error) {
                    SearchError.INDEX_FAILED, SearchError.LOAD_FAILED -> controller.buildIndex()
                    SearchError.INDEX_EMPTY -> controller.clearError()
                }
            },
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 24.dp),
        ) {
            Text(text = stringResource(R.string.search_retry))
        }
    }
}

/**
 * Ringkasan jumlah kecocokan (versi terpotong memakai jumlah tampil sebagai argumen
 * kedua) + daftar hasil memakai [FileRow] dari :core-ui; FileRow menghitung
 * kategori/nama/ukuran sendiri dari [FileNode]. Kunci item = path (asumsi: path
 * unik per entri indeks, kontrak engine).
 */
@Composable
private fun ResultsList(
    state: SearchUiState,
    onOpen: (IndexedEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        val summary =
            if (state.truncated) {
                stringResource(R.string.search_results_truncated, state.totalMatches, state.entries.size)
            } else {
                stringResource(R.string.search_results_summary, state.totalMatches)
            }
        Text(
            text = summary,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(state.entries, key = { it.path }) { entry ->
                FileRow(
                    node =
                        FileNode(
                            path = entry.path,
                            isDirectory = entry.isDirectory,
                            size = entry.sizeBytes,
                            lastModified = entry.lastModifiedEpochMs,
                        ),
                    selected = false,
                    onClick = { onOpen(entry) },
                )
            }
        }
    }
}
