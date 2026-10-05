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

package com.hyperexplorer.feature.sync.ui

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hyperexplorer.data.remote.RemoteConnection
import com.hyperexplorer.data.remote.RemotePath
import com.hyperexplorer.feature.network.ConnectionStore
import com.hyperexplorer.feature.sync.R
import com.hyperexplorer.feature.sync.engine.SyncDirection
import com.hyperexplorer.feature.sync.engine.SyncPair
import com.hyperexplorer.feature.sync.store.SyncPairStore
import com.hyperexplorer.feature.sync.store.SyncStatusStore
import com.hyperexplorer.feature.sync.work.SyncScheduler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Isi dialog tambah pasangan sinkronisasi.
 *
 * @property connectionId id sambungan terpilih dari [SyncState.connections];
 *   0 berarti belum ada sambungan terpilih (dropdown kosong) — Simpan akan ditolak.
 * @property localPath path folder lokal (teks bebas pada v1, bukan pemilih SAF).
 * @property remotePath path folder remote (koordinat remote tanpa skema);
 *   kosong berarti root — dinormalisasi via RemotePath.normalize saat disimpan.
 * @property direction arah sinkronisasi (SegmentedButton dua pilihan).
 * @property intervalMinutes interval berulang dalam menit; harus salah satu nilai pada
 *   [SyncState.ALLOWED_INTERVAL_MINUTES].
 */
data class SyncFormState(
    val connectionId: Long = 0L,
    val localPath: String = "",
    val remotePath: String = "",
    val direction: SyncDirection = SyncDirection.PUSH_TO_REMOTE,
    val intervalMinutes: Int = SyncState.INTERVAL_1_HOUR,
)

/**
 * State holder layar sinkronisasi (pola NetworkViewModel modul :feature-network):
 * class biasa tanpa DI dengan CoroutineScope sendiri (SupervisorJob + [ioDispatcher])
 * dan state Compose `mutableStateOf` agar langsung teramati UI.
 *
 * Semua akses penyimpanan ([SyncPairStore]/[SyncStatusStore]/[ConnectionStore]) dan
 * penjadwalan WorkManager ([SyncScheduler]) berjalan di [ioDispatcher]. Kredensial
 * sambungan TIDAK PERNAH masuk log atau pesan galat: galat dicatat hanya sebagai nama
 * kelas exception ([logSafely]) dan seluruh pesan UI berasal dari resource string.
 *
 * Pesan validasi [formError]/[listError] di-resolve dari resource string modul ini lewat
 * `applicationContext.getString` (bukan literal) sehingga tetap dwibahasa; pada perangkat
 * API < 33 locale application-context dapat berbeda dari locale aktivitas — diasumsikan
 * dapat diterima untuk pesan galat pendek (asumsi terdokumentasi di worklog).
 */
