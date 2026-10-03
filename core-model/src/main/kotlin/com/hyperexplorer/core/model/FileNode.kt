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

package com.hyperexplorer.core.model

import java.io.File
import java.util.Locale

enum class FileCategory {
    FOLDER,
    IMAGE,
    VIDEO,
    AUDIO,
    DOCUMENT,
    ARCHIVE,
    APK,
    OTHER,
}

/**
 * Representasi netral dari sebuah berkas/folder.
 * Semua modul bekerja dengan model ini, bukan java.io.File langsung,
 * agar sumber berkas bisa lokal, jaringan, atau cloud.
 */
data class FileNode(
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
) {
    val name: String
        get() = path.substringAfterLast('/')

    val category: FileCategory
        get() = if (isDirectory) FileCategory.FOLDER else categoryOf(name)

    fun humanSize(): String = humanSize(size)

    companion object {
        fun from(file: File): FileNode =
            FileNode(
                path = file.absolutePath,
                isDirectory = file.isDirectory,
                size = if (file.isDirectory) 0L else file.length(),
                lastModified = file.lastModified(),
            )

        fun categoryOf(fileName: String): FileCategory {
            val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return when (ext) {
                "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic" -> FileCategory.IMAGE
                "mp4", "mkv", "avi", "mov", "webm", "3gp" -> FileCategory.VIDEO
                "mp3", "wav", "ogg", "flac", "m4a", "aac" -> FileCategory.AUDIO
                "zip", "rar", "7z", "tar", "gz" -> FileCategory.ARCHIVE
                "apk", "apks", "xapk" -> FileCategory.APK
                "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "csv", "json", "xml", "html" -> FileCategory.DOCUMENT
                else -> FileCategory.OTHER
            }
        }

        fun humanSize(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            var value = bytes.toDouble()
            val units = listOf("KB", "MB", "GB", "TB")
            var index = -1
            while (value >= 1024 && index < units.size - 1) {
                value /= 1024
                index++
            }
            return String.format(Locale.ROOT, "%.1f %s", value, units[index])
        }
    }
}
