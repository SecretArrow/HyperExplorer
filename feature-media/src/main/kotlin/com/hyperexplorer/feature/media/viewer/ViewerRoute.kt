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

package com.hyperexplorer.feature.media.viewer

import com.hyperexplorer.core.model.FileCategory
import com.hyperexplorer.core.model.FileNode
import java.util.Locale

/**
 * Tujuan pembukaan sebuah berkas di fitur media.
 * Setiap varian punya layar masing-masing:
 * [Image] -> ImageViewerScreen, [Text] -> TextEditorScreen,
 * [Video] -> MediaPlayerScreen (isVideo = true), [Audio] -> MediaPlayerScreen (isVideo = false).
 */
sealed interface ViewerRoute {
    data class Image(
        val path: String,
    ) : ViewerRoute

    data class Text(
        val path: String,
    ) : ViewerRoute

    data class Video(
        val path: String,
    ) : ViewerRoute

    data class Audio(
        val path: String,
    ) : ViewerRoute
}

/**
 * Memetakan jalur berkas ke [ViewerRoute] berdasarkan kategori [FileNode.categoryOf]
 * (ekstensi, tidak peka huruf besar/kecil) dengan fallback ekstensi teks yang dikenal.
 *
 * Perilaku tepi:
 * - Path blank (kosong/spasi saja) -> null.
 * - "file." (ekstensi kosong setelah titik terakhir) -> null.
 * - ".profile" (dotfile; ekstensi "profile" tidak dikenal) -> null.
 * - "a.b/c.mp4" -> Video: titik pada nama folder tidak mengganggu karena ekstensi
 *   diambil dari titik terakhir pada seluruh path ("mp4").
 * - "MOVIE.MP4" -> Video (ekstensi huruf besar dinormalisasi ke lowercase).
 * - Ekstensi tidak dikenal (mis. "dokumen.xyz") -> null.
 */
object ViewerRouter {
    private val TEXT_EXTENSIONS =
        setOf(
            "txt",
            "md",
            "log",
            "json",
            "xml",
            "csv",
            "html",
            "htm",
            "css",
            "js",
            "kt",
            "java",
            "py",
            "sh",
            "yaml",
            "yml",
            "ini",
            "cfg",
            "conf",
            "properties",
        )

    /**
     * Memetakan [path] ke [ViewerRoute]; null bila path blank, ekstensi kosong,
     * atau ekstensi tidak dikenal (bukan media maupun teks yang didukung).
     * Perilaku lama Image/Text tidak berubah.
     */
    fun routeFor(path: String): ViewerRoute? {
        if (path.isBlank()) return null
        return when (FileNode.categoryOf(path)) {
            FileCategory.IMAGE -> ViewerRoute.Image(path)
            FileCategory.VIDEO -> ViewerRoute.Video(path)
            FileCategory.AUDIO -> ViewerRoute.Audio(path)
            FileCategory.FOLDER -> textRouteOrNull(path)
            FileCategory.DOCUMENT -> textRouteOrNull(path)
            FileCategory.ARCHIVE -> textRouteOrNull(path)
            FileCategory.APK -> textRouteOrNull(path)
            FileCategory.OTHER -> textRouteOrNull(path)
        }
    }

    /**
     * Fallback kategori non-media: [ViewerRoute.Text] bila ekstensi (lowercase)
     * terdaftar di [TEXT_EXTENSIONS], selain itu null.
     * Cabang [FileCategory.FOLDER] praktis selalu null karena categoryOf memetakan
     * berdasarkan ekstensi (folder tanpa ekstensi teks dikenal), tetapi tetap
     * ditangani agar `when` ekshaustif terhadap [FileCategory].
     */
    private fun textRouteOrNull(path: String): ViewerRoute? {
        val extension = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return if (extension in TEXT_EXTENSIONS) ViewerRoute.Text(path) else null
    }
}
