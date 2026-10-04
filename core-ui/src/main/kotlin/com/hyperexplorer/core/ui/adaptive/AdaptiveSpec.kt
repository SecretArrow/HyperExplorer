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

package com.hyperexplorer.core.ui.adaptive

/** Klasifikasi lebar layar (dp) mengikuti panduan Material. */
enum class WindowWidth { COMPACT, MEDIUM, EXPANDED }

/**
 * Klasifikasikan lebar [widthDp]: <600 COMPACT, 600..839 MEDIUM, >=840 EXPANDED.
 *
 * Defensif: [widthDp] <= 0 (screenWidthDp bisa 0/UNDEFINED di TV atau pada awal
 * komposisi) -> COMPACT. Asumsi eksplisit: lebar dp yang sah selalu > 0; fungsi ini
 * TOTAL — setiap Int menghasilkan tepat satu klasifikasi tanpa pernah melempar galat,
 * sehingga aman dipanggil dari UI sebelum ukuran jendela terukur.
 */
fun windowWidthFor(widthDp: Int): WindowWidth =
    when {
        widthDp <= 0 -> WindowWidth.COMPACT // defensif: 0/UNDEFINED (TV, awal komposisi) atau negatif
        widthDp < 600 -> WindowWidth.COMPACT
        widthDp < 840 -> WindowWidth.MEDIUM
        else -> WindowWidth.EXPANDED
    }

/**
 * Spesifikasi layout daftar berkas untuk lebar jendela tertentu.
 *
 * @property isGrid true bila daftar berkas harus dirender sebagai grid multi-kolom.
 * @property gridColumns jumlah kolom grid; bermakna bila [isGrid], dan selalu 1 bila
 *   list. Invarian defensif: hasil [browserLayoutFor] tidak pernah mengembalikan
 *   nilai < 1.
 */
data class BrowserLayoutSpec(
    val isGrid: Boolean,
    val gridColumns: Int, // bermakna bila isGrid; selalu 1 bila list
)

/**
 * Petakan [width] ke [BrowserLayoutSpec]:
 * COMPACT -> BrowserLayoutSpec(isGrid=false, gridColumns=1); MEDIUM -> (true, 2);
 * EXPANDED -> (true, 3).
 *
 * `when` di sini ekshaustif atas enum TANPA else dengan sengaja: bila nanti ada
 * konstanta [WindowWidth] baru tanpa pemetaan, kompilasi gagal (lebih aman daripada
 * cabang else yang membungkam kasus baru secara diam-diam). Karena konstanta enum
 * tertutup, tidak ada jalur yang tidak terdefinisi — semua cabang dites di
 * AdaptiveSpecTest.
 */
fun browserLayoutFor(width: WindowWidth): BrowserLayoutSpec =
    when (width) {
        WindowWidth.COMPACT -> BrowserLayoutSpec(isGrid = false, gridColumns = 1)
        WindowWidth.MEDIUM -> BrowserLayoutSpec(isGrid = true, gridColumns = 2)
        WindowWidth.EXPANDED -> BrowserLayoutSpec(isGrid = true, gridColumns = 3)
    }
