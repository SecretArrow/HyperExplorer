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

import com.hyperexplorer.feature.tools.vault.VaultEntry

/**
 * Kategori galat versi UI, dipetakan secara ekshaustif dari
 * [com.hyperexplorer.feature.tools.vault.VaultError].
 */
enum class VaultErrorKind { INVALID_INPUT, SOURCE_MISSING, ENTRY_NOT_FOUND, CORRUPT, INDEX_CORRUPT, IO, KEY }

/**
 * Galat siap-tampil: [kind] menentukan string sumber daya, [detail] berisi pesan asli
 * dari engine (dijamin bebas materi rahasia oleh kontrak engine).
 */
data class VaultUiError(
    val kind: VaultErrorKind,
    val detail: String,
)

/**
 * Operasi vault yang dapat berjalan satu per satu; dipakai sebagai penanda `busy`
 * untuk mencegah re-entrancy.
 */
enum class VaultBusyOp { IMPORT, EXPORT, DELETE, VERIFY, RENAME, OPEN }

/**
 * Keberhasilan operasi vault; menentukan string toast `vault_done_*`.
 */
enum class VaultSuccess { IMPORTED, EXPORTED, DELETED, RENAMED, VERIFIED, OPENED }

/**
 * Pesan sekali-pakai untuk toast. Tepat satu dari [success] atau [error] bernilai
 * non-null; [detailArg] adalah argumen `%1$s` untuk pesan sukses bila ada.
 */
data class VaultUiMessage(
    val success: VaultSuccess?,
    val error: VaultUiError?,
    val detailArg: String?,
)

/**
 * Keadaan vault ketika indeks berhasil dimuat: daftar entri terurut waktu-tambah
 * menurun, plus penanda operasi yang sedang berjalan ([busy], null bila idle).
 */
data class VaultReadyState(
    val entries: List<VaultEntry>,
    val busy: VaultBusyOp? = null,
)

/**
 * Seluruh kemungkinan keadaan UI vault; `when` di layar wajib ekshaustif atas
 * sealed interface ini.
 */
sealed interface VaultUiState {
    /** Indeks belum dimuat (pertama kali atau gagal sebelum siap). */
    data object Loading : VaultUiState

    /** Indeks termuat; daftar entri siap ditampilkan. */
    data class Ready(
        val state: VaultReadyState,
    ) : VaultUiState

    /** Pemuatan indeks gagal; pengguna dapat mencoba lagi. */
    data class Failed(
        val error: VaultUiError,
    ) : VaultUiState
}
