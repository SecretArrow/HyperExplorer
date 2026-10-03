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

package com.hyperexplorer.feature.media.text

import java.io.File
import java.io.IOException

/**
 * I/O berkas teks sederhana untuk editor: baca UTF-8 dengan batas ukuran
 * dan tulis secara atomik (tulis ke berkas .tmp lalu rename).
 */
object TextFileIO {
    /** Batas maksimum byte yang dibaca agar editor tidak kehabisan memori. */
    const val MAX_READ_BYTES = 1_000_000L

    data class TextContent(
        val text: String,
        val truncated: Boolean,
    )

    /**
     * Membaca [file] sebagai UTF-8. Bila berkas lebih besar dari [MAX_READ_BYTES],
     * hanya bagian awal dibaca dan [TextContent.truncated] bernilai true.
     * Berkas kosong mengembalikan teks kosong tanpa truncation;
     * kegagalan I/O dilempar sebagai [IOException] untuk ditangani pemanggil.
     */
    fun read(file: File): TextContent {
        if (!file.isFile) throw IOException("Berkas tidak dapat dibaca: ${file.path}")
        if (file.length() <= MAX_READ_BYTES) {
            return TextContent(text = file.readText(Charsets.UTF_8), truncated = false)
        }
        val limit = MAX_READ_BYTES.toInt()
        val bytes = ByteArray(limit)
        var offset = 0
        file.inputStream().use { input ->
            while (offset < limit) {
                val read = input.read(bytes, offset, limit - offset)
                if (read < 0) break
                offset += read
            }
        }
        return TextContent(text = String(bytes, 0, offset, Charsets.UTF_8), truncated = true)
    }

    /**
     * Menulis [content] ke [file] secara atomik: tulis ke berkas sementara
     * lalu rename; bila rename gagal, salin menimpa target.
     * Kegagalan I/O dikembalikan sebagai [Result.failure].
     */
    fun write(file: File, content: String): Result<Unit> =
        try {
            file.parentFile?.mkdirs()
            val tempFile = File(file.parentFile, "${file.name}.tmp")
            tempFile.writeText(content, Charsets.UTF_8)
            if (!tempFile.renameTo(file)) {
                tempFile.copyTo(file, overwrite = true)
                tempFile.delete()
            }
            Result.success(Unit)
        } catch (error: IOException) {
            Result.failure(error)
        }
}
