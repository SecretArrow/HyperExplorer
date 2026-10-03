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

package com.hyperexplorer.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeModeTest {
    @Test
    fun `from ordinal returns matching mode`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromOrdinal(0))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromOrdinal(1))
        assertEquals(ThemeMode.DARK, ThemeMode.fromOrdinal(2))
    }

    @Test
    fun `from ordinal falls back to system for invalid values`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromOrdinal(-1))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromOrdinal(99))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromOrdinal(Int.MIN_VALUE))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromOrdinal(Int.MAX_VALUE))
    }

    @Test
    fun `default stored ordinal is system`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromOrdinal(ThemeMode.SYSTEM.ordinal))
    }

    @Test
    fun `labels are non empty and distinct`() {
        val labels = ThemeMode.entries.map { it.label() }
        assertTrue(labels.all { it.isNotBlank() })
        assertEquals(labels.size, labels.toSet().size)
        assertNotEquals(ThemeMode.SYSTEM.label(), ThemeMode.LIGHT.label())
        assertNotEquals(ThemeMode.LIGHT.label(), ThemeMode.DARK.label())
    }
}
