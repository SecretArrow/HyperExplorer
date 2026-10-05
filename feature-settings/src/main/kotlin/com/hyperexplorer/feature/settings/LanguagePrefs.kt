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

package com.hyperexplorer.feature.settings

import android.content.Context

/**
 * Penyimpan preferensi bahasa berbasis SharedPreferences.
 * Tanpa dependensi eksternal, tanpa jaringan: data hanya ada di perangkat.
 */
class LanguagePrefs(
    context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Baca bahasa tersimpan; default [LanguageMode.SYSTEM] bila belum pernah diset. */
    fun read(): LanguageMode = LanguageMode.fromOrdinal(prefs.getInt(KEY_LANGUAGE_MODE, LanguageMode.SYSTEM.ordinal))

    /** Simpan bahasa pilihan pengguna secara asinkron (apply). */
    fun write(mode: LanguageMode) {
        prefs.edit().putInt(KEY_LANGUAGE_MODE, mode.ordinal).apply()
    }

    private companion object {
        const val PREFS_NAME = "hyper_settings"
        const val KEY_LANGUAGE_MODE = "language_mode"
    }
}
