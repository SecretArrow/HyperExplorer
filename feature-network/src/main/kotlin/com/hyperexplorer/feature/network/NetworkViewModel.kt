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

import android.content.Context
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hyperexplorer.data.remote.RemoteConnection
import com.hyperexplorer.data.remote.RemoteCredentials
import com.hyperexplorer.data.remote.RemoteEntry
import com.hyperexplorer.data.remote.RemoteFileSystem
import com.hyperexplorer.data.remote.RemoteFileSystems
import com.hyperexplorer.data.remote.RemoteProtocol
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * State holder layar lokasi jaringan; class biasa tanpa DI (pola state holder modul
 * browser: CoroutineScope(SupervisorJob() + Dispatchers.IO) sendiri), tetapi state
 * dipaparkan lewat Compose mutableStateOf agar langsung teramati UI.
 *
 * Semua operasi I/O berjalan di [ioDispatcher]. Kredensial tidak pernah ditulis ke
 * log; pesan galat hanya berisi message exception untuk ditampilkan di banner.
 *
 * Kontrak path sama dengan :data-remote — tanpa skema, segmen pertama SMB = share.
 */
class NetworkViewModel(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val store = ConnectionStore(context)
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private var client: RemoteFileSystem? = null

    /** Pembangkit urutan operasi list; operasi tua dibuang bila urutan berubah. */
    private var generation = 0L

    var connectionsLoading by mutableStateOf(true)
        private set

    var connections by mutableStateOf<List<RemoteConnection>>(emptyList())
        private set

    var screen by mutableStateOf(NetworkScreen.CONNECTIONS)
        private set

    var currentConnection by mutableStateOf<RemoteConnection?>(null)
        private set

    var currentPath by mutableStateOf(ROOT_PATH)
        private set

    var entries by mutableStateOf<List<RemoteEntry>>(emptyList())
        private set

    var loading by mutableStateOf(false)
        private set

    var error by mutableStateOf<NetworkError?>(null)
        private set

    /** Pesan exception pendamping [error] untuk banner (tanpa kredensial). */
    var errorDetail by mutableStateOf<String?>(null)
        private set

    var formVisible by mutableStateOf(false)
        private set

    var formState by mutableStateOf(NetworkFormState())
        private set

    var formErrors by mutableStateOf<List<NetworkFormValidator.ValidationError>>(emptyList())
        private set

    var testStatus by mutableStateOf(ConnectionTestStatus.IDLE)
        private set

    var testDetail by mutableStateOf<String?>(null)
        private set

    /** Path target unduhan terakhir (untuk snackbar); null setelah dikonsumsi UI. */
    var downloadTarget by mutableStateOf<String?>(null)
        private set

    init {
        refreshConnections()
    }

    /** Ada folder induk yang dapat dituju dari [currentPath]? */
    val canNavigateUp: Boolean
        get() = parentPath(currentPath) != null

    /** Muat ulang daftar sambungan tersimpan dari penyimpanan terenkripsi. */
    fun refreshConnections() {
        scope.launch {
            connections = store.read()
            connectionsLoading = false
        }
    }

    /** Buka mode browsing pada root lokasi [connection]. */
    fun openConnection(connection: RemoteConnection) {
        browse(connection, ROOT_PATH)
    }

    /** Turun ke folder hasil list [entry] bila berupa direktori. */
    fun openDirectory(entry: RemoteEntry) {
        if (!entry.isDirectory) return
        currentConnection?.let { browse(it, entry.path) }
    }

    /** Buka dialog tambah sambungan baru. */
    fun openAddDialog() {
        formState = NetworkFormState()
        resetFormAux()
        formVisible = true
    }

    /** Buka dialog ubah dengan isi [connection]. */
    fun openEditDialog(connection: RemoteConnection) {
        formState =
            NetworkFormState(
                id = connection.id,
                protocol = connection.protocol,
                host = connection.host,
                port = connection.port.toString(),
                basePath = if (connection.protocol == RemoteProtocol.SMB) connection.share else connection.basePath,
                username = connection.credentials.username,
                password = connection.credentials.password,
            )
        resetFormAux()
        formVisible = true
    }

    /** Ubah isi form lewat [transform]. */
    fun updateForm(transform: (NetworkFormState) -> NetworkFormState) {
        formState = transform(formState)
    }

    /** Ganti protokol pada form; port diisi ulang dengan port default protokol. */
    fun updateProtocol(protocol: RemoteProtocol) {
        formState = formState.copy(protocol = protocol, port = NetworkFormState.defaultPortText(protocol))
    }

    /** Tutup dialog form tanpa menyimpan. */
    fun dismissForm() {
        formVisible = false
    }

    /** Uji koneksi sesuai isi form (connect + list path dasar); hasil di [testStatus]. */
    fun testConnection() {
        if (!validateForm()) return
        runTest(buildConnection(formState), saveOnSuccess = false)
    }

    /**
     * Validasi form lalu uji koneksi (connect + list path dasar/share); hanya bila
     * uji sukses sambungan disimpan ke penyimpanan terenkripsi dan dialog ditutup.
     */
    fun saveConnection() {
        if (!validateForm()) return
        runTest(buildConnection(formState), saveOnSuccess = true)
    }

    /** Hapus sambungan tersimpan dengan [id]. */
    fun deleteConnection(id: Long) {
        scope.launch {
            store.delete(id)
            connections = store.read()
        }
    }

    /**
     * Buka [path] (tanpa skema; SMB: segmen pertama = share) pada [connection]:
     * klien lama ditutup, klien baru di-connect lalu isi folder di-list.
     */
    fun browse(
        connection: RemoteConnection,
        path: String,
    ) {
        closeCurrent()
        generation++
        screen = NetworkScreen.BROWSING
        currentConnection = connection
        currentPath = path
        error = null
        errorDetail = null
        entries = emptyList()
        connectAndList()
    }

    /** Naik ke folder induk; tidak berlaku di root. */
    fun navigateUp() {
        val parent = parentPath(currentPath) ?: return
        currentConnection?.let { browse(it, parent) }
    }

    /** Segarkan isi folder saat ini (connect ulang lalu list). */
    fun refresh() {
        val connection = currentConnection ?: return
        browse(connection, currentPath)
    }

    /** Buat folder bernama [name] di folder saat ini. */
    fun mkdir(name: String) {
        val fs = client ?: return
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        scope.launch {
            try {
                fs.makeDirectory(currentPath, trimmed)
            } catch (t: Throwable) {
                error = NetworkError.MKDIR_FAILED
                errorDetail = t.message
            }
            listCurrent()
        }
    }

    /** Hapus [entry] (berkas atau folder) di lokasi jarak jauh. */
    fun deleteEntry(entry: RemoteEntry) {
        val fs = client ?: return
        scope.launch {
            try {
                fs.delete(entry.path, entry.isDirectory)
            } catch (t: Throwable) {
                error = NetworkError.DELETE_FAILED
                errorDetail = t.message
            }
            listCurrent()
        }
    }

    /** Ganti nama [entry] menjadi [newName] di lokasi jarak jauh. */
    fun renameEntry(
        entry: RemoteEntry,
        newName: String,
    ) {
        val fs = client ?: return
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed == entry.name) return
        scope.launch {
            try {
                fs.rename(entry.path, entry.name, trimmed)
            } catch (t: Throwable) {
                error = NetworkError.RENAME_FAILED
                errorDetail = t.message
            }
            listCurrent()
        }
    }

    /**
     * Unduh [entry] ke Downloads/HyperExplorer/<nama>.
     * Butuh All-Files-Access pada Android 11+ (sudah diminta aplikasi) agar dapat
     * menulis di penyimpanan publik; folder tujuan dibuat bila belum ada.
     */
    fun download(entry: RemoteEntry) {
        val fs = client ?: return
        downloadTarget = null
        scope.launch {
            try {
                val dir =
                    File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        DOWNLOAD_DIR,
                    )
                if (!dir.exists()) dir.mkdirs()
                val target = File(dir, entry.name)
                fs.download(entry.path, target, entry.size)
                downloadTarget = target.absolutePath
            } catch (t: Throwable) {
                error = NetworkError.DOWNLOAD_FAILED
                errorDetail = t.message
            }
        }
    }

    /** Tandai pesan unduhan sudah ditampilkan agar snackbar dapat muncul lagi. */
    fun consumeDownloadResult() {
        downloadTarget = null
    }

    /** Tutup klien jaringan aktif; dipanggil UI saat keluar mode browsing atau layar dibuang. */
    fun closeCurrent() {
        client?.let { current -> runCatching { current.close() } }
        client = null
    }

    /** Kembali ke daftar sambungan tersimpan; klien ditutup. */
    fun closeBrowsing() {
        closeCurrent()
        generation++
        currentConnection = null
        currentPath = ROOT_PATH
        entries = emptyList()
        loading = false
        error = null
        errorDetail = null
        screen = NetworkScreen.CONNECTIONS
    }

    private fun resetFormAux() {
        formErrors = emptyList()
        testStatus = ConnectionTestStatus.IDLE
        testDetail = null
    }

    private fun validateForm(): Boolean {
        val errors = NetworkFormValidator.validate(formState.host, formState.port)
        formErrors = errors
        return errors.isEmpty()
    }

    /** Bangun RemoteConnection dari form; username kosong berarti anonim. */
    private fun buildConnection(form: NetworkFormState): RemoteConnection =
        RemoteConnection(
            id = form.id,
            protocol = form.protocol,
            host = form.host.trim(),
            port = form.port.trim().toIntOrNull() ?: form.protocol.defaultPort,
            share = if (form.protocol == RemoteProtocol.SMB) form.basePath.trim() else "",
            basePath = if (form.protocol == RemoteProtocol.SMB) "" else form.basePath.trim(),
            credentials =
                if (form.username.isBlank()) {
                    RemoteCredentials()
                } else {
                    RemoteCredentials(username = form.username.trim(), password = form.password, anonymous = false)
                },
            displayName = "",
        )

    /** Jalankan uji koneksi (connect + list path dasar/share) lalu tutup klien uji. */
    private fun runTest(
        connection: RemoteConnection,
        saveOnSuccess: Boolean,
    ) {
        testStatus = ConnectionTestStatus.RUNNING
        testDetail = null
        scope.launch {
            var probe: RemoteFileSystem? = null
            val result =
                try {
                    probe = RemoteFileSystems.connect(connection)
                    probe.list(probePath(connection))
                    ConnectionTestStatus.SUCCESS
                } catch (t: Throwable) {
                    testDetail = t.message
                    ConnectionTestStatus.FAILURE
                } finally {
                    runCatching { probe?.close() }
                }
            testStatus = result
            if (saveOnSuccess && result == ConnectionTestStatus.SUCCESS) {
                store.upsert(connection)
                connections = store.read()
                connectionsLoading = false
                formVisible = false
            }
        }
    }

    private fun connectAndList() {
        val connection = currentConnection ?: return
        val gen = generation
        loading = true
        scope.launch {
            val fs =
                try {
                    RemoteFileSystems.connect(connection)
                } catch (t: Throwable) {
                    if (gen == generation) {
                        client = null
                        loading = false
                        error = NetworkError.CONNECT_FAILED
                        errorDetail = t.message
                    }
                    return@launch
                }
            if (gen != generation) {
                // Pengguna sudah pindah lokasi sejak operasi ini dimulai — buang klien tua.
                runCatching { fs.close() }
                return@launch
            }
            client = fs
            listCurrentInternal(fs, gen)
        }
    }

    private suspend fun listCurrentInternal(
        fs: RemoteFileSystem,
        gen: Long,
    ) {
        val result =
            try {
                fs.list(currentPath)
            } catch (t: Throwable) {
                if (gen == generation) {
                    error = NetworkError.LIST_FAILED
                    errorDetail = t.message
                }
                null
            }
        if (gen != generation) return
        if (result != null) {
            entries = result
        }
        loading = false
    }

    private fun listCurrent() {
        val fs = client ?: return
        val gen = generation
        loading = true
        scope.launch { listCurrentInternal(fs, gen) }
    }

    /** Path dasar untuk uji koneksi: share untuk SMB, basePath untuk lainnya. */
    private fun probePath(connection: RemoteConnection): String {
        val base = if (connection.protocol == RemoteProtocol.SMB) connection.share else connection.basePath
        return base.ifBlank { ROOT_PATH }
    }

    /** Path induk dari [path] ("" dianggap root); null bila sudah di root. */
    private fun parentPath(path: String): String? {
        val trimmed = path.trimEnd('/')
        if (trimmed.isEmpty()) return null
        val cut = trimmed.lastIndexOf('/')
        return if (cut <= 0) ROOT_PATH else trimmed.substring(0, cut)
    }

    private companion object {
        const val ROOT_PATH = "/"
        const val DOWNLOAD_DIR = "HyperExplorer"
    }
}
