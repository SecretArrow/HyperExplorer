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

package com.hyperexplorer.core.ui.components

/**
 * Bangun label aksesibilitas baris berkas (TalkBack): "<typeWord>, <name>"
 * dan bila [subtitle] tidak blank ditambah ", <subtitle>".
 * TOTAL: tidak pernah melempar; tidak ada komponen blank yang ikut digabung.
 */
fun buildFileRowLabel(
    isDirectory: Boolean,
    name: String,
    subtitle: String?,
    typeWord: String,
): String {
    val parts = listOf(typeWord, name, subtitle ?: "").filter { it.isNotBlank() }

    return if (parts.isEmpty()) {
        if (isDirectory) "Folder" else "File"
    } else {
        parts.joinToString(separator = ", ")
    }
}
