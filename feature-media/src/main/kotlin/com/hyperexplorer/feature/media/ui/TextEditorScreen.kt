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

package com.hyperexplorer.feature.media.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hyperexplorer.core.ui.components.EmptyState
import com.hyperexplorer.feature.media.R
import com.hyperexplorer.feature.media.text.TextFileIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Editor teks sederhana: muat berkas, sunting monospace, simpan atomik via [TextFileIO].
 * Konfirmasi buang perubahan ditampilkan bila menutup dalam keadaan belum disimpan.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextEditorScreen(
    path: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val file = remember(path) { File(path) }
    var text by remember(path) { mutableStateOf("") }
    var isDirty by remember(path) { mutableStateOf(false) }
    var saving by remember(path) { mutableStateOf(false) }
    var loading by remember(path) { mutableStateOf(true) }
    var loadFailed by remember(path) { mutableStateOf(false) }
    var truncated by remember(path) { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(path) {
        val loaded =
            withContext(Dispatchers.IO) {
                try {
                    TextFileIO.read(file)
                } catch (error: IOException) {
                    null
                }
            }
        loading = false
        val content = loaded
        if (content == null) {
            loadFailed = true
        } else {
            text = content.text
            truncated = content.truncated
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(text = path.substringAfterLast('/'))
                        if (isDirty) {
                            Text(text = stringResource(R.string.media_modified), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            scope.launch {
                                saving = true
                                val result = withContext(Dispatchers.IO) { TextFileIO.write(file, text) }
                                saving = false
                                if (result.isSuccess) isDirty = false
                            }
                        },
                        enabled = isDirty && !saving,
                    ) {
                        Icon(Icons.Filled.Save, contentDescription = stringResource(R.string.media_cd_save))
                    }
                    IconButton(onClick = { if (isDirty) showDiscardDialog = true else onClose() }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.media_cd_close))
                    }
                },
            )
        },
    ) { padding ->
        when {
            loading ->
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            loadFailed ->
                EmptyState(message = stringResource(R.string.media_error_open_text), modifier = Modifier.padding(padding))
            else ->
                Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                    if (truncated) {
                        Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.errorContainer) {
                            Text(
                                text = stringResource(R.string.media_truncated_warning),
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    BasicTextField(
                        value = text,
                        onValueChange = { value ->
                            text = value
                            isDirty = true
                        },
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(16.dp),
                        textStyle =
                            TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                    )
                }
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text(text = stringResource(R.string.media_discard_title)) },
            text = { Text(text = stringResource(R.string.media_discard_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardDialog = false
                        onClose()
                    },
                ) { Text(text = stringResource(R.string.media_discard_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text(text = stringResource(R.string.media_cancel)) }
            },
        )
    }
}
