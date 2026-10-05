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

package com.hyperexplorer.feature.network

import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hyperexplorer.data.remote.RemoteConnection
import com.hyperexplorer.data.remote.RemoteEntry
import com.hyperexplorer.data.remote.RemoteProtocol
import com.hyperexplorer.data.remote.RemoteUri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DATE_FORMAT = SimpleDateFormat("d MMM yyyy HH:mm", Locale.getDefault())

/** Tinta ikon folder agar khas kuning seperti pengelola berkas umum. */
private val FOLDER_ICON_TINT = Color(0xFFF9A825)

/** Hijau sukses untuk ikon hasil uji koneksi. */
private val SUCCESS_TINT = Color(0xFF2E7D32)

/**
 * Layar lokasi jaringan: kelola sambungan tersimpan (SMB/FTP/SFTP/WebDAV/Nextcloud)
 * dan jelajahi isinya. State holder dibuat internal lewat [remember] (bukan
 * rememberSaveable karena klien jaringan tak bisa dihidupkan ulang otomatis)
 * dan klien ditutup lewat [DisposableEffect] saat layar dibuang.
 */
@Composable
fun NetworkLocationsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val vm = remember { NetworkViewModel(context.applicationContext) }
    val snackbarHostState = remember { SnackbarHostState() }
    var deleteConnectionTarget by remember { mutableStateOf<RemoteConnection?>(null) }
    var deleteEntryTarget by remember { mutableStateOf<RemoteEntry?>(null) }
    var renameTarget by remember { mutableStateOf<RemoteEntry?>(null) }
    var showNewFolderDialog by remember { mutableStateOf(false) }

    DisposableEffect(vm) {
        onDispose { vm.closeCurrent() }
    }

    LaunchedEffect(vm.downloadTarget) {
        val target = vm.downloadTarget ?: return@LaunchedEffect
        vm.consumeDownloadResult()
        snackbarHostState.showSnackbar(context.getString(R.string.network_download_success, target))
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        floatingActionButton = {
            when (vm.screen) {
                NetworkScreen.CONNECTIONS ->
                    FloatingActionButton(onClick = { vm.openAddDialog() }) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = stringResource(R.string.network_add_location),
                        )
                    }
                NetworkScreen.BROWSING ->
                    FloatingActionButton(onClick = { showNewFolderDialog = true }) {
                        Icon(
                            imageVector = Icons.Outlined.CreateNewFolder,
                            contentDescription = stringResource(R.string.network_new_folder),
                        )
                    }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (vm.screen) {
                NetworkScreen.CONNECTIONS ->
                    ConnectionsContent(
                        vm = vm,
                        onDeleteRequest = { connection -> deleteConnectionTarget = connection },
                    )
                NetworkScreen.BROWSING ->
                    BrowsingContent(
                        vm = vm,
                        onRenameRequest = { entry -> renameTarget = entry },
                        onDeleteRequest = { entry -> deleteEntryTarget = entry },
                    )
            }
        }
    }

    if (vm.formVisible) {
        ConnectionFormDialog(vm = vm, onDismiss = { vm.dismissForm() })
    }

    deleteConnectionTarget?.let { connection ->
        ConfirmDeleteDialog(
            titleRes = R.string.network_delete_connection_title,
            name = connection.displayName.ifBlank { connection.host },
            onConfirm = {
                vm.deleteConnection(connection.id)
                deleteConnectionTarget = null
            },
            onDismiss = { deleteConnectionTarget = null },
        )
    }

    deleteEntryTarget?.let { entry ->
        ConfirmDeleteDialog(
            titleRes = R.string.network_delete,
            name = entry.name,
            onConfirm = {
                vm.deleteEntry(entry)
                deleteEntryTarget = null
            },
            onDismiss = { deleteEntryTarget = null },
        )
    }

    renameTarget?.let { entry ->
        RenameEntryDialog(
            entry = entry,
            onConfirm = { newName ->
                vm.renameEntry(entry, newName)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    if (showNewFolderDialog) {
        NewFolderDialog(
            onConfirm = { name ->
                vm.mkdir(name)
                showNewFolderDialog = false
            },
            onDismiss = { showNewFolderDialog = false },
        )
    }
}

@Composable
private fun ConnectionsContent(
    vm: NetworkViewModel,
    onDeleteRequest: (RemoteConnection) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.network_title),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
            style = MaterialTheme.typography.titleMedium,
        )
        if (vm.connectionsLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else if (vm.connections.isEmpty()) {
            EmptyMessage(message = stringResource(R.string.network_no_connections))
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(vm.connections, key = { it.id }) { connection ->
                    ConnectionRow(
                        connection = connection,
                        onClick = { vm.openConnection(connection) },
                        onEdit = { vm.openEditDialog(connection) },
                        onDelete = { onDeleteRequest(connection) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionRow(
    connection: RemoteConnection,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = protocolIcon(connection.protocol),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = connection.displayName.ifBlank { connection.host },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = RemoteUri.toDisplayString(connection) + " · " + protocolLabel(connection.protocol),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.network_cd_more))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.network_edit)) },
                    leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onEdit()
                    },
                )
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.network_delete)) },
                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
}

