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

package com.hyperexplorer.feature.tools.vault

/**
 * Satu berkas yang tersimpan di dalam vault terenkripsi.
 *
 * @property id id unik entri (32 karakter heksadesimal, dihasilkan engine dari UUID).
 * @property storedFileName nama berkas blob di dalam direktori vault, selalu "<id>.hve".
 * @property originalName nama asli berkas saat diimpor; dipakai sebagai nama hasil ekspor.
 * @property originalPath path sumber lengkap saat diimpor, untuk keperluan jejak audit saja.
 * @property mimeType tipe MIME berkas; string kosong bila tidak diketahui (v1 selalu kosong).
 * @property sizeBytes ukuran plaintext dalam byte; dipakai sebagai pemeriksa silang integritas.
 * @property addedAtEpochMs waktu impor dalam milidetik sejak epoch.
 * @property wrapMethod cara KEK membungkus DEK pada berkas blob.
 */
data class VaultEntry(
    val id: String,
    val storedFileName: String,
    val originalName: String,
    val originalPath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val addedAtEpochMs: Long,
    val wrapMethod: WrapMethod,
)

/**
 * Metode pembungkusan DEK (Data Encryption Key) di dalam blob.
 * Konversi ke id on-disk didefinisikan oleh [VaultFormat.WRAP_METHOD_KEYSTORE].
 */
enum class WrapMethod {
    /** DEK dibungkus kunci master (KEK) yang disimpan di Android Keystore. */
    KEYSTORE,
}

/**
 * Seluruh kondisi gagal yang dapat terjadi pada operasi vault.
 * Sealed agar pemanggil dipaksa compiler menangani setiap kasus secara ekshaustif.
 */
sealed interface VaultError {
    /** Masukan dari pemanggil tidak valid (nama ilegal, tujuan bukan direktori, dst.). */
    data class InvalidInput(val reason: String) : VaultError

    /** Berkas sumber tidak ada pada path yang diberikan. */
    data class SourceMissing(val path: String) : VaultError

    /** Id entri tidak dikenal di indeks vault. */
    data class EntryNotFound(val id: String) : VaultError

    /** Entri ada tetapi blob-nya rusak, terpotong, atau tidak dapat didekripsi. */
    data class CorruptEntry(val id: String, val detail: String) : VaultError

    /** Berkas indeks (utama maupun cadangan) tidak dapat di-parse; vault menolak menulis. */
    data class IndexCorrupt(val detail: String) : VaultError

    /** Kegagalan I/O sistem berkas; [operation] menyebut operasi apa yang gagal. */
    data class IoFailure(val operation: String, val cause: String) : VaultError

    /** Kunci kriptografis tidak tersedia atau tidak dapat dipakai (mis. Keystore rusak). */
    data class KeyUnavailable(val detail: String) : VaultError
}

/**
 * Hasil operasi vault yang tidak pernah melempar exception ke pemanggil.
 * Sealed agar pemanggil dipaksa compiler menangani [Ok] dan [Err] secara ekshaustif.
 */
sealed interface VaultResult<out T> {
    /** Operasi berhasil dengan nilai [value]. */
    data class Ok<T>(val value: T) : VaultResult<T>

    /** Operasi gagal dengan [error] yang informatif (tanpa materi kunci). */
    data class Err(val error: VaultError) : VaultResult<Nothing>
}

/**
 * Hasil impor: entri yang baru dibuat plus apakah berkas sumber ikut terhapus.
 * [sourceDeleted] bernilai false bila pemanggil meminta penghapusan tetapi sistem
 * berkas menolak — ini BUKAN kegagalan impor.
 */
data class ImportOutcome(val entry: VaultEntry, val sourceDeleted: Boolean)
