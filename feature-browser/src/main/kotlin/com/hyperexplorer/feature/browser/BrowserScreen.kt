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

package com.hyperexplorer.feature.browser

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import com.hyperexplorer.core.model.FileNode
import com.hyperexplorer.core.ui.components.EmptyState
import com.hyperexplorer.core.ui.components.FileRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    state: BrowserState,
    hasStorageAccess: Boolean,
    onRequestStorageAccess: () -> Unit,
    onOpenFile: (FileNode) -> Unit,
) {
    val ui by state.ui.collectAsState()
    val clipboard by state.clipboard.collectAsState()
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = if (ui.current == ui.root) "Hyper Explorer" else ui.current.name) },
                navigationIcon = {
                    if (ui.current != ui.root) {
                        IconButton(onClick = { state.up() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Naik")
                        }
                    }
                },
                actions = {
                    if (clipboard != null) {
                        IconButton(onClick = { state.paste() }) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = "Tempel")
                        }
                    }
                    IconButton(onClick = { state.setSelectionMode(!ui.selectionMode) }) {
                        Icon(
                            if (ui.selectionMode) Icons.Filled.Close else Icons.Filled.CheckCircle,
                            contentDescription = "Mode seleksi",
                        )
                    }
                    IconButton(onClick = { showNewFolderDialog = true }) {
                        Icon(Icons.Filled.Create, contentDescription = "Folder baru")
                    }
                    IconButton(onClick = { state.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Segarkan")
                    }
                },
            )
        },
        bottomBar = {
            if (ui.selectionMode && ui.selection.isNotEmpty()) {
                SelectionBar(
                    count = ui.selection.size,
                    onDelete = { state.deleteSelected() },
                    onCopy = { state.copySelected() },
                    onCut = { state.cutSelected() },
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (ui.loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (!hasStorageAccess) {
                StorageAccessBanner(onRequest = onRequestStorageAccess)
            }
            when {
                ui.error != null -> EmptyState(message = ui.error ?: "")
                ui.items.isEmpty() -> EmptyState(message = "Folder kosong")
                else ->
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(ui.items, key = { it.path }) { node ->
                            FileRow(
                                node = node,
                                selected = node.path in ui.selection,
                                onClick = {
                                    when {
                                        ui.selectionMode -> state.toggleSelection(node)
                                        node.isDirectory -> state.open(node)
                                        else -> onOpenFile(node)
                                    }
                                },
                            )
                        }
                    }
            }
        }
    }

    if (showNewFolderDialog) {
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            title = { Text(text = "Folder baru") },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text(text = "Nama folder") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (folderName.isNotBlank()) state.createFolder(folderName.trim())
                        folderName = ""
                        showNewFolderDialog = false
                    },
                ) { Text(text = "Buat") }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) { Text(text = "Batal") }
            },
        )
    }
}

@Composable
private fun StorageAccessBanner(
    onRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.errorContainer) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Berikan akses semua berkas agar file manager bekerja penuh.",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onRequest) { Text(text = "Izinkan") }
        }
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Hapus") }
            IconButton(onClick = onCopy) { Icon(Icons.Filled.ContentCopy, contentDescription = "Salin") }
            IconButton(onClick = onCut) { Icon(Icons.Filled.ContentCut, contentDescription = "Potong") }
            Spacer(modifier = Modifier.weight(1f))
            Text(text = "$count dipilih", style = MaterialTheme.typography.labelLarge)
        }
    }
}