@Composable
private fun BrowsingContent(
    vm: NetworkViewModel,
    onRenameRequest: (RemoteEntry) -> Unit,
    onDeleteRequest: (RemoteEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (vm.canNavigateUp) {
                IconButton(onClick = { vm.navigateUp() }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.network_up),
                    )
                }
            }
            Text(
                text = vm.currentPath,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = { vm.refresh() }) {
                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.network_cd_refresh))
            }
            IconButton(onClick = { vm.closeBrowsing() }) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.network_close))
            }
        }
        if (vm.loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        vm.error?.let { error ->
            ErrorBanner(error = error, detail = vm.errorDetail, onRetry = { vm.refresh() })
        }
        when {
            vm.error == null && !vm.loading && vm.entries.isEmpty() ->
                EmptyMessage(message = stringResource(R.string.network_empty_folder))
            else ->
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(vm.entries, key = { it.path }) { entry ->
                        RemoteEntryRow(
                            entry = entry,
                            onClick = { vm.openDirectory(entry) },
                            onDownload = { vm.download(entry) },
                            onRename = { onRenameRequest(entry) },
                            onDelete = { onDeleteRequest(entry) },
                        )
                    }
                }
        }
    }
}

@Composable
private fun RemoteEntryRow(
    entry: RemoteEntry,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (entry.isDirectory) Icons.Outlined.Folder else Icons.Outlined.InsertDriveFile,
            // A11y: jenis entri (folder/berkas) tidak tersedia dalam teks manapun pada baris
            // (subtitle direktori hanya tanggal), jadi ikon ini diberi deskripsi dinamis.
            contentDescription =
                stringResource(
                    if (entry.isDirectory) R.string.network_cd_folder else R.string.network_cd_file,
                ),
            tint = if (entry.isDirectory) FOLDER_ICON_TINT else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitleFor(entry),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.network_cd_more))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.network_cd_download)) },
                    leadingIcon = { Icon(Icons.Outlined.Download, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onDownload()
                    },
                )
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.network_rename)) },
                    leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.network_delete)) },
                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
}

