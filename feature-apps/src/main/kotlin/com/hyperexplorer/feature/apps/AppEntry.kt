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

package com.hyperexplorer.feature.apps

/**
 * Satu baris pada daftar aplikasi terpasang.
 * Semua data diambil dari PackageManager tanpa izin khusus.
 */
data class AppEntry(
    val label: String,
    val packageName: String,
    val versionName: String,
    val apkPath: String,
    val sizeBytes: Long,
    val installedAtMillis: Long,
    val isSystem: Boolean,
)
