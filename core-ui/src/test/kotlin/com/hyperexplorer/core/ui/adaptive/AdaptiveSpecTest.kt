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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uji [windowWidthFor] dan [browserLayoutFor] murni JVM: klasifikasi lebar (batas
 * 600/840 inklusif, defensif untuk <= 0) dan pemetaan layout (COMPACT list 1 kolom,
 * MEDIUM grid 2 kolom, EXPANDED grid 3 kolom) serta invarian defensif gridColumns >= 1.
 */
class AdaptiveSpecTest {
    // ------------------------------------------------------- windowWidthFor

    @Test
    fun `lebar 0 defensif menghasilkan COMPACT`() {
        val result = windowWidthFor(0)

        assertEquals(
            "screenWidthDp 0 (belum terukur: TV/awal komposisi) harus COMPACT",
            WindowWidth.COMPACT,
            result,
        )
    }

    @Test
    fun `lebar negatif defensif menghasilkan COMPACT`() {
        val result = windowWidthFor(-5)

        assertEquals("lebar negatif tidak sah harus COMPACT, aktual: $result", WindowWidth.COMPACT, result)
    }

    @Test
    fun `lebar 1 dp COMPACT`() {
        val result = windowWidthFor(1)

        assertEquals("lebar terkecil yang sah (1 dp) harus COMPACT, aktual: $result", WindowWidth.COMPACT, result)
    }

    @Test
    fun `lebar 599 COMPACT tepat di bawah batas medium`() {
        val result = windowWidthFor(599)

        assertEquals("599 < 600 harus COMPACT, aktual: $result", WindowWidth.COMPACT, result)
    }

    @Test
    fun `lebar 600 MEDIUM batas bawah inklusif`() {
        val result = windowWidthFor(600)

        assertEquals("600 harus MEDIUM (batas inklusif), aktual: $result", WindowWidth.MEDIUM, result)
    }

    @Test
    fun `lebar 839 MEDIUM tepat di bawah batas expanded`() {
        val result = windowWidthFor(839)

        assertEquals("839 < 840 harus MEDIUM, aktual: $result", WindowWidth.MEDIUM, result)
    }

    @Test
    fun `lebar 840 EXPANDED batas bawah inklusif`() {
        val result = windowWidthFor(840)

        assertEquals("840 harus EXPANDED (batas inklusif), aktual: $result", WindowWidth.EXPANDED, result)
    }

    @Test
    fun `lebar 1200 EXPANDED`() {
        val result = windowWidthFor(1200)

        assertEquals("1200 jelas expanded harus EXPANDED, aktual: $result", WindowWidth.EXPANDED, result)
    }

    @Test
    fun `lebar Int MAX VALUE EXPANDED tanpa overflow`() {
        val result = windowWidthFor(Int.MAX_VALUE)

        assertEquals("Int.MAX_VALUE harus EXPANDED tanpa overflow, aktual: $result", WindowWidth.EXPANDED, result)
    }

    // ------------------------------------------------------- browserLayoutFor

    @Test
    fun `layout COMPACT adalah list 1 kolom`() {
        val layout = browserLayoutFor(WindowWidth.COMPACT)

        assertFalse("COMPACT harus list (bukan grid), aktual: $layout", layout.isGrid)
        assertEquals("COMPACT harus 1 kolom, aktual: ${layout.gridColumns}", 1, layout.gridColumns)
    }

    @Test
    fun `layout MEDIUM adalah grid 2 kolom`() {
        val layout = browserLayoutFor(WindowWidth.MEDIUM)

        assertTrue("MEDIUM harus grid, aktual: $layout", layout.isGrid)
        assertEquals("MEDIUM harus 2 kolom, aktual: ${layout.gridColumns}", 2, layout.gridColumns)
    }

    @Test
    fun `layout EXPANDED adalah grid 3 kolom`() {
        val layout = browserLayoutFor(WindowWidth.EXPANDED)

        assertTrue("EXPANDED harus grid, aktual: $layout", layout.isGrid)
        assertEquals("EXPANDED harus 3 kolom, aktual: ${layout.gridColumns}", 3, layout.gridColumns)
    }

    @Test
    fun `seluruh WindowWidth entries tercover mapping tanpa cabang mati`() {
        val visited = mutableSetOf<WindowWidth>()

        for (width in WindowWidth.entries) {
            val layout = browserLayoutFor(width)
            when (width) {
                WindowWidth.COMPACT -> {
                    assertFalse("COMPACT harus list, aktual: $layout", layout.isGrid)
                    assertEquals("COMPACT harus 1 kolom, aktual: $layout", 1, layout.gridColumns)
                    visited += width
                }
                WindowWidth.MEDIUM -> {
                    assertTrue("MEDIUM harus grid, aktual: $layout", layout.isGrid)
                    assertEquals("MEDIUM harus 2 kolom, aktual: $layout", 2, layout.gridColumns)
                    visited += width
                }
                WindowWidth.EXPANDED -> {
                    assertTrue("EXPANDED harus grid, aktual: $layout", layout.isGrid)
                    assertEquals("EXPANDED harus 3 kolom, aktual: $layout", 3, layout.gridColumns)
                    visited += width
                }
            }
        }

        assertEquals(
            "setiap konstanta WindowWidth wajib punya cabang uji sendiri (tidak ada cabang mati)",
            WindowWidth.entries.toSet(),
            visited,
        )
    }

    @Test
    fun `invarian gridColumns selalu minimal 1 untuk semua WindowWidth`() {
        for (width in WindowWidth.entries) {
            val layout = browserLayoutFor(width)

            assertTrue("gridColumns >= 1 untuk $width, aktual: ${layout.gridColumns}", layout.gridColumns >= 1)
            if (!layout.isGrid) {
                assertEquals("layout list wajib tepat 1 kolom untuk $width", 1, layout.gridColumns)
            }
        }
    }
}