@Composable
private fun ErrorBanner(
    error: NetworkError,
    detail: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.errorContainer) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = stringResource(errorRes(error)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            if (detail != null) {
                Text(
                    text = stringResource(R.string.network_error_reason, detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            TextButton(onClick = onRetry) {
                Text(text = stringResource(R.string.network_retry))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionFormDialog(
    vm: NetworkViewModel,
    onDismiss: () -> Unit,
) {
    val form = vm.formState
    val editing = form.id != 0L
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text =
                    if (editing) {
                        stringResource(R.string.network_edit_connection)
                    } else {
                        stringResource(R.string.network_add_location)
                    },
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ProtocolDropdown(selected = form.protocol, onSelect = { vm.updateProtocol(it) })
                OutlinedTextField(
                    value = form.host,
                    onValueChange = { value -> vm.updateForm { it.copy(host = value) } },
                    label = { Text(text = stringResource(R.string.network_host)) },
                    singleLine = true,
                    isError = NetworkFormValidator.ValidationError.EMPTY_HOST in vm.formErrors,
                    supportingText = {
                        if (NetworkFormValidator.ValidationError.EMPTY_HOST in vm.formErrors) {
                            Text(text = stringResource(R.string.network_error_host_required))
                        }
                    },
                )
                OutlinedTextField(
                    value = form.port,
                    onValueChange = { value -> vm.updateForm { it.copy(port = value.filter { ch -> ch.isDigit() }) } },
                    label = { Text(text = stringResource(R.string.network_port)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = NetworkFormValidator.ValidationError.INVALID_PORT in vm.formErrors,
                    supportingText = {
                        if (NetworkFormValidator.ValidationError.INVALID_PORT in vm.formErrors) {
                            Text(text = stringResource(R.string.network_error_port_invalid))
                        }
                    },
                )
                OutlinedTextField(
                    value = form.basePath,
                    onValueChange = { value -> vm.updateForm { it.copy(basePath = value) } },
                    label = {
                        Text(
                            text =
                                when (form.protocol) {
                                    RemoteProtocol.SMB -> stringResource(R.string.network_share)
                                    RemoteProtocol.NEXTCLOUD -> stringResource(R.string.network_field_server_prefix)
                                    RemoteProtocol.FTP, RemoteProtocol.SFTP, RemoteProtocol.WEBDAV ->
                                        stringResource(R.string.network_base_path)
                                },
                        )
                    },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = form.username,
                    onValueChange = { value -> vm.updateForm { it.copy(username = value) } },
                    label = { Text(text = stringResource(R.string.network_username)) },
                    singleLine = true,
                    isError = NetworkFormValidator.ValidationError.EMPTY_USERNAME in vm.formErrors,
                    supportingText = {
                        if (NetworkFormValidator.ValidationError.EMPTY_USERNAME in vm.formErrors) {
                            Text(text = stringResource(R.string.network_error_username_required))
                        }
                    },
                )
                PasswordField(
                    value = form.password,
                    onValueChange = { value -> vm.updateForm { it.copy(password = value) } },
                )
                if (form.protocol == RemoteProtocol.NEXTCLOUD) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.network_tls),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Switch(
                            checked = form.secure,
                            onCheckedChange = { checked -> vm.updateForm { it.copy(secure = checked) } },
                        )
                    }
                    LoginFlowSection(vm = vm)
                }
                TestConnectionRow(vm = vm)
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.saveConnection() }, enabled = form.host.isNotBlank()) {
                Text(text = stringResource(R.string.network_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.network_cancel)) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProtocolDropdown(
    selected: RemoteProtocol,
    onSelect: (RemoteProtocol) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = protocolLabel(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(text = stringResource(R.string.network_protocol)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            RemoteProtocol.entries.forEach { protocol ->
                DropdownMenuItem(
                    text = { Text(text = protocolLabel(protocol)) },
                    onClick = {
                        expanded = false
                        onSelect(protocol)
                    },
                )
            }
        }
    }
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(text = stringResource(R.string.network_password)) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription =
                        if (visible) {
                            stringResource(R.string.network_hide_password)
                        } else {
                            stringResource(R.string.network_show_password)
                        },
                )
            }
        },
    )
}

