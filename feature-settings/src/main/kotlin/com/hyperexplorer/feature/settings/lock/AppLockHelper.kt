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

/**
 * Hasil pemeriksaan ketersediaan autentikator (biometrik lemah dan/atau kredensial perangkat)
 * pada perangkat, sebagaimana dilaporkan oleh androidx.biometric.BiometricManager#canAuthenticate.
 */
enum class AppLockAvailability {
    /** Autentikator dapat dipakai: layar kunci boleh menampilkan prompt biometrik/kredensial. */
    AVAILABLE,

    /** Tidak ada perangkat keras biometrik (mis. emulator atau ponsel tanpa sensor). */
    NO_HARDWARE,

    /** Perangkat keras ada tetapi belum ada sidik jari/wajah/PIN yang terdaftar. */
    NONE_ENROLLED,

    /** Hardware sibuk, perlu pembaruan keamanan, kode tidak dikenal, atau pemanggil salah. */
    UNKNOWN,
}

/**
 * Pemetaan murni (JVM-pure) dari kode hasil androidx.biometric.BiometricManager#canAuthenticate
 * ke [AppLockAvailability].
 *
 * Konstanta disalin sebagai `const val` privat (bukan referensi langsung ke androidx.biometric)
 * agar fungsi pemetaan dapat diunit-test di JVM murni tanpa perangkat Android. Sumber nilai:
 * androidx.biometric:biometric:1.1.0 — BiometricManager.BIOMETRIC_SUCCESS,
 * BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE, BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
 * BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE, BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED.
 * WAJIB disinkronkan ulang bila dependensi androidx.biometric di-upgrade.
 */
object AppLockHelper {
    /** androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS = 0 (dapat autentikasi). */
    private const val BIOMETRIC_SUCCESS = 0

    /** androidx.biometric.BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE = 1 (hardware sibuk). */
    private const val BIOMETRIC_ERROR_HW_UNAVAILABLE = 1

    /** androidx.biometric.BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED = 11 (belum ada kredensial). */
    private const val BIOMETRIC_ERROR_NONE_ENROLLED = 11

    /** androidx.biometric.BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE = 12 (tanpa sensor). */
    private const val BIOMETRIC_ERROR_NO_HARDWARE = 12

    /** androidx.biometric.BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED = 15. */
    private const val BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED = 15

    /**
     * Petakan kode hasil canAuthenticate ke [AppLockAvailability].
     *
     * Pemetaan fail-closed: hanya BIOMETRIC_SUCCESS yang dianggap [AppLockAvailability.AVAILABLE];
     * NO_HARDWARE dan NONE_ENROLLED dipetakan eksplisit agar layar kunci bisa memberi pesan
     * penjelasan, sedangkan HW_UNAVAILABLE, SECURITY_UPDATE_REQUIRED, dan kode tak dikenal
     * (termasuk negatif) jatuh ke [AppLockAvailability.UNKNOWN].
     *
     * @param canAuthenticateResult kode hasil dari androidx.biometric.BiometricManager#canAuthenticate
     */
    fun mapAvailability(canAuthenticateResult: Int): AppLockAvailability =
        when (canAuthenticateResult) {
            BIOMETRIC_SUCCESS -> AppLockAvailability.AVAILABLE
            BIOMETRIC_ERROR_NO_HARDWARE -> AppLockAvailability.NO_HARDWARE
            BIOMETRIC_ERROR_NONE_ENROLLED -> AppLockAvailability.NONE_ENROLLED
            BIOMETRIC_ERROR_HW_UNAVAILABLE -> AppLockAvailability.UNKNOWN
            BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> AppLockAvailability.UNKNOWN
            else -> AppLockAvailability.UNKNOWN
        }

    /**
     * Benar hanya bila [availability] [AppLockAvailability.AVAILABLE] — keputusan fail-closed:
     * kunci hanya boleh dipasang/ditegakkan bila ada autentikator yang benar-benar bisa dipakai.
     */
    fun canLock(availability: AppLockAvailability): Boolean = availability == AppLockAvailability.AVAILABLE
}
