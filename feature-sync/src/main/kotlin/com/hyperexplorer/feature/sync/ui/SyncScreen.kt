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

package com.hyperexplorer.feature.sync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hyperexplorer.core.ui.components.EmptyState
import com.hyperexplorer.data.remote.RemoteConnection
import com.hyperexplorer.data.remote.RemoteUri
import com.hyperexplorer.feature.sync.R
import com.hyperexplorer.feature.sync.engine.SyncDirection
import com.hyperexplorer.feature.sync.engine.SyncPair
import java.io.File

/**
 * Layar sinkronisasi: daftar pasangan folder lokal <-> remote (WorkManager periodik) dengan
 * tombol sinkron-sekarang & hapus (konfirmasi AlertDialog) per baris, FAB tambah pasangan,
 * dialog form (dropdown sambungan + path lokal/remote + SegmentedButton arah + dropdown
 * interval), banner galat operasi, dan tampilan kosong. State holder [SyncState] dibuat
 * internal lewat [remember] (pola NetworkLocationsScreen, bukan rememberSaveable).
 *
 * Seluruh teks UI diambil dari resource string (R.string.sync_*) — tanpa literal.
 */
@Composable
fun SyncScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state = remember { SyncState(context.applicationContext) }
    var deleteTarget by remember { mutableStateOf<SyncPair?>(null) }

    Scaffold(
        modifier = modifier,
        floatingActionButton = {
            FloatingActionButton(onClick = { state.openAddDialog() }) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.sync_add_pair),
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Text(
                text = stringResource(R.string.sync_title),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                style = MaterialTheme.typography.titleMedium,
            )
            if (state.loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            state.listError?.let { error ->
                ErrorBanner(error = error, onRetry = { state.refresh() })
            }
            if (!state.loading && state.pairs.isEmpty() && state.listError == null) {
                EmptyContent()
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.pairs, key = { it.id }) { pair ->
                        SyncPairRow(
                            pair = pair,
                            statusText = state.statusText(pair.id),
                            onSyncNow = { state.syncNow(pair.id) },
                            onDeleteRequest = { deleteTarget = pair },
                        )
                    }
                }
            }
        }
    }

    if (state.dialogVisible) {
        AddPairDialog(state = state, onDismiss = { state.dismissDialog() })
    }

    deleteTarget?.let { pair ->
        DeletePairDialog(
            pair = pair,
            onConfirm = {
                state.deletePair(pair.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

/** Satu baris pasangan: ikon arah, nama folder lokal, remote · interval, dan status terakhir. */
@Composable
private fun SyncPairRow(
    pair: SyncPair,
    statusText: String?,
    onSyncNow: () -> Unit,
    onDeleteRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = directionIcon(pair.direction),
            contentDescription = directionLabel(pair.direction),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = basenameOf(pair.localPath),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = RemoteUri.toDisplayString(pair.connection) + " · " + intervalSummaryLabel(pair.intervalMinutes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = statusCaption(statusText),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onSyncNow) {
            Icon(
                imageVector = Icons.Outlined.Sync,
                contentDescription = stringResource(R.string.sync_sync_now),
            )
        }
        IconButton(onClick = onDeleteRequest) {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = stringResource(R.string.sync_delete),
            )
        }
    }
}

/**
 * Dialog tambah pasangan: dropdown sambungan (ExposedDropdownMenuBox, pola
 * NetworkLocationsScreen), path lokal, path remote (kosong = root), SegmentedButton arah,
 * dropdown interval, pesan galat form, dan tombol Simpan/Batal.
 */
@Composable
private fun AddPairDialog(
    state: SyncState,
    onDismiss: () -> Unit,
) {
    val form = state.form
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.sync_add_pair)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ConnectionDropdown(
                    selectedId = form.connectionId,
                    connections = state.connections,
                    onSelect = { id -> state.updateConnection(id) },
                )
                OutlinedTextField(
                    value = form.localPath,
                    onValueChange = { value -> state.updateLocalPath(value) },
                    label = { Text(text = stringResource(R.string.sync_pair_local_path)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = form.remotePath,
                    onValueChange = { value -> state.updateRemotePath(value) },
                    label = { Text(text = stringResource(R.string.sync_pair_remote_path)) },
                    singleLine = true,
                    supportingText = { Text(text = stringResource(R.string.sync_pair_remote_hint)) },
                )
                DirectionSection(selected = form.direction, onSelect = { state.updateDirection(it) })
                IntervalDropdown(selected = form.intervalMinutes, onSelect = { state.updateInterval(it) })
                state.formError?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { state.savePair() }) {
                Text(text = stringResource(R.string.sync_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.sync_cancel))
            }
        },
    )
}

