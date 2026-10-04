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

class LanguageModeTest {
    @Test
    fun `from ordinal returns matching mode`() {
        assertEquals(LanguageMode.SYSTEM, LanguageMode.fromOrdinal(0))
        assertEquals(LanguageMode.ENGLISH, LanguageMode.fromOrdinal(1))
        assertEquals(LanguageMode.INDONESIAN, LanguageMode.fromOrdinal(2))
    }

    @Test
    fun `from ordinal falls back to system for invalid values`() {
        assertEquals(LanguageMode.SYSTEM, LanguageMode.fromOrdinal(-1))
        assertEquals(LanguageMode.SYSTEM, LanguageMode.fromOrdinal(99))
        assertEquals(LanguageMode.SYSTEM, LanguageMode.fromOrdinal(Int.MIN_VALUE))
        assertEquals(LanguageMode.SYSTEM, LanguageMode.fromOrdinal(Int.MAX_VALUE))
    }

    @Test
    fun `default stored ordinal is system`() {
        assertEquals(LanguageMode.SYSTEM, LanguageMode.fromOrdinal(LanguageMode.SYSTEM.ordinal))
    }

    @Test
    fun `label resources are distinct and valid`() {
        val labels = LanguageMode.entries.map { it.labelRes() }
        assertTrue(labels.all { it != 0 })
        assertEquals(labels.size, labels.toSet().size)
        assertNotEquals(LanguageMode.SYSTEM.labelRes(), LanguageMode.ENGLISH.labelRes())
        assertNotEquals(LanguageMode.ENGLISH.labelRes(), LanguageMode.INDONESIAN.labelRes())
    }
}
