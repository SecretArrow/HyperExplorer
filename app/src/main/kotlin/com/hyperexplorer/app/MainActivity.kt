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

package com.hyperexplorer.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.hyperexplorer.core.model.FileNode
import com.hyperexplorer.core.ui.theme.HyperExplorerTheme
import com.hyperexplorer.data.local.FileRepository
import com.hyperexplorer.feature.browser.BrowserScreen
import com.hyperexplorer.feature.browser.BrowserState
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var hasStorageAccess by mutableStateOf(false)

    private lateinit var browserState: BrowserState

    private val allFilesSettingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            hasStorageAccess = checkStorageAccess()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hasStorageAccess = checkStorageAccess()

        val trashDir = File(filesDir, "trash").apply { mkdirs() }
        val repository = FileRepository(trashDir)
        val root = Environment.getExternalStorageDirectory() ?: filesDir
        browserState = BrowserState(repository, root)

        setContent {
            HyperExplorerTheme {
                BrowserScreen(
                    state = browserState,
                    hasStorageAccess = hasStorageAccess,
                    onRequestStorageAccess = { requestStorageAccess() },
                    onOpenFile = { openWith(it) },
                )
            }
        }
    }

    private fun checkStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val appIntent =
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName"),
                )
            runCatching { allFilesSettingsLauncher.launch(appIntent) }
                .onFailure {
                    allFilesSettingsLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQUEST_CODE_STORAGE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_STORAGE) {
            hasStorageAccess = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun openWith(node: FileNode) {
        val file = File(node.path)
        if (!file.isFile) return
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val mime =
            MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(node.name.substringAfterLast('.', "").lowercase(Locale.ROOT))
                ?: "application/octet-stream"
        val intent =
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(intent) }
    }

    companion object {
        private const val REQUEST_CODE_STORAGE = 1001
    }
}
