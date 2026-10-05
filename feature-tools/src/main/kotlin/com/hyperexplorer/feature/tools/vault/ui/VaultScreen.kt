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

package com.hyperexplorer.feature.tools.vault.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hyperexplorer.core.model.FileNode
import com.hyperexplorer.core.ui.components.EmptyState
import com.hyperexplorer.feature.tools.R
import com.hyperexplorer.feature.tools.vault.AndroidKeystoreKeyProvider
import com.hyperexplorer.feature.tools.vault.VaultEntry
import com.hyperexplorer.feature.tools.vault.VaultNameError
import com.hyperexplorer.feature.tools.vault.VaultNames
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Layar vault terenkripsi: daftar berkas terkunci, impor berdasarkan jalur,
 * serta aksi buka/ekspor/verifikasi/ganti-nama/hapus per entri.
 *
 * Pesan [VaultUiMessage] dari [VaultController] ditampilkan sebagai toast lalu
 * langsung dikonsumsi agar tidak muncul dua kali; pemetaan sukses maupun galat
 * dilakukan lewat `when` ekshaustif.
 *
 * @param vaultDir direktori penyimpanan vault milik aplikasi.
 * @param onOpenFile dipanggil dengan berkas hasil dekripsi (sudah di thread utama).
 */
@Composable
fun VaultScreen(
    vaultDir: File,
    onOpenFile: (File) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember(vaultDir) { VaultController(vaultDir, AndroidKeystoreKeyProvider(), scope) }
    val openCacheDir = remember(context) { File(context.cacheDir, "vault_open") }
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()) }

    // Di-refresh ulang setiap instansi controller baru (mis. vaultDir berganti).
    LaunchedEffect(controller) { controller.refresh() }

    val state by controller.state.collectAsState()
    val message by controller.messages.collectAsState()

    LaunchedEffect(message) {
        val current = message ?: return@LaunchedEffect
        val text =
            when {
                current.success != null -> successText(context, current, current.success)
                current.error != null -> errorText(context, current.error)
                else -> null
            }
        if (text != null) {
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        }
        controller.consumeMessage()
    }

    var pendingDelete by remember { mutableStateOf<VaultEntry?>(null) }
    var pendingRename by remember { mutableStateOf<VaultEntry?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.vault_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp),
        )
        val busyOp = (state as? VaultUiState.Ready)?.state?.busy
        if (busyOp != null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                text = stringResource(busyRes(busyOp)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        when (val current = state) {
            VaultUiState.Loading ->
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            is VaultUiState.Failed -> FailedPane(error = current.error, onRetry = { controller.refresh() })
            is VaultUiState.Ready ->
                ReadyContent(
                    ready = current.state,
                    controller = controller,
                    onOpenFile = onOpenFile,
                    openCacheDir = openCacheDir,
                    dateFormat = dateFormat,
                    onRequestDelete = { pendingDelete = it },
                    onRequestRename = { pendingRename = it },
                )
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(text = stringResource(R.string.vault_delete_confirm_title)) },
            text = { Text(text = stringResource(R.string.vault_delete_confirm_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = target.id
                        pendingDelete = null
                        controller.deleteEntry(id)
                    },
                ) { Text(text = stringResource(R.string.vault_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(text = stringResource(R.string.vault_cancel)) }
            },
        )
    }

    pendingRename?.let { target ->
        var newName by remember(target.id) { mutableStateOf(target.originalName) }
        val nameError = VaultNames.validate(newName)
        val unchanged = newName.equals(target.originalName, ignoreCase = true)
        AlertDialog(
            onDismissRequest = { pendingRename = null },
            title = { Text(text = stringResource(R.string.vault_rename)) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(text = stringResource(R.string.vault_rename_hint)) },
                    singleLine = true,
                    isError = nameError != null,
                    supportingText = {
                        nameError?.let { error -> Text(text = stringResource(nameErrorRes(error))) }
                    },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = nameError == null && !unchanged,
                    onClick = {
                        val id = target.id
                        val name = newName
                        pendingRename = null
                        controller.renameEntry(id, name)
                    },
                ) { Text(text = stringResource(R.string.vault_rename)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRename = null }) { Text(text = stringResource(R.string.vault_cancel)) }
            },
        )
    }
}

