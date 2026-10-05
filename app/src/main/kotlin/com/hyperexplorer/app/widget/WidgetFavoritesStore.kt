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

package com.hyperexplorer.app.widget

import android.content.Context
import android.util.Log
import com.hyperexplorer.core.common.favorites.FavoriteFolders
import com.hyperexplorer.core.common.favorites.FavoriteFoldersCodec

/**
 * Penyimpanan folder favorit milik widget "Akses cepat" di atas SharedPreferences.
 * Format nilai: hasil [FavoriteFoldersCodec.encode] (satu path per baris).
 *
 * Nama prefs dan kunci HARUS sama dengan yang dibaca [QuickAccessWidget]
 * ("widget_favorites" / "paths") — keduanya sengaja duplikat sebagai konstanta
 * privat per file agar widget dan activity tetap independen; ada KDoc di masing-
 * masing file yang merujuk pasangan ini.
 *
 * Defensif: kegagalan baca/tulis I/O preferensi tidak pernah melempar keluar —
 * baca gagal -> [read] mengembalikan daftar kosong; tulis gagal -> dicatat via
 * Log.w dan state widget menampilkan data lama.
 */
class WidgetFavoritesStore(
    context: Context,
) {
    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Baca daftar favorit; selalu mengembalikan [FavoriteFolders] (mungkin kosong). */
    fun read(): FavoriteFolders =
        try {
            val raw = prefs.getString(PREFS_KEY_PATHS, null)
            FavoriteFolders(FavoriteFoldersCodec.decode(raw))
        } catch (t: Throwable) {
            Log.w(
                TAG,
                "read: gagal membaca favorit dari SharedPreferences '$PREFS_NAME' " +
                    "(kunci '$PREFS_KEY_PATHS'); dikembalikan daftar kosong.",
                t,
            )
            FavoriteFolders(emptyList())
        }

    /** Simpan daftar favorit; kegagalan hanya dicatat (widget menampilkan data lama). */
    fun write(folders: FavoriteFolders) {
        try {
            prefs.edit().putString(PREFS_KEY_PATHS, FavoriteFoldersCodec.encode(folders.items)).apply()
        } catch (t: Throwable) {
            Log.w(
                TAG,
                "write: gagal menyimpan favorit ke SharedPreferences '$PREFS_NAME'; " +
                    "widget tetap menampilkan daftar sebelumnya.",
                t,
            )
        }
    }

    private companion object {
        /** Harus sama dgn PREFS_NAME di QuickAccessWidgetReceiver.kt. */
        const val PREFS_NAME = "widget_favorites"

        /** Harus sama dgn PREFS_KEY_PATHS di QuickAccessWidgetReceiver.kt. */
        const val PREFS_KEY_PATHS = "paths"

        /** Tag Log untuk pesan peringatan defensif milik file ini. */
        const val TAG = "WidgetFavoritesStore"
    }
}
