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

package com.hyperexplorer.feature.settings.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test JVM murni untuk pemetaan ketersediaan app lock.
 * Nilai konstanta adalah salinan dari androidx.biometric.BiometricManager 1.1.0
 * (lihat KDoc AppLockHelper); tidak ada dependensi Android di test ini.
 */
class AppLockHelperTest {
    @Test
    fun `map availability maps biometric success to available`() {
        // androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
        assertEquals(AppLockAvailability.AVAILABLE, AppLockHelper.mapAvailability(0))
    }

    @Test
    fun `map availability maps no hardware to no hardware`() {
        // androidx.biometric.BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE
        assertEquals(AppLockAvailability.NO_HARDWARE, AppLockHelper.mapAvailability(12))
    }

    @Test
    fun `map availability maps none enrolled to none enrolled`() {
        // androidx.biometric.BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
        assertEquals(AppLockAvailability.NONE_ENROLLED, AppLockHelper.mapAvailability(11))
    }

    @Test
    fun `map availability maps hw unavailable to unknown`() {
        // androidx.biometric.BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE → fail-closed UNKNOWN
        assertEquals(AppLockAvailability.UNKNOWN, AppLockHelper.mapAvailability(1))
    }

    @Test
    fun `map availability maps security update required to unknown`() {
        // androidx.biometric.BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED
        assertEquals(AppLockAvailability.UNKNOWN, AppLockHelper.mapAvailability(15))
    }

    @Test
    fun `map availability maps unknown codes to unknown`() {
        // Kode tak dikenal (mis. vendor/custom) dan input gila tetap fail-closed ke UNKNOWN.
        assertEquals(AppLockAvailability.UNKNOWN, AppLockHelper.mapAvailability(999))
        assertEquals(AppLockAvailability.UNKNOWN, AppLockHelper.mapAvailability(-1))
        assertEquals(AppLockAvailability.UNKNOWN, AppLockHelper.mapAvailability(Int.MIN_VALUE))
        assertEquals(AppLockAvailability.UNKNOWN, AppLockHelper.mapAvailability(Int.MAX_VALUE))
    }

    @Test
    fun `can lock is true only for available`() {
        assertTrue(AppLockHelper.canLock(AppLockAvailability.AVAILABLE))
        assertFalse(AppLockHelper.canLock(AppLockAvailability.NO_HARDWARE))
        assertFalse(AppLockHelper.canLock(AppLockAvailability.NONE_ENROLLED))
        assertFalse(AppLockHelper.canLock(AppLockAvailability.UNKNOWN))
    }
}
