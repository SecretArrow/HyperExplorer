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

package com.hyperexplorer.core.common.favorites

/** Wadah imutabel daftar path folder favorit (urutan penyematan dipertahankan). */
data class FavoriteFolders(val items: List<String>)

/** Batas kapasitas default daftar favorit (dipakai [FavoriteFolders.add] dan [FavoriteFoldersCodec.decode]). */
const val DEFAULT_MAX_FAVORITES = 9

/** Tambah [path]; blank/kosong/duplikat (setelah normalisasi) = no-op; bila items.size >= [max] (atau max <= 0) = no-op. */
fun FavoriteFolders.add(
    path: String,
    max: Int = DEFAULT_MAX_FAVORITES,
): FavoriteFolders {
    // Cabang no-op aman (tanpa exception): normalisasi invalid; max <= 0; sudah ada (string persis);
    // penuh (items.size >= max). Urutan item existing TIDAK pernah berubah; hasil selalu instance baru.
    val normalized = normalizeFavoritePath(path) ?: return this
    if (max <= 0) return this
    if (items.contains(normalized)) return this
    if (items.size >= max) return this
    return FavoriteFolders(items + normalized)
}

/** Hapus [path] (pencocokan string persis); tidak ada = no-op. */
fun FavoriteFolders.remove(path: String): FavoriteFolders {
    if (!items.contains(path)) return this
    // Pencocokan string PERSIS (tanpa normalisasi); filterNot menjaga urutan item yang tersisa.
    return FavoriteFolders(items.filterNot { it == path })
}

/** Pencocokan string persis terhadap items. */
fun FavoriteFolders.isFavorite(path: String): Boolean = items.contains(path)

/**
 * Codec teks daftar favorit: satu path per baris, dipisah '\n'. Murni stdlib JVM (tanpa I/O):
 * pemanggil yang menyimpan/membaca hasilnya ke penyimpanan.
 */
object FavoriteFoldersCodec {
    private const val MAX_PATH_CHARS = 4096

    /** Encode: baris-per-path (join "\n"); skip blank, path mengandung '\n'/'\r', dan path lebih panjang dari 4096 char. */
    fun encode(paths: List<String>): String {
        val out = StringBuilder()
        for (element in paths) {
            val blank = element.isBlank()
            val multiline = element.contains('\n') || element.contains('\r')
            val tooLong = element.length > MAX_PATH_CHARS
            if (blank || multiline || tooLong) continue
            if (out.isNotEmpty()) out.append('\n')
            out.append(element)
        }
        return out.toString()
    }

    /** Decode defensif: TIDAK PERNAH melempar/me-return null; raw null/kosong/garbage -> list aman. */
    fun decode(
        raw: String?,
        max: Int = DEFAULT_MAX_FAVORITES,
    ): List<String> {
        if (raw == null) return emptyList()
        if (raw.isEmpty()) return emptyList()
        // max <= 0 -> tidak ada item diterima (defensif, tanpa exception).
        if (max <= 0) return emptyList()
        val result = ArrayList<String>()
        for (line in raw.split('\n')) {
            val normalized = normalizeFavoritePath(line) ?: continue
            // Dedupe stable: pertahankan kemunculan PERTAMA; berhenti saat kapasitas max tercapai.
            if (result.contains(normalized)) continue
            result.add(normalized)
            if (result.size >= max) break
        }
        return result
    }
}

/**
 * Normalisasi path favorit (SATU helper privat, dipakai [FavoriteFolders.add] dan
 * [FavoriteFoldersCodec.decode]): trim whitespace; buang trailing '/' berulang KECUALI
 * path root "/"; lalu bila hasil blank -> invalid (null).
 *
 * Contoh: "  /sdcard/Download/// " -> "/sdcard/Download"; "/" tetap "/"; "///" -> invalid (blank).
 */
private fun normalizeFavoritePath(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed == "/") return trimmed
    val stripped = trimmed.trimEnd('/')
    if (stripped.isEmpty()) return null
    return stripped
}
