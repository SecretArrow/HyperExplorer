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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppManagerTest {
    private fun app(
        label: String,
        packageName: String,
        isSystem: Boolean,
    ): AppEntry =
        AppEntry(
            label = label,
            packageName = packageName,
            versionName = "1.0",
            apkPath = "/data/app/$packageName/base.apk",
            sizeBytes = 1024L,
            installedAtMillis = 0L,
            isSystem = isSystem,
        )

    @Test
    fun `sort apps orders labels case insensitive and puts system apps last`() {
        val apps =
            listOf(
                app("Zebra", "com.zebra", isSystem = true),
                app("browser", "com.browser", isSystem = false),
                app("Alpha", "com.alpha", isSystem = false),
                app("Calculator", "com.calculator", isSystem = true),
                app("media", "com.media", isSystem = false),
            )

        val sorted = AppManager.sortApps(apps)

        assertEquals(listOf("Alpha", "browser", "media", "Calculator", "Zebra"), sorted.map { it.label })
        assertTrue(sorted.take(3).none { it.isSystem })
        assertTrue(sorted.takeLast(2).all { it.isSystem })
    }

    @Test
    fun `sort apps keeps empty input intact`() {
        assertTrue(AppManager.sortApps(emptyList()).isEmpty())
    }

    @Test
    fun `sanitize apk file name replaces illegal characters`() {
        // spasi -> "_", "!" -> "_", huruf besar/kecil dan tanda hubung dipertahankan
        assertEquals("Wi-Fi_Analyzer_-1.2.3.apk", AppManager.sanitizeApkFileName("Wi-Fi Analyzer!", "1.2.3"))
    }

    @Test
    fun `sanitize apk file name without version`() {
        assertEquals("Label.apk", AppManager.sanitizeApkFileName("Label", ""))
    }

    @Test
    fun `sanitize apk file name has no trailing or leading dot`() {
        assertEquals("hidden-1.0.apk", AppManager.sanitizeApkFileName(".hidden", "1.0"))
        assertEquals("Kotatsu.apk", AppManager.sanitizeApkFileName("Kotatsu.", ""))
        assertFalse(AppManager.sanitizeApkFileName("Aplikasi Cadangan", "2.0 ").endsWith(". "))
    }

    @Test
    fun `sanitize apk file name sanitizes version too`() {
        assertEquals("App-1_0.apk", AppManager.sanitizeApkFileName("App", "1 0"))
    }
}
