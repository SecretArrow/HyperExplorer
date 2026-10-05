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

package com.hyperexplorer.feature.apps.ui

import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hyperexplorer.core.model.FileNode
import com.hyperexplorer.core.ui.components.EmptyState
import com.hyperexplorer.feature.apps.AppEntry
import com.hyperexplorer.feature.apps.AppManager
import com.hyperexplorer.feature.apps.AppsState
import com.hyperexplorer.feature.apps.R
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(
    state: AppsState,
    onBackupDone: (Result<File>) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val ui by state.ui.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.apps_title)) },
                actions = {
                    IconButton(onClick = { state.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.apps_cd_refresh))
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (ui.loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            when {
                ui.error != null -> EmptyState(message = stringResource(R.string.apps_error_load_list))
                ui.apps.isEmpty() -> EmptyState(message = stringResource(R.string.apps_empty))
                else ->
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(ui.apps, key = { it.packageName }) { app ->
                            val cannotOpenApp = stringResource(R.string.apps_error_launch)
                            val backupFailedMessage = stringResource(R.string.apps_backup_failed)
                            // Konfigurasi-aware: resolusi template string di komposisi (bukan
                            // context.getString di coroutine — dilarang lint compose 2026).
                            val backupSuccessTemplate = stringResource(R.string.apps_backup_success)
                            AppRow(
                                app = app,
                                onClick = {
                                    if (!AppManager.launch(context, app.packageName)) {
                                        Toast.makeText(context, cannotOpenApp, Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onBackup = {
                                    scope.launch {
                                        val targetDir =
                                            File(
                                                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                                                "HyperExplorer",
                                            )
                                        val result = AppManager.backupApk(app, targetDir)
                                        onBackupDone(result)
                                        val message =
                                            result.fold(
                                                onSuccess = { file ->
                                                    String.format(backupSuccessTemplate, file.absolutePath)
                                                },
                                                onFailure = { backupFailedMessage },
                                            )
                                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                    }
                                },
                                onUninstall = { AppManager.uninstall(context, app.packageName) },
                            )
                        }
                    }
            }
        }
    }
}

@Composable
private fun AppRow(
    app: AppEntry,
    onClick: () -> Unit,
    onBackup: () -> Unit,
    onUninstall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Android,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = app.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "${app.packageName} · ${app.versionName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = FileNode.humanSize(app.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (app.isSystem) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.apps_badge_system),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
        IconButton(onClick = onBackup) {
            Icon(Icons.Filled.Save, contentDescription = stringResource(R.string.apps_cd_backup))
        }
        IconButton(onClick = onUninstall) {
            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.apps_cd_uninstall))
        }
    }
}