/**
 * Bagian isi ketika vault siap: formulir impor di atas, lalu daftar kartu entri
 * atau [EmptyState] bila belum ada berkas terkunci.
 */
@Composable
private fun ReadyContent(
    ready: VaultReadyState,
    controller: VaultController,
    onOpenFile: (File) -> Unit,
    openCacheDir: File,
    dateFormat: SimpleDateFormat,
    onRequestDelete: (VaultEntry) -> Unit,
    onRequestRename: (VaultEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Konfigurasi-aware: resolusi string di komposisi (lint LocalContextGetResourceValueCall).
    val invalidInputLabel = stringResource(R.string.vault_error_invalid_input)
    val busy = ready.busy
    Column(modifier = modifier.fillMaxSize()) {
        ImportSection(
            busy = busy,
            onImport = { rawPath, deleteSource ->
                if (rawPath.isBlank()) {
                    // Jangan pernah memanggil controller dengan jalur kosong.
                    Toast.makeText(context, invalidInputLabel, Toast.LENGTH_LONG).show()
                } else {
                    controller.importFrom(rawPath, deleteSource)
                }
            },
        )
        if (ready.entries.isEmpty()) {
            EmptyState(message = stringResource(R.string.vault_empty), modifier = Modifier.weight(1f))
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(ready.entries, key = { it.id }) { entry ->
                    VaultEntryCard(
                        entry = entry,
                        busy = busy,
                        dateFormat = dateFormat,
                        onOpen = { controller.openEntry(entry.id, openCacheDir, onReady = onOpenFile) },
                        onExport = {
                            val destDir = context.getExternalFilesDir(null) ?: context.filesDir
                            controller.exportTo(entry.id, destDir)
                        },
                        onVerify = { controller.verifyEntry(entry.id) },
                        onRename = { onRequestRename(entry) },
                        onDelete = { onRequestDelete(entry) },
                    )
                }
            }
        }
    }
}

/** Formulir impor: jalur berkas + pilihan menghapus berkas asli + tombol impor. */
@Composable
private fun ImportSection(
    busy: VaultBusyOp?,
    onImport: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var path by remember { mutableStateOf("") }
    var deleteSource by remember { mutableStateOf(true) }
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        OutlinedTextField(
            value = path,
            onValueChange = { path = it },
            label = { Text(text = stringResource(R.string.vault_import_hint)) },
            singleLine = true,
            enabled = busy == null,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = deleteSource,
                        role = Role.Checkbox,
                        onValueChange = { deleteSource = it },
                    ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = deleteSource, onCheckedChange = null)
            Text(
                text = stringResource(R.string.vault_import_delete_source),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Button(
            onClick = { onImport(path.trim(), deleteSource) },
            enabled = busy == null,
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Text(text = stringResource(R.string.vault_import_button))
        }
    }
}

/** Kartu satu entri vault: nama, ukuran+tanggal, dan lima aksi ikon. */
@Composable
private fun VaultEntryCard(
    entry: VaultEntry,
    busy: VaultBusyOp?,
    dateFormat: SimpleDateFormat,
    onOpen: () -> Unit,
    onExport: () -> Unit,
    onVerify: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 4.dp)) {
            Text(
                text = entry.originalName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${FileNode.humanSize(entry.sizeBytes)} · ${dateFormat.format(Date(entry.addedAtEpochMs))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row {
                EntryAction(
                    icon = Icons.Filled.OpenInNew,
                    labelRes = R.string.vault_open,
                    enabled = busy == null,
                    onClick = onOpen,
                )
                EntryAction(
                    icon = Icons.Filled.SaveAlt,
                    labelRes = R.string.vault_export,
                    enabled = busy == null,
                    onClick = onExport,
                )
                EntryAction(
                    icon = Icons.Filled.Verified,
                    labelRes = R.string.vault_verify,
                    enabled = busy == null,
                    onClick = onVerify,
                )
                EntryAction(
                    icon = Icons.Filled.Edit,
                    labelRes = R.string.vault_rename,
                    enabled = busy == null,
                    onClick = onRename,
                )
                EntryAction(
                    icon = Icons.Filled.DeleteOutline,
                    labelRes = R.string.vault_delete,
                    enabled = busy == null,
                    onClick = onDelete,
                )
            }
        }
    }
}