@Composable
private fun TestConnectionRow(vm: NetworkViewModel) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = { vm.testConnection() },
                enabled = vm.testStatus != ConnectionTestStatus.RUNNING,
            ) {
                Text(text = stringResource(R.string.network_test_connection))
            }
            Spacer(modifier = Modifier.width(8.dp))
            when (vm.testStatus) {
                ConnectionTestStatus.RUNNING ->
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                ConnectionTestStatus.SUCCESS ->
                    Icon(imageVector = Icons.Filled.CheckCircle, contentDescription = null, tint = SUCCESS_TINT)
                ConnectionTestStatus.FAILURE ->
                    Icon(
                        imageVector = Icons.Filled.Error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                ConnectionTestStatus.IDLE -> Unit
            }
        }
        when (vm.testStatus) {
            ConnectionTestStatus.SUCCESS -> {
                Text(
                    text = stringResource(R.string.network_test_success),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            ConnectionTestStatus.FAILURE -> {
                vm.testDetail?.let { detail ->
                    Text(
                        text = stringResource(R.string.network_test_failed, detail),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            ConnectionTestStatus.IDLE, ConnectionTestStatus.RUNNING -> Unit
        }
    }
}

/**
 * Bagian Login flow v2 Nextcloud pada dialog form: tombol mulai, status
 * menunggu/sukses/gagal, dan pembukaan URL persetujuan di peramban. URL dibuka
 * reaktif lewat [LaunchedEffect] (bukan langsung pada onClick) karena start()
 * berjalan asinkron di ViewModel; bila tidak ada peramban, status menjadi FAILED.
 */
@Composable
private fun LoginFlowSection(vm: NetworkViewModel) {
    val context = LocalContext.current
    LaunchedEffect(vm.loginFlowUrl) {
        val url = vm.loginFlowUrl ?: return@LaunchedEffect
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            vm.consumeLoginFlowUrl()
        } catch (e: Exception) {
            // Tidak ada aktivitas peramban / ditolak sistem — laporkan sebagai kegagalan.
            vm.onLoginFlowBrowserMissing(e.message)
        }
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        TextButton(
            onClick = { vm.startLoginFlow() },
            enabled = vm.loginFlowStatus != LoginFlowStatus.WAITING_BROWSER,
        ) {
            Text(text = stringResource(R.string.network_login_flow_button))
        }
        when (vm.loginFlowStatus) {
            LoginFlowStatus.WAITING_BROWSER -> {
                Text(
                    text = stringResource(R.string.network_login_flow_waiting),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { vm.cancelLoginFlow() }) {
                    Text(text = stringResource(R.string.network_login_flow_cancel))
                }
            }
            LoginFlowStatus.SUCCESS ->
                Text(
                    text = stringResource(R.string.network_login_flow_success),
                    style = MaterialTheme.typography.bodySmall,
                )
            LoginFlowStatus.FAILED ->
                vm.loginFlowDetail?.let { detail ->
                    Text(
                        text = stringResource(R.string.network_login_flow_failed, detail),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            LoginFlowStatus.IDLE -> Unit
        }
    }
}

@Composable
private fun ConfirmDeleteDialog(
    @StringRes titleRes: Int,
    name: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(titleRes)) },
        text = { Text(text = stringResource(R.string.network_delete_confirm, name)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(text = stringResource(R.string.network_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.network_cancel)) }
        },
    )
}

@Composable
private fun RenameEntryDialog(
    entry: RemoteEntry,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(entry) { mutableStateOf(entry.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.network_rename)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(text = stringResource(R.string.network_new_name)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) {
                Text(text = stringResource(R.string.network_rename))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.network_cancel)) }
        },
    )
}

@Composable
private fun NewFolderDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.network_new_folder)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(text = stringResource(R.string.network_folder_name)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) {
                Text(text = stringResource(R.string.network_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.network_cancel)) }
        },
    )
}

/** Pesan kosong di tengah layar (padanan EmptyState core-ui tanpa dependensi tambahan). */
@Composable
private fun EmptyMessage(
    message: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private fun protocolIcon(protocol: RemoteProtocol): ImageVector =
    when (protocol) {
        RemoteProtocol.FTP -> Icons.Outlined.Dns
        RemoteProtocol.SFTP -> Icons.Outlined.Terminal
        RemoteProtocol.SMB -> Icons.Outlined.Storage
        RemoteProtocol.WEBDAV -> Icons.Outlined.CloudQueue
        RemoteProtocol.NEXTCLOUD -> Icons.Outlined.Cloud
    }

@Composable
private fun protocolLabel(protocol: RemoteProtocol): String =
    when (protocol) {
        RemoteProtocol.FTP -> stringResource(R.string.network_protocol_ftp)
        RemoteProtocol.SFTP -> stringResource(R.string.network_protocol_sftp)
        RemoteProtocol.SMB -> stringResource(R.string.network_protocol_smb)
        RemoteProtocol.WEBDAV -> stringResource(R.string.network_protocol_webdav)
        RemoteProtocol.NEXTCLOUD -> stringResource(R.string.network_protocol_nextcloud)
    }

private fun errorRes(error: NetworkError): Int =
    when (error) {
        NetworkError.CONNECT_FAILED -> R.string.network_error_connect
        NetworkError.LIST_FAILED -> R.string.network_error_read_folder
        NetworkError.DELETE_FAILED -> R.string.network_error_delete
        NetworkError.RENAME_FAILED -> R.string.network_error_rename
        NetworkError.DOWNLOAD_FAILED -> R.string.network_error_download
        NetworkError.MKDIR_FAILED -> R.string.network_error_mkdir
    }

@Composable
private fun subtitleFor(entry: RemoteEntry): String {
    val date = DATE_FORMAT.format(Date(entry.lastModified))
    return if (entry.isDirectory) date else humanSize(entry.size) + " · " + date
}

/** Ukuran manusiawi B/KB/MB/GB dengan 1 desimal. */
private fun humanSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    var value = bytes.toDouble()
    val units = listOf("KB", "MB", "GB")
    var index = -1
    while (value >= 1024 && index < units.size - 1) {
        value /= 1024
        index++
    }
    return String.format(Locale.ROOT, "%.1f %s", value, units[index])
}
