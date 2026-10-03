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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.hyperexplorer.core.model.FileNode
import com.hyperexplorer.core.ui.theme.HyperExplorerTheme
import com.hyperexplorer.data.local.FileRepository
import com.hyperexplorer.feature.apps.AppsState
import com.hyperexplorer.feature.apps.ui.AppsScreen
import com.hyperexplorer.feature.browser.BrowserScreen
import com.hyperexplorer.feature.browser.BrowserState
import com.hyperexplorer.feature.media.ui.ImageViewerScreen
import com.hyperexplorer.feature.media.ui.TextEditorScreen
import com.hyperexplorer.feature.media.viewer.ViewerRoute
import com.hyperexplorer.feature.media.viewer.ViewerRouter
import com.hyperexplorer.feature.settings.ThemeMode
import com.hyperexplorer.feature.settings.ThemePrefs
import com.hyperexplorer.feature.settings.ui.SettingsScreen
import com.hyperexplorer.feature.tools.ui.StorageAnalyzerScreen
import com.hyperexplorer.feature.tools.zip.ZipEngine
import com.hyperexplorer.feature.transfer.ui.FtpServerScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/** Layar utama yang dapat dipilih lewat navigasi bawah. */
private enum class Screen(val label: String) {
    BROWSER("Berkas"),
    APPS("Aplikasi"),
    STORAGE("Penyimpanan"),
    FTP("FTP"),
    SETTINGS("Pengaturan"),
}

class MainActivity : ComponentActivity() {
    private var hasStorageAccess by mutableStateOf(false)
    private var screen by mutableStateOf(Screen.BROWSER)
    private var viewer by mutableStateOf<ViewerRoute?>(null)
    private var themeMode by mutableStateOf(ThemeMode.SYSTEM)

    private lateinit var browserState: BrowserState
    private lateinit var appsState: AppsState
    private lateinit var themePrefs: ThemePrefs

    private val zipEngine = ZipEngine()
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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
        appsState = AppsState(this)
        themePrefs = ThemePrefs(this)
        themeMode = themePrefs.read()

        setContent {
            val darkTheme =
                when (themeMode) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                }
            HyperExplorerTheme(darkTheme = darkTheme) {
                AppContent()
            }
        }
    }

    @Composable
    private fun AppContent() {
        val route = viewer
        when (route) {
            is ViewerRoute.Image -> ImageViewerScreen(path = route.path, onClose = { viewer = null })
            is ViewerRoute.Text -> TextEditorScreen(path = route.path, onClose = { viewer = null })
            null -> MainScaffold()
        }
    }

    @Composable
    private fun MainScaffold() {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    val screens = Screen.entries
                    for (item in screens) {
                        NavigationBarItem(
                            selected = screen == item,
                            onClick = { screen = item },
                            icon = { Icon(imageVector = item.icon(), contentDescription = null) },
                            label = { Text(text = item.label) },
                        )
                    }
                }
            },
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
                when (screen) {
                    Screen.BROWSER ->
                        BrowserScreen(
                            state = browserState,
                            hasStorageAccess = hasStorageAccess,
                            onRequestStorageAccess = { requestStorageAccess() },
                            onOpenFile = { openFile(it) },
                            onZip = { zipSelected() },
                        )
                    Screen.APPS -> AppsScreen(state = appsState)
                    Screen.STORAGE -> StorageAnalyzerScreen(root = storageRoot())
                    Screen.FTP -> FtpServerScreen(rootDir = storageRoot())
                    Screen.SETTINGS ->
                        SettingsScreen(
                            currentMode = themeMode,
                            onSelectMode = { mode ->
                                themeMode = mode
                                themePrefs.write(mode)
                            },
                            appVersion = appVersion(),
                        )
                }
            }
        }
    }

    private fun Screen.icon() =
        when (this) {
            Screen.BROWSER -> Icons.Filled.Folder
            Screen.APPS -> Icons.Filled.Apps
            Screen.STORAGE -> Icons.Filled.PieChart
            Screen.FTP -> Icons.Filled.Computer
            Screen.SETTINGS -> Icons.Filled.Settings
        }

    private fun storageRoot(): File = Environment.getExternalStorageDirectory() ?: filesDir

    private fun appVersion(): String =
        runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        }.getOrDefault("")

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

    /** Buka berkas: gambar/teks memakai penampil internal, lainnya diserahkan ke aplikasi eksternal. */
    private fun openFile(node: FileNode) {
        val route = ViewerRouter.routeFor(node.path)
        if (route != null) {
            viewer = route
        } else {
            openWith(node)
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

    /** Kompres berkas terpilih menjadi arsip ZIP di folder yang sedang dibuka. */
    private fun zipSelected() {
        val current = browserState.ui.value.current
        val sources = browserState.ui.value.selection.map { File(it) }.filter { it.exists() }
        if (sources.isEmpty()) return
        val archiveName = if (current == browserState.ui.value.root) "arsip" else current.name
        val target = File(current, "$archiveName.zip")
        mainScope.launch {
            val result = zipEngine.zipFiles(sources, target)
            browserState.clearSelection()
            browserState.refresh()
            val message =
                result.fold(
                    onSuccess = { "Arsip dibuat: ${it.name}" },
                    onFailure = { "Gagal membuat arsip ZIP" },
                )
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val REQUEST_CODE_STORAGE = 1001
    }
}