class SyncState(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val appContext = context.applicationContext
    private val pairStore = SyncPairStore(appContext)
    private val statusStore = SyncStatusStore(appContext)
    private val connectionStore = ConnectionStore(appContext)
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    /** Daftar pasangan tersimpan; kosong bila belum ada atau penyimpanan gagal dibaca. */
    var pairs by mutableStateOf<List<SyncPair>>(emptyList())
        private set

    /** true selama pembacaan/refresh berjalan (indikator linier di layar). */
    var loading by mutableStateOf(true)
        private set

    /** Sambungan tersimpan (ConnectionStore :feature-network) untuk dropdown dialog. */
    var connections by mutableStateOf<List<RemoteConnection>>(emptyList())
        private set

    /** Dialog tambah pasangan sedang tampil? */
    var dialogVisible by mutableStateOf(false)
        private set

    /** Isi form dialog tambah pasangan. */
    var form by mutableStateOf(SyncFormState())
        private set

    /** Pesan galat validasi/simpan pada dialog (dari resource string); null bila tidak ada. */
    var formError by mutableStateOf<String?>(null)
        private set

    /** Pesan galat operasi daftar (muat/hapus/jalankan sekarang) untuk banner; null bila tidak ada. */
    var listError by mutableStateOf<String?>(null)
        private set

    /** Status terakhir per id pasangan (SyncStatusStore); nilai null berarti belum pernah sinkron. */
    private var statusMessages by mutableStateOf<Map<Long, String?>>(emptyMap())

    init {
        refresh()
    }

    /** Ringkasan hasil sinkron terakhir untuk [pairId]; null berarti belum pernah disinkronkan. */
    fun statusText(pairId: Long): String? = statusMessages[pairId]

    /**
     * Muat ulang seluruh state dari penyimpanan: pasangan, sambungan, dan status terakhir.
     * Dipanggil saat init, setelah simpan/hapus/jalankan-sekarang, dan oleh UI (banner coba lagi).
     */
    fun refresh() {
        scope.launch {
            loading = true
            var loadFailed = false
            val readPairs =
                runCatching { pairStore.read() }
                    .onFailure { t ->
                        logSafely("read pairs", t)
                        loadFailed = true
                    }.getOrDefault(emptyList())
            val readConnections =
                runCatching { connectionStore.read() }
                    .onFailure { t ->
                        logSafely("read connections", t)
                        loadFailed = true
                    }.getOrDefault(emptyList())
            val statuses = mutableMapOf<Long, String?>()
            readPairs.forEach { pair ->
                statuses[pair.id] =
                    runCatching { statusStore.lastResult(pair.id) }
                        .onFailure { t -> logSafely("read status", t) }
                        .getOrNull()
            }
            pairs = readPairs
            connections = readConnections
            statusMessages = statuses
            listError = if (loadFailed) message(R.string.sync_error_load) else null
            loading = false
        }
    }

    /**
     * Buka dialog tambah pasangan dengan form baru; sambungan pertama otomatis terpilih
     * bila daftar sambungan tidak kosong. Dialog tetap dapat dibuka walau sambungan kosong
     * (Simpan kemudian ditolak dengan pesan sambungan wajib — skenario defensif).
     */
    fun openAddDialog() {
        form = SyncFormState(connectionId = connections.firstOrNull()?.id ?: 0L)
        formError = null
        dialogVisible = true
    }

    /** Tutup dialog tanpa menyimpan; isian form dan galat form dibuang. */
    fun dismissDialog() {
        dialogVisible = false
        form = SyncFormState()
        formError = null
    }

    /** Ganti sambungan terpilih pada form. */
    fun updateConnection(id: Long) {
        form = form.copy(connectionId = id)
    }

    /** Ganti path folder lokal pada form (belum divalidasi — divalidasi saat [savePair]). */
    fun updateLocalPath(path: String) {
        form = form.copy(localPath = path)
    }

    /** Ganti path folder remote pada form (kosong = root; dinormalisasi saat [savePair]). */
    fun updateRemotePath(path: String) {
        form = form.copy(remotePath = path)
    }

    /** Ganti arah sinkronisasi pada form. */
    fun updateDirection(direction: SyncDirection) {
        form = form.copy(direction = direction)
    }

    /** Ganti interval berulang (menit) pada form. */
    fun updateInterval(minutes: Int) {
        form = form.copy(intervalMinutes = minutes)
    }

    /**
     * Validasi fail-fast lalu simpan pasangan dan jadwalkan WorkManager periodik.
     *
     * Urutan validasi (pesan PERTAMA yang gagal ditulis ke [formError], dialog tetap terbuka):
     * 1. sambungan harus ada pada [connections] — menolak daftar kosong / belum memilih;
     * 2. path lokal tidak blank — v1 sengaja TIDAK memeriksa exists()+isDirectory() karena
     *    path diketik manual dan boleh jadi folder dibuat belakangan (asumsi terdokumentasi);
     * 3. interval harus salah satu pilihan [ALLOWED_INTERVAL_MINUTES].
     *
     * remotePath dinormalisasi via [RemotePath.normalize] (kosong → "" = root; ".." dijepit).
     * Sukses: upsert → schedule (dengan id baru hasil upsert) → dialog tertutup → [refresh].
     * Galat penyimpanan: dialog tetap terbuka dengan pesan sync_error_save_failed.
     */
    fun savePair() {
        formError = null
        val connection = connections.firstOrNull { it.id == form.connectionId }
        if (connection == null) {
            formError = message(R.string.sync_error_connection_required)
            return
        }
        val localPath = form.localPath.trim()
        if (localPath.isEmpty()) {
            formError = message(R.string.sync_error_local_path_required)
            return
        }
        if (form.intervalMinutes !in ALLOWED_INTERVAL_MINUTES) {
            formError = message(R.string.sync_error_interval_invalid)
            return
        }
        val pair =
            SyncPair(
                localPath = localPath,
                connection = connection,
                remotePath = RemotePath.normalize(form.remotePath),
                direction = form.direction,
                intervalMinutes = form.intervalMinutes,
            )
        scope.launch {
            runCatching {
                val id = pairStore.upsert(pair)
                SyncScheduler.schedule(appContext, pair.copy(id = id))
            }.onSuccess {
                form = SyncFormState()
                dialogVisible = false
                refresh()
            }.onFailure { t ->
                logSafely("save pair", t)
                formError = message(R.string.sync_error_save_failed)
            }
        }
    }

    /**
     * Hapus pasangan [id]: batalkan jadwal WorkManager lebih dulu (anti sync hantu), hapus
     * datanya, lalu [refresh]. Pembersihan status terkait sudah ditangani [SyncScheduler.cancel]
     * (kontrak: cancel = cancelUniqueWork + SyncStatusStore.clear).
     *
     * Worker yang KEBETULAN sedang berjalan boleh menuntaskan siklusnya; hasilnya untuk
     * pasangan yang sudah dihapus diabaikan UI (barisnya tidak ada lagi) dan tidak pernah
     * mengembalikan pasangan yang terhapus. Gagal → banner [listError] (sync_error_delete),
     * daftar tetap di-refresh agar tampilan tidak basi.
     */
    fun deletePair(id: Long) {
        scope.launch {
            runCatching {
                SyncScheduler.cancel(appContext, id)
                pairStore.delete(id)
            }.onFailure { t ->
                logSafely("delete pair", t)
                listError = message(R.string.sync_error_delete)
            }
            refresh()
        }
    }

    /**
     * Jadwalkan sinkron sekali jalan untuk pasangan [id] (WorkManager enqueue, pola
     * [SyncScheduler.runNow]). Tidak melakukan I/O berat di sini — hanya penjadwalan.
     *
     * CATATAN ASINKRON: hasil sinkron ditulis oleh worker lewat SyncStatusStore.record
     * SETELAH pekerjaan selesai, sehingga [statusText] TIDAK berubah seketika oleh metode
     * ini; [refresh] dipanggil untuk memuat state terkini dan status baru akan tampak pada
     * refresh berikutnya setelah worker selesai (tulis worker bersifat asinkron).
     */
    fun syncNow(id: Long) {
        val pair = pairs.firstOrNull { it.id == id } ?: return
        scope.launch {
            runCatching { SyncScheduler.runNow(appContext, pair) }
                .onFailure { t ->
                    logSafely("run sync now", t)
                    listError = message(R.string.sync_error_sync_now)
                }
            refresh()
        }
    }

    /** Pesan galat dari resource string modul (tanpa literal UI, tanpa data dinamis sensitif). */
    private fun message(resId: Int): String = appContext.getString(resId)

    /**
     * Catat galat ke log dengan AMAN: hanya nama operasi + nama kelas exception. Pesan dan
     * stack exception TIDAK dicatat karena dapat memuat data pasangan (SyncPair.toString
     * menyertakan RemoteConnection beserta kredensialnya).
     */
    private fun logSafely(
        operation: String,
        t: Throwable,
    ) {
        Log.w(TAG, "$operation failed: ${t.javaClass.simpleName}")
    }

    companion object {
        /** Pilihan interval dropdown: 15 menit / 1 jam / 6 jam / 1 hari. */
        const val INTERVAL_15_MINUTES = 15
        const val INTERVAL_1_HOUR = 60
        const val INTERVAL_6_HOURS = 360
        const val INTERVAL_1_DAY = 1440

        /** Pilihan interval yang sah; validasi [savePair] menolak nilai di luar himpunan ini. */
        val ALLOWED_INTERVAL_MINUTES = setOf(INTERVAL_15_MINUTES, INTERVAL_1_HOUR, INTERVAL_6_HOURS, INTERVAL_1_DAY)

        private const val TAG = "SyncState"
    }
}
