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

package com.hyperexplorer.feature.transfer.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.hyperexplorer.feature.transfer.R
import com.hyperexplorer.feature.transfer.ftp.FtpConfig
import com.hyperexplorer.feature.transfer.ftp.FtpService
import java.io.File

/**
 * Layar pengaturan server FTP: kredensial, tombol mulai/berhenti, status,
 * petunjuk pemakaian, dan peringatan keamanan.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FtpServerScreen(
    rootDir: File,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val running by FtpService.running.collectAsState()
    val serverError by FtpService.error.collectAsState()
    val serverPort by FtpService.port.collectAsState()

    var portText by remember { mutableStateOf("2121") }
    var username by remember { mutableStateOf("hyper") }
    var password by remember { mutableStateOf(randomPassword()) }
    var showPassword by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<Int?>(null) }

    fun validateInput(): Boolean {
        val port = portText.trim().toIntOrNull()
        return when {
            port == null || (port != 0 && (port < 1024 || port > 65535)) -> {
                validationError = R.string.transfer_error_port
                false
            }
            username.isBlank() -> {
                validationError = R.string.transfer_error_username_empty
                false
            }
            password.isBlank() -> {
                validationError = R.string.transfer_error_password_empty
                false
            }
            else -> {
                validationError = null
                true
            }
        }
    }

    fun startServer() {
        FtpService.start(
            context = context,
            config =
                FtpConfig(
                    port = portText.trim().toIntOrNull() ?: 2121,
                    username = username.trim(),
                    password = password,
                    rootDir = rootDir,
                ),
        )
    }

    // Izin notifikasi hanya kenyamanan: bila ditolak, server tetap boleh jalan.
    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            startServer()
        }

    val onStart: () -> Unit = {
        if (validateInput()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                startServer()
            }
        }
    }
    val onStop: () -> Unit = { FtpService.stop(context) }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = stringResource(R.string.transfer_title), style = MaterialTheme.typography.titleLarge)

        StatusCard(running = running, serverError = serverError, port = serverPort)

        OutlinedTextField(
            value = portText,
            onValueChange = { portText = it.filter { character -> character.isDigit() } },
            label = { Text(text = stringResource(R.string.transfer_port_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            enabled = !running,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text(text = stringResource(R.string.transfer_username_label)) },
            singleLine = true,
            enabled = !running,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(text = stringResource(R.string.transfer_password_label)) },
            singleLine = true,
            enabled = !running,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(
                        imageVector = if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription =
                            if (showPassword) {
                                stringResource(R.string.transfer_hide_password)
                            } else {
                                stringResource(R.string.transfer_show_password)
                            },
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        validationError?.let { messageRes ->
            Text(
                text = stringResource(messageRes),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Button(
            onClick = if (running) onStop else onStart,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = if (running) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                contentDescription = null,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = stringResource(if (running) R.string.transfer_stop else R.string.transfer_start))
        }

        UsageCard()
        SecurityWarningCard()
    }
}

@Composable
private fun StatusCard(
    running: Boolean,
    serverError: String?,
    port: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (running) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text =
                    if (running) {
                        stringResource(R.string.transfer_status_running, FtpService.localIpText(context), port)
                    } else {
                        stringResource(R.string.transfer_status_inactive)
                    },
                style = MaterialTheme.typography.titleMedium,
            )
            if (serverError != null) {
                Text(
                    text = stringResource(R.string.transfer_stopped_reason, serverError),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun UsageCard(modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = stringResource(R.string.transfer_usage_title), style = MaterialTheme.typography.titleSmall)
            Text(
                text = stringResource(R.string.transfer_usage_steps),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SecurityWarningCard(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Text(
            text = stringResource(R.string.transfer_security_warning),
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private const val PASSWORD_LENGTH = 6

/** Kata sandi awal acak 6 karakter (tanpa karakter ambigu 0/O/1/l/I). */
private fun randomPassword(): String {
    val alphabet = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789"
    return buildString {
        repeat(PASSWORD_LENGTH) { append(alphabet.random()) }
    }
}
