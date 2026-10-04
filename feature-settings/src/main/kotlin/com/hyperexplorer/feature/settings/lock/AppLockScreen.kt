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

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.hyperexplorer.feature.settings.R
import java.util.concurrent.Executors

/** Kode galat internal untuk kegagalan BUKAN dari BiometricPrompt (bukan kode resmi AndroidX). */
private const val INTERNAL_ERROR_CODE = -1

/** Ukuran ikon gembok besar pada layar kunci. */
private val LOCK_ICON_SIZE = 64.dp

/**
 * Status pesan yang tampil pada layar kunci (di luar [AppLockAvailability] yang datang dari host).
 */
private sealed interface LockStatus {
    /** Kondisi awal/normal: tanpa pesan, hanya tombol buka kunci. */
    data object Idle : LockStatus

    /** Konteks bukan FragmentActivity sehingga prompt tidak dapat ditampilkan (defensive). */
    data object NoActivity : LockStatus

    /** Autentikasi tidak dikenali (prompt MASIH aktif — pengguna boleh mencoba di dialog). */
    data object Failed : LockStatus

    /** Terkunci sementara/permanen karena terlalu banyak percobaan (lockout). */
    data object Lockout : LockStatus

    /** Galat lain dengan kode BiometricPrompt, atau [INTERNAL_ERROR_CODE] untuk galat internal. */
    data class Error(val code: Int) : LockStatus
}

/**
 * Layar kunci aplikasi penuh: ikon gembok, judul, subjudul, pesan status, dan tombol buka kunci.
 *
 * ALUR (defensive, fail-closed dengan jalan keluar terdokumentasi):
 * 1. [availability] == AVAILABLE → tombol "Buka kunci" tampil DAN prompt di-AUTO-launch SEKALI
 *    via LaunchedEffect(Unit) saat layar masuk komposisi. Guard re-entry `promptShown` menjamin
 *    satu prompt aktif pada satu waktu: auto-launch hanya sekali per masuk komposisi, tombol
 *    coba-lagi tidak berfungsi selama prompt masih aktif, dan guard dibuka kembali pada
 *    onAuthenticationError sehingga pengguna bisa mencoba lagi secara manual.
 * 2. [availability] != AVAILABLE (tidak ada sensor / belum ada kredensial terdaftar) → tampil
 *    pesan penjelasan + tombol "Nonaktifkan kunci aplikasi" ([onDisableLock]). Keputusan desain:
 *    fail-closed (aplikasi tetap terkunci karena tidak ada cara autentikasi) TAPI menyediakan
 *    jalan keluar manual agar pengguna tidak terkunci permanen; tombol buka kunci disembunyikan.
 * 3. Konteks bukan [FragmentActivity] → prompt tidak bisa ditampilkan; tampil pesan + tombol
 *    coba + tombol nonaktifkan (jalan keluar tetap tersedia).
 * 4. Callback prompt:
 *    - onAuthenticationError CANCELED/NEGATIVE_BUTTON → diam (tetap terkunci, tombol coba tampil);
 *    - LOCKOUT/LOCKOUT_PERMANENT → pesan khusus "terlalu banyak percobaan";
 *    - galat lain → pesan generik dengan kode (stringResource lock_error_generic);
 *    - onAuthenticationSucceeded → [onUnlocked] dipanggil (host yang membuka aplikasi);
 *    - onAuthenticationFailed → pesan "tidak dikenali" — prompt masih aktif di depan.
 * 5. Pembangunan PromptInfo dibungkus try/catch IllegalArgumentException (kombinasi autentikator
 *    invalid) dan pemanggilan authenticate dibungkus try/catch Exception — galat apa pun
 *    menampilkan pesan informatif dan LAYAR TETAP TERKUNCI (tidak pernah membuka tanpa autentikasi).
 *
 * Catatan teknis: prompt memakai executor satu utas khusus (dibuat sekali per instansi layar,
 * di-shutdown saat layar meninggalkan komposisi); callback menulis status snapshot Compose
 * (aman lintas utas) dan dipetakan ke pesan ber-resource — tidak ada teks hardcode.
 *
 * @param availability hasil pemeriksaan autentikator dari AppLockHelper.mapAvailability
 * @param onUnlocked dipanggil setelah autentikasi sukses; host yang membuka konten utama
 * @param onDisableLock dipanggil saat pengguna memilih jalan keluar manual; host harus menonaktifkan
 *   AppLockPrefs (write false) lalu memanggil onUnlocked-nya sendiri
 * @param modifier modifier pada Box layar penuh
 */
