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
 * Alasan sebuah nama berkas ditolak sebagai nama entri vault.
 */
enum class VaultNameError {
    /** Nama kosong atau hanya berisi spasi. */
    BLANK,

    /** Nama memuat '/', '\\', atau karakter NUL. */
    ILLEGAL_CHAR,

    /** Nama adalah "." atau ".." (terlarang karena merujuk direktori). */
    RESERVED,

    /** Nama melebihi [VaultNames.MAX_NAME_BYTES] byte saat dienkode UTF-8. */
    TOO_LONG,
}

/**
 * Validasi nama berkas di dalam vault. Murni (tanpa I/O) agar mudah diuji dan
 * dipakai ulang oleh impor maupun rename.
 */
object VaultNames {
    /** Batas panjang nama dalam byte UTF-8, mengikuti batas nama berkas POSIX. */
    const val MAX_NAME_BYTES = 255

    /**
     * Memvalidasi [name] sebagai nama berkas vault.
     *
     * @return null bila nama valid; jika tidak, alasan penolakan sesuai urutan
     * pemeriksaan: blank → karakter terlarang → nama tercadang → terlalu panjang.
     */
    fun validate(name: String): VaultNameError? {
        if (name.isBlank()) return VaultNameError.BLANK
        if (name.any { it == '/' || it == '\\' || it == '\u0000' }) return VaultNameError.ILLEGAL_CHAR
        if (name == "." || name == "..") return VaultNameError.RESERVED
        if (name.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES) return VaultNameError.TOO_LONG
        return null
    }
}
