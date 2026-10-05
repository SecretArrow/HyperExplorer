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

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.KeyStoreException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Adapter tipis [VaultKeyProvider] yang menyimpan KEK (kunci master AES-256) di
 * Android Keystore. **Hanya untuk runtime Android; tidak diuji di JVM** — unit
 * test memakai [SoftwareKeyProvider] karena AndroidKeyStore tidak tersedia di host.
 *
 * Kunci dibuat sekali (get-or-create) dengan alias [alias] dan non-eksporabilitas
 * bawaan Keystore; DEK per berkas tetap dibungkus di dalam aplikasi, bukan di
 * dalam keystore, sehingga format blob tetap standar dan portabel.
 */
class AndroidKeystoreKeyProvider(
    private val alias: String = "hyper_vault_kek",
) : VaultKeyProvider {
    /**
     * Mengambil kunci AES-256 GCM dari AndroidKeyStore, atau membuatnya bila belum ada.
     *
     * @throws GeneralSecurityException bila keystore tidak dapat dibaca/dibuat atau
     * entri dengan [alias] ternyata bukan kunci AES.
     */
    private fun obtainKey(): SecretKey =
        try {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
            keyStore.load(null)
            val existing = keyStore.getEntry(alias, null)
            if (existing is KeyStore.SecretKeyEntry) {
                val key = existing.secretKey
                if (key.algorithm != KeyProperties.KEY_ALGORITHM_AES) {
                    throw GeneralSecurityException(
                        "entri '$alias' di AndroidKeyStore bukan kunci AES (algoritma: ${key.algorithm})",
                    )
                }
                key
            } else {
                val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
                generator.init(
                    KeyGenParameterSpec
                        .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(KEY_SIZE_BITS)
                        .build(),
                )
                generator.generateKey()
            }
        } catch (e: IOException) {
            throw GeneralSecurityException("AndroidKeyStore tidak dapat dibaca: ${e.message ?: e.javaClass.simpleName}", e)
        } catch (e: KeyStoreException) {
            throw GeneralSecurityException("AndroidKeyStore tidak dapat diakses: ${e.message ?: e.javaClass.simpleName}", e)
        }

    override fun wrapDek(
        dek: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // Tanpa IV eksplisit: AndroidKeyStore menghasilkan cipher.iv 12 byte sendiri.
        cipher.init(Cipher.ENCRYPT_MODE, obtainKey())
        cipher.updateAAD(aad)
        val encrypted = cipher.doFinal(dek)
        return cipher.iv + encrypted
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
        cipher.init(Cipher.DECRYPT_MODE, obtainKey(), GCMParameterSpec(VaultFormat.GCM_TAG_BITS, nonce))
        cipher.updateAAD(aad)
        // AEADBadTagException sengaja dibiarkan naik agar VaultEngine memetakannya
        // menjadi VaultError.CorruptEntry (tag/id tidak cocok).
        return cipher.doFinal(wrapped, VaultFormat.WRAP_NONCE_SIZE, wrapped.size - VaultFormat.WRAP_NONCE_SIZE)
    }

    private companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE_BITS = 256
    }
}
