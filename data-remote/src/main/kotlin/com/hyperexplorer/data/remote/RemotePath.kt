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

package com.hyperexplorer.data.remote

/**
 * Util path remote: normalisasi dan anti-traversal.
 *
 * Koordinat path remote: absolut di dalam remote, TANPA skema, tanpa '/' di
 * depan/belakang (root = ""), pemisah selalu '/'. Hasil [normalize] tidak pernah
 * mengandung ".." sehingga tidak bisa keluar dari root.
 */
object RemotePath {
    /**
     * Normalisasi [path]: buang whitespace, '/' di depan & belakang, segmen kosong
     * ("//" → "/"), segmen "."; segmen ".." menaik satu tingkat dan DIJEPIT di root
     * (tidak pernah keluar root). Root → "".
     */
    fun normalize(path: String): String {
        val trimmed = path.trim().trim('/')
        if (trimmed.isEmpty()) return ""
        val stack = mutableListOf<String>()
        for (segment in trimmed.split('/')) {
            if (segment.isEmpty() || segment == ".") continue
            if (segment == "..") {
                if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                continue
            }
            stack.add(segment)
        }
        return stack.joinToString("/")
    }

    /**
     * Gabungkan [parent] dan [child], lalu normalisasi hasil gabungannya — bukan
     * masing-masing bagian — sehingga traversal ".." dari [child] dijepit di root
     * remote dan tidak pernah lolos keluar parent+root.
     */
    fun join(
        parent: String,
        child: String,
    ): String {
        val p = parent.trim()
        val c = child.trim()
        return when {
            p.isEmpty() -> normalize(c)
            c.isEmpty() -> normalize(p)
            else -> normalize("$p/$c")
        }
    }

    /**
     * Validasi ketat path masukan dari pemanggil: tolak traversal ".." (segmen mana pun),
     * backslash, dan byte NUL dengan [IllegalArgumentException]. Path "" (root) sah.
     */
    fun requireSafe(path: String) {
        val trimmed = path.trim()
        if (trimmed.contains('\\')) {
            throw IllegalArgumentException("Backslash is not allowed in remote path: $path")
        }
        if (trimmed.contains('\u0000')) {
            throw IllegalArgumentException("NUL byte is not allowed in remote path: $path")
        }
        val hasTraversal = trimmed.split('/').any { it == ".." }
        if (hasTraversal) {
            throw IllegalArgumentException("Path traversal is not allowed: $path")
        }
    }
}
