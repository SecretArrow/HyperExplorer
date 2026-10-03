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
 * Setiap varian punya layar viewer masing-masing.
 */
sealed interface ViewerRoute {
    data class Image(val path: String) : ViewerRoute

    data class Text(val path: String) : ViewerRoute
}

/**
 * Memetakan jalur berkas ke [ViewerRoute] berdasarkan ekstensi (tidak peka huruf besar/kecil).
 * Mengembalikan null bila nama tanpa titik atau ekstensi tidak dikenal.
 */
object ViewerRouter {
    private val TEXT_EXTENSIONS =
        setOf(
            "txt", "md", "log", "json", "xml", "csv", "html", "htm", "css", "js",
            "kt", "java", "py", "sh", "yaml", "yml", "ini", "cfg", "conf", "properties",
        )

    fun routeFor(path: String): ViewerRoute? {
        val extension = path.substringAfterLast('.', "")
        if (extension.isEmpty()) return null
        return when (FileNode.categoryOf(path)) {
            FileCategory.IMAGE -> ViewerRoute.Image(path)
            else ->
                if (extension.lowercase(Locale.ROOT) in TEXT_EXTENSIONS) {
                    ViewerRoute.Text(path)
                } else {
                    null
                }
        }
    }
}
