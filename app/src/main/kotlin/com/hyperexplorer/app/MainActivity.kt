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
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.biometric.BiometricManager
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.os.LocaleListCompat
import com.hyperexplorer.core.model.FileNode
import com.hyperexplorer.core.ui.theme.HyperExplorerTheme
import com.hyperexplorer.data.local.FileRepository
import com.hyperexplorer.feature.apps.AppsState
import com.hyperexplorer.feature.apps.ui.AppsScreen
import com.hyperexplorer.feature.browser.BrowserScreen
import com.hyperexplorer.feature.browser.BrowserState
import com.hyperexplorer.feature.media.player.MediaPlayerScreen
import com.hyperexplorer.feature.media.ui.ImageViewerScreen
import com.hyperexplorer.feature.media.ui.TextEditorScreen
import com.hyperexplorer.feature.media.viewer.ViewerRoute
import com.hyperexplorer.feature.media.viewer.ViewerRouter
import com.hyperexplorer.feature.network.NetworkLocationsScreen
import com.hyperexplorer.feature.settings.LanguageMode
import com.hyperexplorer.feature.settings.LanguagePrefs
import com.hyperexplorer.feature.settings.ThemeMode
import com.hyperexplorer.feature.settings.ThemePrefs
import com.hyperexplorer.feature.settings.lock.AppLockHelper
import com.hyperexplorer.feature.settings.lock.AppLockPrefs
import com.hyperexplorer.feature.settings.lock.AppLockScreen
import com.hyperexplorer.feature.settings.ui.SettingsScreen
import com.hyperexplorer.feature.sync.ui.SyncScreen
import com.hyperexplorer.feature.tools.ui.StorageAnalyzerScreen
import com.hyperexplorer.feature.tools.vault.ui.VaultScreen
import com.hyperexplorer.feature.tools.zip.ZipCrypto
import com.hyperexplorer.feature.tools.zip.ZipEngine
import com.hyperexplorer.feature.transfer.ui.FtpServerScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/** Layar utama yang dapat dipilih lewat navigasi bawah. */
private enum class Screen(val labelRes: Int) {
    BROWSER(R.string.app_tab_files),
    APPS(R.string.app_tab_apps),
    STORAGE(R.string.app_tab_storage),
    NETWORK(R.string.app_tab_network),
    SETTINGS(R.string.app_tab_settings),
}

/** Seksi pada tab Network: server FTP lokal, lokasi jaringan, atau sinkronisasi. */
private enum class NetworkSection(val labelRes: Int) {
    SERVER(R.string.app_network_section_server),
    LOCATIONS(R.string.app_network_section_locations),
    SYNC(R.string.app_network_section_sync),
}

/** Seksi pada tab Storage: analisis penyimpanan atau vault terenkripsi. */
private enum class StorageSection(val labelRes: Int) {
    ANALYZER(R.string.app_storage_section_analyzer),
    VAULT(R.string.app_storage_section_vault),
}

class MainActivity : AppCompatActivity() {
    private var hasStorageAccess by mutableStateOf(false)
    private var screen by mutableStateOf(Screen.BROWSER)
    private var networkSection by mutableStateOf(NetworkSection.SERVER)
    private var storageSection by mutableStateOf(StorageSection.ANALYZER)
    private var viewer by mutableStateOf<ViewerRoute?>(null)
    private var themeMode by mutableStateOf(ThemeMode.SYSTEM)
    private var languageMode by mutableStateOf(LanguageMode.SYSTEM)
    private var zipDialogVisible by mutableStateOf(false)
    private var pendingZipPassword by mutableStateOf<CharArray?>(null)
    private var extractTarget by mutableStateOf<File?>(null)
    private var appLockEnabled by mutableStateOf(false)
    private var needsLock by mutableStateOf(false)

    private lateinit var browserState: BrowserState
    private lateinit var appsState: AppsState
    private lateinit var themePrefs: ThemePrefs
    private lateinit var languagePrefs: LanguagePrefs
    private lateinit var vaultDir: File