/** Satu tombol aksi ikon pada kartu entri; dinonaktifkan saat operasi berjalan. */
@Composable
private fun EntryAction(
    icon: ImageVector,
    labelRes: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(imageVector = icon, contentDescription = stringResource(labelRes))
    }
}

/** Pane galat pemuatan indeks: ikon, teks galat sesuai jenis, dan tombol coba lagi. */
@Composable
private fun FailedPane(
    error: VaultUiError,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(imageVector = Icons.Filled.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        Text(
            text = stringResource(errorRes(error.kind)),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 12.dp),
        )
        if (error.detail.isNotBlank()) {
            Text(
                text = error.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
            Text(text = stringResource(R.string.vault_retry))
        }
    }
}

/** Teks toast sukses; pesan ber-argumen memakai [VaultUiMessage.detailArg]. */
private fun successText(
    context: Context,
    message: VaultUiMessage,
    success: VaultSuccess,
): String {
    val resId = successRes(success)
    return when (success) {
        VaultSuccess.IMPORTED,
        VaultSuccess.EXPORTED,
        VaultSuccess.DELETED,
        VaultSuccess.RENAMED,
        -> context.getString(resId, message.detailArg ?: "")
        VaultSuccess.VERIFIED,
        VaultSuccess.OPENED,
        -> context.getString(resId)
    }
}

/** Teks toast galat: label jenis galat + detail asli bila ada. */
private fun errorText(
    context: Context,
    error: VaultUiError,
): String {
    val base = context.getString(errorRes(error.kind))
    return if (error.detail.isBlank()) base else "$base: ${error.detail}"
}

/** Pemetaan ekshaustif jenis sukses ke string `vault_done_*`. */
private fun successRes(success: VaultSuccess): Int =
    when (success) {
        VaultSuccess.IMPORTED -> R.string.vault_done_import
        VaultSuccess.EXPORTED -> R.string.vault_done_export
        VaultSuccess.DELETED -> R.string.vault_done_delete
        VaultSuccess.RENAMED -> R.string.vault_done_rename
        VaultSuccess.VERIFIED -> R.string.vault_done_verify
        VaultSuccess.OPENED -> R.string.vault_done_open
    }

/** Pemetaan ekshaustif jenis galat ke string `vault_error_*`. */
private fun errorRes(kind: VaultErrorKind): Int =
    when (kind) {
        VaultErrorKind.INVALID_INPUT -> R.string.vault_error_invalid_input
        VaultErrorKind.SOURCE_MISSING -> R.string.vault_error_source_missing
        VaultErrorKind.ENTRY_NOT_FOUND -> R.string.vault_error_entry_not_found
        VaultErrorKind.CORRUPT -> R.string.vault_error_corrupt
        VaultErrorKind.INDEX_CORRUPT -> R.string.vault_error_index_corrupt
        VaultErrorKind.IO -> R.string.vault_error_io
        VaultErrorKind.KEY -> R.string.vault_error_key
    }

/** Pemetaan ekshaustif operasi sibuk ke string `vault_busy_*`. */
private fun busyRes(op: VaultBusyOp): Int =
    when (op) {
        VaultBusyOp.IMPORT -> R.string.vault_busy_import
        VaultBusyOp.EXPORT -> R.string.vault_busy_export
        VaultBusyOp.DELETE -> R.string.vault_busy_delete
        VaultBusyOp.VERIFY -> R.string.vault_busy_verify
        VaultBusyOp.RENAME -> R.string.vault_busy_rename
        VaultBusyOp.OPEN -> R.string.vault_busy_open
    }

/** Pemetaan ekshaustif galat penamaan ke string `vault_name_err_*`. */
private fun nameErrorRes(error: VaultNameError): Int =
    when (error) {
        VaultNameError.BLANK -> R.string.vault_name_err_blank
        VaultNameError.ILLEGAL_CHAR -> R.string.vault_name_err_illegal_char
        VaultNameError.RESERVED -> R.string.vault_name_err_reserved
        VaultNameError.TOO_LONG -> R.string.vault_name_err_too_long
    }
