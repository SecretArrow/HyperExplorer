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

package com.hyperexplorer.feature.tools.vault.ui

import com.hyperexplorer.core.common.ConflictStrategy
import com.hyperexplorer.feature.tools.vault.VaultEngine
import com.hyperexplorer.feature.tools.vault.VaultError
import com.hyperexplorer.feature.tools.vault.VaultKeyProvider
import com.hyperexplorer.feature.tools.vault.VaultNames
import com.hyperexplorer.feature.tools.vault.VaultResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Detail pesan galat untuk jalur sumber impor yang kosong. */
private const val BLANK_PATH_DETAIL = "jalur sumber kosong"

/** Detail fallback untuk pengecualian yang tidak membawa pesan. */
private const val NO_MESSAGE_DETAIL = "pengecualian tanpa pesan"

/**
 * Pengendali UI vault: menjembatani [VaultEngine] (seluruh operasi I/O) ke state
 * Compose yang aman-dipakai layar.
 *
 * Kontrak anti-reentrancy: hanya satu operasi vault ([VaultBusyOp]) yang boleh
 * berjalan pada satu waktu. Pemeriksaan `Ready` + penandaan `busy` dilakukan
 * SECARA SINKRON di thread pemanggil (layar selalu memanggil dari thread utama),
 * sehingga op kedua yang dipanggil sebelum scheduler berjalan pun sudah tertolak.
 * [refresh] dikecualikan dari guard karena sifatnya hanya membaca ulang indeks;
 * bila dipanggil saat ada operasi berjalan, penanda `busy` dipertahankan agar
 * guard tetap berlaku.
 *
 * Setiap pemanggilan engine dibungkus [runEngine]: pengecualian tak terduga TIDAK
 * ditelan, melainkan dikonversi menjadi galat IO ber-detail informatif agar selalu
 * sampai ke UI.
 *
 * @param vaultDir direktori penyimpanan vault (diteruskan ke [VaultEngine]).
 * @param keyProvider penyedia kunci pembungkus DEK (diteruskan ke [VaultEngine]).
 * @param scope scope coroutine pemilik layar; callback UI diluncurkan di sini.
 * @param ioDispatcher dispatcher untuk seluruh pemanggilan engine.
 */
