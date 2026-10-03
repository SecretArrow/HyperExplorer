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

/**
 * Pilihan tema aplikasi. Nilai tersimpan sebagai ordinal di SharedPreferences.
 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    ;

    /** Label yang tampil di layar pengaturan (bahasa Indonesia). */
    fun label(): String =
        when (this) {
            SYSTEM -> "Ikuti sistem"
            LIGHT -> "Terang"
            DARK -> "Gelap"
        }

    companion object {
        /** Aman terhadap ordinal lama/tak valid: selalu kembali ke SYSTEM. */
        fun fromOrdinal(value: Int): ThemeMode = entries.getOrElse(value) { SYSTEM }
    }
}
