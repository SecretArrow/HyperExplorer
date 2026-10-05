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

package com.hyperexplorer.feature.tools.vault

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Penyedia operasi pembungkusan kunci (envelope encryption).
 *
 * Engine membuat DEK acak 32 byte per berkas, lalu membungkusnya (wrap) dengan
 * kunci master. Implementasinya dapat berupa Android Keystore (perangkat) atau
 * [SoftwareKeyProvider] (uji JVM / skenario tanpa Keystore).
 *
 * Kontrak bentuk keluaran: `[12 byte nonce | ciphertext + tag GCM]`, dengan
 * AAD = id entri UTF-8 sehingga wrapped DEK terikat pada entri tertentu.
 */
interface VaultKeyProvider {
    /**
     * Membungkus [dek] (tepat 32 byte, AES-256) menggunakan AAD [aad].
     *
     * @throws GeneralSecurityException bila pembungkusan gagal (kunci tidak
     * tersedia, algoritma tidak didukung, atau verifikasi tag gagal).
     */
    fun wrapDek(
        dek: ByteArray,
        aad: ByteArray,
    ): ByteArray

    /**
     * Membuka [wrapped] DEK yang dibungkus dengan AAD [aad].
     *
     * @throws GeneralSecurityException bila tag atau AAD tidak cocok (termasuk
     * [javax.crypto.AEADBadTagException]) sehingga DEK tidak dapat dipulihkan.
     */
    fun unwrapDek(
        wrapped: ByteArray,
        aad: ByteArray,
    ): ByteArray
}

/**
 * Implementasi [VaultKeyProvider] murni JVM berbasis [SecretKeySpec] — dipakai
 * unit test dan skenario tanpa Android Keystore. Kunci master disimpan di
 * memori proses; untuk produksi gunakan [AndroidKeystoreKeyProvider].
 *
 * @property secret kunci master AES-256, tepat 32 byte.
 */
class SoftwareKeyProvider(
    private val secret: ByteArray,
) : VaultKeyProvider {
    init {
        require(secret.size == 32) { "SoftwareKeyProvider membutuhkan kunci tepat 32 byte (AES-256), dapat ${secret.size} byte" }
    }

    private val secureRandom = SecureRandom()

    override fun wrapDek(
        dek: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        val nonce = ByteArray(VaultFormat.WRAP_NONCE_SIZE).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(secret, KEY_ALGORITHM), GCMParameterSpec(VaultFormat.GCM_TAG_BITS, nonce))
        cipher.updateAAD(aad)
        val encrypted = cipher.doFinal(dek)
        return nonce + encrypted
    }

    override fun unwrapDek(
        wrapped: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        val minimumSize = VaultFormat.WRAP_NONCE_SIZE + VaultFormat.GCM_TAG_BITS / 8
        if (wrapped.size < minimumSize) {
            throw GeneralSecurityException(
                "wrapped DEK terlalu pendek: ${wrapped.size} byte, minimal $minimumSize " +
                    "(nonce ${VaultFormat.WRAP_NONCE_SIZE} byte + tag ${VaultFormat.GCM_TAG_BITS / 8} byte)",
            )
        }
        val nonce = wrapped.copyOfRange(0, VaultFormat.WRAP_NONCE_SIZE)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(secret, KEY_ALGORITHM), GCMParameterSpec(VaultFormat.GCM_TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(wrapped, VaultFormat.WRAP_NONCE_SIZE, wrapped.size - VaultFormat.WRAP_NONCE_SIZE)
    }

    private companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_ALGORITHM = "AES"
    }
}
