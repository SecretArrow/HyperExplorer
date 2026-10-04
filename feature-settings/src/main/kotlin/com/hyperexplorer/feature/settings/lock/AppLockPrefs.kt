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

package com.hyperexplorer.feature.settings.lock

import android.content.Context

/**
 * Penyimpan status kunci aplikasi (app lock) berbasis SharedPreferences.
 *
 * Berbagi berkas preferensi yang sama dengan ThemePrefs/LanguagePrefs ("hyper_settings") agar
 * seluruh preferensi Hyper Explorer terkumpul di satu tempat (pola ThemePrefs).
 *
 * Desain fail-safe: baca/tulis dibungkus runCatching dan TIDAK PERNAH melempar — bila penyimpanan
 * gagal, [read] kembali ke false (kunci nonaktif, aplikasi tetap terbuka) dan [write] diam menelan
 * galat. Alasannya: galat persistensi tidak boleh mengunci pengguna keluar dari aplikasinya sendiri.
 */
object AppLockPrefs {
    /** Baca status kunci aplikasi; default false (nonaktif) bila belum pernah diset atau gagal baca. */
    fun read(context: Context): Boolean =
        runCatching {
            context
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_APP_LOCK_ENABLED, false)
        }.getOrDefault(false)

    /** Simpan status kunci aplikasi secara asinkron (apply); galat ditelan agar tidak pernah crash. */
    fun write(
        context: Context,
        enabled: Boolean,
    ) {
        runCatching {
            context
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_APP_LOCK_ENABLED, enabled)
                .apply()
        }
    }

    private companion object {
        const val PREFS_NAME = "hyper_settings"
        const val KEY_APP_LOCK_ENABLED = "app_lock_enabled"
    }
}