class VaultController(
    vaultDir: File,
    keyProvider: VaultKeyProvider,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val engine = VaultEngine(vaultDir, keyProvider)

    private val _state = MutableStateFlow<VaultUiState>(VaultUiState.Loading)
    val state: StateFlow<VaultUiState> = _state.asStateFlow()

    private val _messages = MutableStateFlow<VaultUiMessage?>(null)
    val messages: StateFlow<VaultUiMessage?> = _messages.asStateFlow()

    /**
     * Menandai pesan toast saat ini sebagai sudah ditampilkan; [messages] kembali null.
     */
    fun consumeMessage() {
        _messages.value = null
    }

    /**
     * Memuat ulang daftar entri dari engine. Selalu boleh dijalankan (tidak peduli
     * state saat ini). Hasil Ok menjadi [VaultUiState.Ready] terurut waktu-tambah
     * menurun; hasil Err atau kegagalan tak terduga menjadi [VaultUiState.Failed].
     */
    fun refresh() {
        scope.launch(ioDispatcher) {
            val result = runEngine(OP_LOAD_LIST) { engine.listEntries() }
            when (result) {
                is VaultResult.Ok -> {
                    val busy = (_state.value as? VaultUiState.Ready)?.state?.busy
                    val sorted = result.value.sortedByDescending { it.addedAtEpochMs }
                    _state.value = VaultUiState.Ready(VaultReadyState(entries = sorted, busy = busy))
                }
                is VaultResult.Err -> _state.value = VaultUiState.Failed(result.error.toUiError())
            }
        }
    }

    /**
     * Mengimpor berkas dari [rawPath] (di-trim). Pre-validasi dilakukan SEBELUM guard
     * state dan tanpa menyentuh engine: jalur kosong langsung mengirim pesan
     * INVALID_INPUT dan membiarkan state apa adanya. [deleteSource] diteruskan ke
     * engine untuk menghapus berkas asli setelah terenkripsi.
     */
    fun importFrom(
        rawPath: String,
        deleteSource: Boolean,
    ) {
        val trimmedPath = rawPath.trim()
        if (trimmedPath.isEmpty()) {
            _messages.value =
                VaultUiMessage(
                    success = null,
                    error = VaultUiError(kind = VaultErrorKind.INVALID_INPUT, detail = BLANK_PATH_DETAIL),
                    detailArg = null,
                )
            return
        }
        if (!beginOp(VaultBusyOp.IMPORT)) return
        scope.launch(ioDispatcher) {
            try {
                val result = runEngine(OP_IMPORT) { engine.importFile(File(trimmedPath), deleteSource) }
                when (result) {
                    is VaultResult.Ok -> {
                        emitSuccess(VaultSuccess.IMPORTED, result.value.entry.originalName)
                        refresh()
                    }
                    is VaultResult.Err -> emitError(result.error)
                }
            } finally {
                endOp(VaultBusyOp.IMPORT)
            }
        }
    }

    /**
     * Mengekspor entri [id] ke [destDir] dengan strategi konflik RENAME; pesan
     * sukses berisi path berkas hasil ekspor.
     */
    fun exportTo(
        id: String,
        destDir: File,
    ) {
        if (!beginOp(VaultBusyOp.EXPORT)) return
        scope.launch(ioDispatcher) {
            try {
                val result = runEngine(OP_EXPORT) { engine.exportEntry(id, destDir, ConflictStrategy.RENAME) }
                when (result) {
                    is VaultResult.Ok -> emitSuccess(VaultSuccess.EXPORTED, result.value.absolutePath)
                    is VaultResult.Err -> emitError(result.error)
                }
            } finally {
                endOp(VaultBusyOp.EXPORT)
            }
        }
    }

    /**
     * Menghapus permanen entri [id] lalu memuat ulang daftar; pesan sukses
     * membawa [id] sebagai argumen.
     */
    fun deleteEntry(id: String) {
        if (!beginOp(VaultBusyOp.DELETE)) return
        scope.launch(ioDispatcher) {
            try {
                val result = runEngine(OP_DELETE) { engine.deleteEntry(id) }
                when (result) {
                    is VaultResult.Ok -> {
                        emitSuccess(VaultSuccess.DELETED, id)
                        refresh()
                    }
                    is VaultResult.Err -> emitError(result.error)
                }
            } finally {
                endOp(VaultBusyOp.DELETE)
            }
        }
    }

    /**
     * Memverifikasi integritas entri [id]; tidak mengubah daftar sehingga cukup
     * mengirim pesan sukses/galat.
     */
    fun verifyEntry(id: String) {
        if (!beginOp(VaultBusyOp.VERIFY)) return
        scope.launch(ioDispatcher) {
            try {
                val result = runEngine(OP_VERIFY) { engine.verifyEntry(id) }
                when (result) {
                    is VaultResult.Ok -> emitSuccess(VaultSuccess.VERIFIED, null)
                    is VaultResult.Err -> emitError(result.error)
                }
            } finally {
                endOp(VaultBusyOp.VERIFY)
            }
        }
    }

    /**
     * Mengganti nama tampilan entri [id] menjadi [newName]. Pre-validasi
     * [VaultNames.validate] dijalankan sebelum guard state dan tanpa menyentuh
     * engine: nama tidak valid mengirim pesan INVALID_INPUT ber-detail jenis galat.
     */
    fun renameEntry(
        id: String,
        newName: String,
    ) {
        val nameError = VaultNames.validate(newName)
        if (nameError != null) {
            _messages.value =
                VaultUiMessage(
                    success = null,
                    error = VaultUiError(kind = VaultErrorKind.INVALID_INPUT, detail = nameError.name),
                    detailArg = null,
                )
            return
        }
        if (!beginOp(VaultBusyOp.RENAME)) return
        scope.launch(ioDispatcher) {
            try {
                val result = runEngine(OP_RENAME) { engine.renameEntry(id, newName) }
                when (result) {
                    is VaultResult.Ok -> {
                        emitSuccess(VaultSuccess.RENAMED, result.value.originalName)
                        refresh()
                    }
                    is VaultResult.Err -> emitError(result.error)
                }
            } finally {
                endOp(VaultBusyOp.RENAME)
            }
        }
    }

    /**
     * Menyiapkan salinan terdekripsi entri [id] di dalam [cacheDir], mengirim pesan
     * OPENED, lalu mengeksekusi [onReady] dengan berkas hasil di Dispatchers.Main
     * (bukan di thread I/O) agar pemanggil boleh menyentuh API UI.
     */
    fun openEntry(
        id: String,
        cacheDir: File,
        onReady: (File) -> Unit,
    ) {
        if (!beginOp(VaultBusyOp.OPEN)) return
        scope.launch(ioDispatcher) {
            try {
                val result = runEngine(OP_OPEN) { engine.openDecrypted(id, cacheDir) }
                when (result) {
                    is VaultResult.Ok -> {
                        emitSuccess(VaultSuccess.OPENED, null)
                        val decrypted = result.value
                        scope.launch(Dispatchers.Main) {
                            try {
                                onReady(decrypted)
                            } catch (t: Throwable) {
                                _messages.value = unexpectedFailure(OP_OPEN, t)
                            }
                        }
                    }
                    is VaultResult.Err -> emitError(result.error)
                }
            } finally {
                endOp(VaultBusyOp.OPEN)
            }
        }
    }

    /**
     * Cek-dan-set sinkron: hanya operasi baru yang diizinkan bila state saat ini
     * Ready DAN sedang tidak sibuk. Mengembalikan true bila op boleh berjalan dan
     * penanda `busy` sudah dipasang.
     */
    private fun beginOp(op: VaultBusyOp): Boolean {
        val current = _state.value
        if (current !is VaultUiState.Ready) return false
        if (current.state.busy != null) return false
        _state.value = current.copy(state = current.state.copy(busy = op))
        return true
    }

    /**
     * Melepas penanda `busy` hanya bila masih cocok dengan [op] pemiliknya,
     * sehingga akhir operasi tidak pernah menimpa penanda operasi lain.
     */
    private fun endOp(op: VaultBusyOp) {
        val current = _state.value
        if (current is VaultUiState.Ready && current.state.busy == op) {
            _state.value = current.copy(state = current.state.copy(busy = null))
        }
    }

    private fun emitSuccess(
        success: VaultSuccess,
        detailArg: String?,
    ) {
        _messages.value = VaultUiMessage(success = success, error = null, detailArg = detailArg)
    }

    private fun emitError(error: VaultError) {
        _messages.value = VaultUiMessage(success = null, error = error.toUiError(), detailArg = null)
    }

    /**
     * Membungkus pemanggilan engine: hasil Err diteruskan apa adanya, pengecualian
     * tak terduga dikonversi menjadi galat IO ber-detail informatif (bukan ditelan).
     */
    private fun <T> runEngine(
        operation: String,
        block: () -> VaultResult<T>,
    ): VaultResult<T> =
        try {
            block()
        } catch (t: Throwable) {
            VaultResult.Err(VaultError.IoFailure(operation = operation, cause = unexpectedDetail(operation, t)))
        }

    /** Detail informatif untuk kegagalan tak terduga, tanpa materi rahasia. */
    private fun unexpectedDetail(
        operation: String,
        t: Throwable,
    ): String = "operasi $operation gagal tak terduga: ${t.message ?: NO_MESSAGE_DETAIL}"

    private fun unexpectedFailure(
        operation: String,
        t: Throwable,
    ): VaultUiMessage =
        VaultUiMessage(
            success = null,
            error = VaultUiError(kind = VaultErrorKind.IO, detail = unexpectedDetail(operation, t)),
            detailArg = null,
        )

    /**
     * Pemetaan ekshaustif [VaultError] -> [VaultUiError]. Detail memakai pesan asli
     * engine; untuk IoFailure field `cause` sudah memuat kalimat informatif dari
     * engine atau dari [runEngine].
     */
    private fun VaultError.toUiError(): VaultUiError =
        when (this) {
            is VaultError.InvalidInput -> VaultUiError(kind = VaultErrorKind.INVALID_INPUT, detail = reason)
            is VaultError.SourceMissing -> VaultUiError(kind = VaultErrorKind.SOURCE_MISSING, detail = path)
            is VaultError.EntryNotFound -> VaultUiError(kind = VaultErrorKind.ENTRY_NOT_FOUND, detail = id)
            is VaultError.CorruptEntry -> VaultUiError(kind = VaultErrorKind.CORRUPT, detail = detail)
            is VaultError.IndexCorrupt -> VaultUiError(kind = VaultErrorKind.INDEX_CORRUPT, detail = detail)
            is VaultError.IoFailure -> VaultUiError(kind = VaultErrorKind.IO, detail = cause)
            is VaultError.KeyUnavailable -> VaultUiError(kind = VaultErrorKind.KEY, detail = detail)
        }

    private companion object {
        const val OP_LOAD_LIST = "muat daftar"
        const val OP_IMPORT = "impor"
        const val OP_EXPORT = "ekspor"
        const val OP_DELETE = "hapus"
        const val OP_VERIFY = "verifikasi"
        const val OP_RENAME = "ganti nama"
        const val OP_OPEN = "buka"
    }
}