@Composable
fun AppLockScreen(
    availability: AppLockAvailability,
    onUnlocked: () -> Unit,
    onDisableLock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    var promptShown by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<LockStatus>(LockStatus.Idle) }
    val promptTitle = stringResource(R.string.lock_title)
    val promptSubtitle = stringResource(R.string.lock_subtitle)

    DisposableEffect(Unit) {
        onDispose { executor.shutdown() }
    }

    /** Tampilkan prompt sekali; guard `promptShown` mencegah re-entry saat prompt masih aktif. */
    fun launchPrompt() {
        if (promptShown) return
        val activity = context as? FragmentActivity
        if (activity == null) {
            status = LockStatus.NoActivity
            return
        }
        val promptInfo =
            try {
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle(promptTitle)
                    .setSubtitle(promptSubtitle)
                    .setAllowedAuthenticators(
                        // 1.1.0: konstanta ada di BiometricManager.Authenticators
                        // (BiometricPrompt.Authenticators baru ada sejak 1.2.0).
                        BiometricManager.Authenticators.BIOMETRIC_WEAK or
                            BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                    )
                    .build()
                // DEVICE_CREDENTIAL menyediakan batal/kredensial bawaan → tanpa setNegativeButtonText.
            } catch (e: IllegalArgumentException) {
                status = LockStatus.Error(INTERNAL_ERROR_CODE)
                return
            }
        promptShown = true
        try {
            BiometricPrompt(
                activity,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationError(
                        errorCode: Int,
                        errString: CharSequence,
                    ) {
                        promptShown = false
                        status =
                            when (errorCode) {
                                BiometricPrompt.ERROR_CANCELED,
                                BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                                -> LockStatus.Idle
                                BiometricPrompt.ERROR_LOCKOUT,
                                BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
                                -> LockStatus.Lockout
                                else -> LockStatus.Error(errorCode)
                            }
                    }

                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        status = LockStatus.Idle
                        onUnlocked()
                    }

                    override fun onAuthenticationFailed() {
                        status = LockStatus.Failed
                    }
                },
            ).authenticate(promptInfo)
        } catch (e: Exception) {
            promptShown = false
            status = LockStatus.Error(INTERNAL_ERROR_CODE)
        }
    }

    // (1) AUTO-launch prompt sekali per masuk komposisi — hanya bila autentikator tersedia.
    LaunchedEffect(Unit) {
        if (availability == AppLockAvailability.AVAILABLE) launchPrompt()
    }

    Box(
        modifier = modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                modifier = Modifier.size(LOCK_ICON_SIZE),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(text = stringResource(R.string.lock_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                text = stringResource(R.string.lock_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            LockStatusMessage(status = status, modifier = Modifier.fillMaxWidth())
            if (availability == AppLockAvailability.AVAILABLE) {
                Button(onClick = { launchPrompt() }) {
                    Text(text = stringResource(R.string.lock_button))
                }
                if (status is LockStatus.NoActivity) {
                    // Jalan keluar manual: prompt tak mungkin tampil dari layar ini.
                    Button(onClick = onDisableLock) {
                        Text(text = stringResource(R.string.lock_button_disable))
                    }
                }
            } else {
                Text(
                    text = stringResource(R.string.lock_no_authenticator),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                // Jalan keluar manual terdokumentasi: nonaktifkan kunci agar tidak terkunci permanen.
                Button(onClick = onDisableLock) {
                    Text(text = stringResource(R.string.lock_button_disable))
                }
            }
        }
    }
}

/** Pesan status layar kunci; kosong (Unit) bila [status] Idle — tidak ada cabang yang menggantung. */
@Composable
private fun LockStatusMessage(
    status: LockStatus,
    modifier: Modifier = Modifier,
) {
    when (status) {
        LockStatus.Idle -> Unit
        LockStatus.NoActivity ->
            LockStatusText(text = stringResource(R.string.lock_no_activity), modifier = modifier)
        LockStatus.Failed ->
            LockStatusText(text = stringResource(R.string.lock_failed), modifier = modifier)
        LockStatus.Lockout ->
            LockStatusText(text = stringResource(R.string.lock_error_lockout), modifier = modifier)
        is LockStatus.Error ->
            LockStatusText(
                text = stringResource(R.string.lock_error_generic, status.code),
                modifier = modifier,
            )
    }
}

@Composable
private fun LockStatusText(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
}