/** Dropdown sambungan tersimpan; daftar kosong tetap bisa dibuka (Simpan nanti ditolak). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionDropdown(
    selectedId: Long,
    connections: List<RemoteConnection>,
    onSelect: (Long) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = connections.firstOrNull { it.id == selectedId }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected?.let { RemoteUri.toDisplayString(it) } ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text(text = stringResource(R.string.sync_pair_connection)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            connections.forEach { connection ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = RemoteUri.toDisplayString(connection),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(connection.id)
                    },
                )
            }
        }
    }
}

/** Pemilih arah sinkronisasi: SegmentedButton dua pilihan (dorong ke remote / tarik ke lokal). */
@Composable
private fun DirectionSection(
    selected: SyncDirection,
    onSelect: (SyncDirection) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.sync_direction),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = selected == SyncDirection.PUSH_TO_REMOTE,
                onClick = { onSelect(SyncDirection.PUSH_TO_REMOTE) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                label = { Text(text = stringResource(R.string.sync_direction_push)) },
            )
            SegmentedButton(
                selected = selected == SyncDirection.PULL_TO_LOCAL,
                onClick = { onSelect(SyncDirection.PULL_TO_LOCAL) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                label = { Text(text = stringResource(R.string.sync_direction_pull)) },
            )
        }
    }
}

/** Dropdown interval berulang: 15 menit / 1 jam / 6 jam / 1 hari → 15/60/360/1440 menit. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IntervalDropdown(
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val options =
        remember {
            listOf(
                SyncState.INTERVAL_15_MINUTES,
                SyncState.INTERVAL_1_HOUR,
                SyncState.INTERVAL_6_HOURS,
                SyncState.INTERVAL_1_DAY,
            )
        }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = intervalOptionLabel(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(text = stringResource(R.string.sync_interval)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { minutes ->
                DropdownMenuItem(
                    text = { Text(text = intervalOptionLabel(minutes)) },
                    onClick = {
                        expanded = false
                        onSelect(minutes)
                    },
                )
            }
        }
    }
}

/** Dialog konfirmasi hapus (defensif: mencegah hapus tak sengaja). */
@Composable
private fun DeletePairDialog(
    pair: SyncPair,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.sync_delete_title)) },
        text = { Text(text = stringResource(R.string.sync_delete_confirm, basenameOf(pair.localPath))) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.sync_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.sync_cancel))
            }
        },
    )
}

/** Banner galat operasi daftar (pola ErrorBanner :feature-network) + tombol coba lagi. */
@Composable
private fun ErrorBanner(
    error: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.errorContainer) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(onClick = onRetry) {
                Text(text = stringResource(R.string.sync_retry))
            }
        }
    }
}

/**
 * Tampilan kosong: EmptyState dari :core-ui untuk pesan utama (pesan tunggal, sesuai pola
 * komponen inti) + petunjuk singkat sebagai teks terpisah di bawahnya (EmptyState hanya
 * mendukung satu pesan, sehingga petunjuk ditempatkan pada sisa ruang Column).
 */
@Composable
private fun EmptyContent(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        EmptyState(
            message = stringResource(R.string.sync_empty),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.sync_empty_hint),
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 48.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Ikon arah: panah atas = dorong ke remote, panah bawah = tarik ke lokal. */
private fun directionIcon(direction: SyncDirection): ImageVector =
    when (direction) {
        SyncDirection.PUSH_TO_REMOTE -> Icons.Outlined.ArrowUpward
        SyncDirection.PULL_TO_LOCAL -> Icons.Outlined.ArrowDownward
    }

/** Label arah untuk contentDescription ikon baris (satu-satunya indikasi arah di baris). */
@Composable
private fun directionLabel(direction: SyncDirection): String =
    when (direction) {
        SyncDirection.PUSH_TO_REMOTE -> stringResource(R.string.sync_direction_push)
        SyncDirection.PULL_TO_LOCAL -> stringResource(R.string.sync_direction_pull)
    }

/** Nama folder lokal (basename) utk judul baris; fallback path utuh bila basename kosong. */
private fun basenameOf(path: String): String = File(path).name.ifBlank { path }

/** Label interval utk baris daftar (60 → "Every 60 minutes", 360 → "Every 6 hours", 1440 → Daily). */
@Composable
private fun intervalSummaryLabel(minutes: Int): String =
    when {
        minutes == SyncState.INTERVAL_1_DAY -> stringResource(R.string.sync_every_day)
        minutes % 60 == 0 && minutes / 60 > 1 -> stringResource(R.string.sync_every_hours, minutes / 60)
        else -> stringResource(R.string.sync_every_minutes, minutes)
    }

/** Label opsi interval utk dropdown; nilai tak dikenal jatuh ke "Every N minutes" (defensif). */
@Composable
private fun intervalOptionLabel(minutes: Int): String =
    when (minutes) {
        SyncState.INTERVAL_15_MINUTES -> stringResource(R.string.sync_interval_15m)
        SyncState.INTERVAL_1_HOUR -> stringResource(R.string.sync_interval_1h)
        SyncState.INTERVAL_6_HOURS -> stringResource(R.string.sync_interval_6h)
        SyncState.INTERVAL_1_DAY -> stringResource(R.string.sync_interval_1d)
        else -> stringResource(R.string.sync_every_minutes, minutes)
    }

/** Caption status terakhir: null (belum pernah sinkron) → teks "belum pernah" eksplisit. */
@Composable
private fun statusCaption(statusText: String?): String =
    if (statusText == null) {
        stringResource(R.string.sync_status_never)
    } else {
        stringResource(R.string.sync_last_sync, statusText)
    }