    private val zipEngine = ZipEngine()
    private val zipCrypto = ZipCrypto()
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val allFilesSettingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            hasStorageAccess = checkStorageAccess()
        }

    /**
     * Arm ulang kunci setiap aktivitas berhenti (pindah aplikasi, layar mati, membuka
     * aplikasi eksternal) — saat kembali pengguna diminta membuka kunci lagi.
     */
    override fun onStop() {
        super.onStop()
        if (appLockEnabled) needsLock = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Pola resmi AppCompat untuk penyimpanan bahasa kustom: terapkan SEBELUM super.onCreate().
        AppCompatDelegate.setApplicationLocales(localesFor(LanguagePrefs(this).read()))
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hasStorageAccess = checkStorageAccess()

        val trashDir = File(filesDir, "trash").apply { mkdirs() }
        val repository = FileRepository(trashDir)
        vaultDir = File(filesDir, "vault")
        val root = Environment.getExternalStorageDirectory() ?: filesDir
        browserState = BrowserState(repository, root)
        appsState = AppsState(this)
        themePrefs = ThemePrefs(this)
        themeMode = themePrefs.read()
        languagePrefs = LanguagePrefs(this)
        languageMode = languagePrefs.read()
        appLockEnabled = AppLockPrefs.read(this)
        // Buka terkunci bila kunci aktif; pengguna membuka kunci lewat AppLockScreen.
        needsLock = appLockEnabled

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

    /** Petakan mode bahasa ke daftar locale AppCompat (SYSTEM = kosong, ikuti sistem). */
    private fun localesFor(mode: LanguageMode): LocaleListCompat =
        when (mode) {
            LanguageMode.SYSTEM -> LocaleListCompat.getEmptyLocaleList()
            LanguageMode.ENGLISH -> LocaleListCompat.forLanguageTags("en")
            LanguageMode.INDONESIAN -> LocaleListCompat.forLanguageTags("id")
        }

    @Composable
    private fun AppContent() {
        if (appLockEnabled && needsLock) {
            val authenticators =
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            val availability =
                AppLockHelper.mapAvailability(
                    BiometricManager.from(this@MainActivity).canAuthenticate(authenticators),
                )
            AppLockScreen(
                availability = availability,
                onUnlocked = { needsLock = false },
                onDisableLock = {
                    AppLockPrefs.write(this@MainActivity, false)
                    appLockEnabled = false
                    needsLock = false
                },
            )
            return
        }
        val route = viewer
        when (route) {
            is ViewerRoute.Image -> ImageViewerScreen(path = route.path, onClose = { viewer = null })
            is ViewerRoute.Text -> TextEditorScreen(path = route.path, onClose = { viewer = null })
            is ViewerRoute.Video -> MediaPlayerScreen(path = route.path, isVideo = true, onClose = { viewer = null })
            is ViewerRoute.Audio -> MediaPlayerScreen(path = route.path, isVideo = false, onClose = { viewer = null })
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
                            label = { Text(text = stringResource(item.labelRes)) },
                        )
                    }
                }
            },
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
                if (zipDialogVisible) {
                    ZipPasswordDialog(
                        onDismiss = {
                            zipDialogVisible = false
                            pendingZipPassword = null
                        },
                        onPlainZip = {
                            pendingZipPassword = null
                            zipDialogVisible = false
                            runZip()
                        },
                        onEncryptedZip = { password ->
                            pendingZipPassword = password
                            zipDialogVisible = false
                            runZip()
                        },
                    )
                }
                extractTarget?.let { archive ->
                    ZipExtractDialog(
                        archiveName = archive.name,
                        onDismiss = { extractTarget = null },
                        onOpenExternal = {
                            extractTarget = null
                            openWithFile(archive, archive.name)
                        },
                        onExtract = { password ->
                            extractTarget = null
                            runExtract(archive, password)
                        },
                    )
                }
                when (screen) {
                    Screen.BROWSER ->
                        BrowserScreen(
                            state = browserState,
                            hasStorageAccess = hasStorageAccess,
                            onRequestStorageAccess = { requestStorageAccess() },
                            onOpenFile = { openFile(it) },
                            onZip = { zipDialogVisible = true },
                        )
                    Screen.APPS -> AppsScreen(state = appsState)
                    Screen.STORAGE -> StorageHub()
                    Screen.NETWORK -> NetworkHub()
                    Screen.SETTINGS ->
                        SettingsScreen(
                            currentMode = themeMode,
                            onSelectMode = { mode ->
                                themeMode = mode
                                themePrefs.write(mode)
                            },
                            currentLanguage = languageMode,
                            onSelectLanguage = { mode ->
                                languageMode = mode
                                languagePrefs.write(mode)
                                // Memicu rekreasiasi aktivitas agar seluruh UI memakai bahasa baru.
                                AppCompatDelegate.setApplicationLocales(localesFor(mode))
                            },
                            appVersion = appVersion(),
                            appLockEnabled = appLockEnabled,
                            onToggleAppLock = { enabled ->
                                appLockEnabled = enabled
                                AppLockPrefs.write(this@MainActivity, enabled)
                                if (enabled) needsLock = true
                            },
                        )
                }
            }
        }
    }

    /** Hub tab Network: pemilih seksi di atas (server FTP / lokasi jaringan) + konten seksi aktif. */
    @Composable
    private fun NetworkHub() {
        Column(modifier = Modifier.fillMaxSize()) {
            SingleChoiceSegmentedButtonRow(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                val sections = NetworkSection.entries
                sections.forEachIndexed { index, section ->
                    SegmentedButton(
                        selected = networkSection == section,
                        onClick = { networkSection = section },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = sections.size),
                        label = { Text(text = stringResource(section.labelRes)) },
                    )
                }
            }
            when (networkSection) {
                NetworkSection.SERVER -> FtpServerScreen(rootDir = storageRoot())
                NetworkSection.LOCATIONS -> NetworkLocationsScreen(modifier = Modifier.fillMaxSize())
                NetworkSection.SYNC -> SyncScreen(modifier = Modifier.fillMaxSize())
            }
        }
    }

    /** Hub tab Storage: pemilih seksi di atas (analisis / vault) + konten seksi aktif. */
    @Composable
    private fun StorageHub() {
        Column(modifier = Modifier.fillMaxSize()) {
            SingleChoiceSegmentedButtonRow(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                val sections = StorageSection.entries
                sections.forEachIndexed { index, section ->
                    SegmentedButton(
                        selected = storageSection == section,
                        onClick = { storageSection = section },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = sections.size),
                        label = { Text(text = stringResource(section.labelRes)) },
                    )
                }
            }
            when (storageSection) {
                StorageSection.ANALYZER -> StorageAnalyzerScreen(root = storageRoot())
                StorageSection.VAULT ->
                    VaultScreen(
                        vaultDir = vaultDir,
                        onOpenFile = { openDecryptedFile(it) },
                    )
            }
        }
    }

    private fun Screen.icon() =
        when (this) {
            Screen.BROWSER -> Icons.Filled.Folder
            Screen.APPS -> Icons.Filled.Apps
            Screen.STORAGE -> Icons.Filled.PieChart
            Screen.NETWORK -> Icons.Filled.Computer
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

    /** Buka berkas: ZIP ditawarkan ekstraksi, gambar/teks penampil internal, lainnya aplikasi eksternal. */
    private fun openFile(node: FileNode) {
        val isZip = node.name.substringAfterLast('.', "").equals("zip", ignoreCase = true)
        if (isZip && File(node.path).isFile) {
            extractTarget = File(node.path)
            return
        }
        val route = ViewerRouter.routeFor(node.path)
        if (route != null) {
            viewer = route
        } else {
            openWith(node)
        }
    }

    private fun openWith(node: FileNode) {
        openWithFile(File(node.path), node.name)
    }

    /** Buka berkas hasil dekripsi vault dengan penampil internal atau aplikasi eksternal. */
    private fun openDecryptedFile(file: File) {
        val route = ViewerRouter.routeFor(file.path)
        if (route != null) {
            viewer = route
        } else {
            openWithFile(file, file.name)
        }
    }

    private fun openWithFile(
        file: File,
        displayName: String,
    ) {
        if (!file.isFile) return
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val mime =
            MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(displayName.substringAfterLast('.', "").lowercase(Locale.ROOT))
                ?: "application/octet-stream"
        val intent =
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(intent) }
    }

    /**
     * Kompres berkas terpilih menjadi arsip ZIP di folder yang sedang dibuka.
     * [pendingZipPassword] null = arsip polos (ZipEngine); non-null = arsip
     * terenkripsi AES-256 (ZipCrypto) — sandi di-wipe setelah dipakai.
     */
    private fun runZip() {
        val current = browserState.ui.value.current
        val sources = browserState.ui.value.selection.map { File(it) }.filter { it.exists() }
        if (sources.isEmpty()) return
        val archiveName =
            if (current == browserState.ui.value.root) {
                getString(R.string.app_default_archive_name)
            } else {
                current.name
            }
        val target = File(current, "$archiveName.zip")
        val encrypted = pendingZipPassword != null
        val password = pendingZipPassword
        pendingZipPassword = null
        mainScope.launch {
            val result =
                if (encrypted) {
                    zipCrypto.zipFilesEncrypted(sources, target, password ?: CharArray(0))
                } else {
                    zipEngine.zipFiles(sources, target)
                }
            password?.fill('\u0000')
            browserState.clearSelection()
            browserState.refresh()
            val message =
                result.fold(
                    onSuccess = {
                        getString(
                            if (encrypted) R.string.app_zip_encrypted_created else R.string.app_zip_created,
                            it.name,
                        )
                    },
                    onFailure = { getString(R.string.app_zip_failed) },
                )
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Dialog opsi pembuatan ZIP: tanpa sandi (polos) atau dgn sandi AES-256.
     * Tombol terenkripsi hanya aktif bila sandi tidak kosong (fail-fast).
     */
    @Composable
    private fun ZipPasswordDialog(
        onDismiss: () -> Unit,
        onPlainZip: () -> Unit,
        onEncryptedZip: (CharArray) -> Unit,
    ) {
        var password by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(text = stringResource(R.string.app_zip_dialog_title)) },
            text = {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    label = { Text(text = stringResource(R.string.app_zip_password_hint)) },
                    visualTransformation = PasswordVisualTransformation(),
                )
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(text = stringResource(R.string.app_action_cancel))
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = onPlainZip) {
                        Text(text = stringResource(R.string.app_zip_action_plain))
                    }
                    TextButton(
                        enabled = password.isNotBlank(),
                        onClick = { onEncryptedZip(password.toCharArray()) },
                    ) {
                        Text(text = stringResource(R.string.app_zip_action_encrypted))
                    }
                }
            },
        )
    }

    /**
     * Ekstraksi arsip ZIP [archive] ke folder se-nama (tanpa ekstensi) di folder yang sama.
     * [password] null/kosong = arsip polos via ZipEngine; non-null = terenkripsi via ZipCrypto
     * (AES-256, sandi di-wipe setelah dipakai). Gagal dikenal dipetakan ke pesan spesifik.
     */
    private fun runExtract(
        archive: File,
        password: CharArray?,
    ) {
        val target = File(archive.parentFile, archive.nameWithoutExtension)
        if (target.exists() && target.listFiles()?.isNotEmpty() == true) {
            Toast
                .makeText(
                    this@MainActivity,
                    getString(R.string.app_extract_target_exists, target.name),
                    Toast.LENGTH_SHORT,
                ).show()
            return
        }
        val encrypted = password != null && password.isNotEmpty()
        mainScope.launch {
            val result =
                if (encrypted) {
                    zipCrypto.unzip(archive, target, password ?: CharArray(0))
                } else {
                    zipEngine.unzip(archive, target)
                }
            password?.fill('\u0000')
            browserState.refresh()
            val message =
                result.fold(
                    onSuccess = { count -> getString(R.string.app_extract_success, count, target.name) },
                    onFailure = { error ->
                        if (error is SecurityException) {
                            getString(R.string.app_extract_wrong_password)
                        } else {
                            getString(R.string.app_extract_failed)
                        }
                    },
                )
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Dialog ekstraksi arsip ZIP: ekstrak (opsi sandi utk arsip terenkripsi),
     * buka dengan aplikasi lain, atau batal.
     */
    @Composable
    private fun ZipExtractDialog(
        archiveName: String,
        onDismiss: () -> Unit,
        onOpenExternal: () -> Unit,
        onExtract: (CharArray?) -> Unit,
    ) {
        var password by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(text = stringResource(R.string.app_extract_dialog_title, archiveName)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        singleLine = true,
                        label = { Text(text = stringResource(R.string.app_extract_password_hint)) },
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(text = stringResource(R.string.app_action_cancel))
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = onOpenExternal) {
                        Text(text = stringResource(R.string.app_extract_open))
                    }
                    TextButton(
                        enabled = password.isBlank(),
                        onClick = { onExtract(null) },
                    ) {
                        Text(text = stringResource(R.string.app_extract_action_plain))
                    }
                    TextButton(
                        enabled = password.isNotBlank(),
                        onClick = { onExtract(password.toCharArray()) },
                    ) {
                        Text(text = stringResource(R.string.app_extract_action_encrypted))
                    }
                }
            },
        )
    }

    companion object {
        private const val REQUEST_CODE_STORAGE = 1001
    }
}
