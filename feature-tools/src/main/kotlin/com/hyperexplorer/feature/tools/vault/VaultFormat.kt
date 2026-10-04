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

/**
 * Konstanta format kontainer vault (berkas `.hve`) beserta parameter kriptografinya.
 *
 * Semua bilangan multi-byte ditulis **big-endian**. Format didokumentasikan
 * lengkap dan hanya memakai primitif standar (AES-256-GCM) supaya berkas vault
 * tetap dapat dibaca alat lain tanpa terikat pada aplikasi ini (anti lock-in).
 *
 * **Layout berkas blob `<id>.hve`** (header 20 byte, offset 0-based inklusif):
 *
 * | Offset      | Ukuran | Isi |
 * |-------------|--------|-----|
 * | `[0..3]`    | 4 B    | magic ASCII `"HVLT"` ([MAGIC]) |
 * | `[4]`       | 1 B    | versi format = 1 ([VERSION]) |
 * | `[5..16]`   | 12 B   | nonce GCM konten, acak per berkas ([CONTENT_NONCE_SIZE]) |
 * | `[17]`      | 1 B    | id metode pembungkusan DEK ([WRAP_METHOD_KEYSTORE] untuk Android Keystore) |
 * | `[18..19]`  | 2 B    | u16 `wrappedLen` = panjang wrapped DEK |
 * | `[20..20+w)`| w B    | wrapped DEK: `nonce 12 B | ciphertext DEK + tag`, GCM dengan AAD = id entri UTF-8 |
 * | sisanya     | n B    | ciphertext GCM konten (n ≥ 16 byte tag) |
 *
 * **Plaintext konten** (hasil dekripsi bagian "sisanya"):
 *
 * ```
 * u16 nameLen | nama asli (UTF-8) | u16 mimeLen | mime (UTF-8) | payload mentah
 * ```
 *
 * Ketentuan kriptografi:
 * - DEK = 32 byte acak per berkas (AES-256); tidak pernah disimpan dalam bentuk terbuka.
 * - AAD GCM konten = id entri UTF-8 → blob tidak dapat ditukar antar entri.
 * - AAD GCM wrapped DEK = id entri UTF-8 → wrapped DEK terikat pada entri pemiliknya.
 *
 * Berkas indeks (`index.bin`) mendeskripsikan daftar entri; formatnya didokumentasikan
 * pada [VaultIndex].
 */
object VaultFormat {
    /** Magic 4 byte ASCII di awal setiap blob vault. */
    const val MAGIC = "HVLT"

    /** Versi format kontainer yang ditulis dan dibaca engine ini. */
    const val VERSION: Byte = 1

    /** Panjang nonce GCM untuk konten terenkripsi, dalam byte. */
    const val CONTENT_NONCE_SIZE = 12

    /** Panjang nonce GCM untuk pembungkusan DEK, dalam byte. */
    const val WRAP_NONCE_SIZE = 12

    /** Panjang tag autentikasi GCM, dalam bit (128 bit = 16 byte). */
    const val GCM_TAG_BITS = 128

    /** Id on-disk untuk [WrapMethod.KEYSTORE]. */
    const val WRAP_METHOD_KEYSTORE: Int = 1

    /** Ukuran buffer untuk seluruh operasi I/O streaming (8 KB, tidak memuat berkas utuh ke memori). */
    const val STREAM_BUFFER_SIZE = 8 * 1024

    /**
     * Batas panjang string metadata (nama/mime) saat decode plaintext, sebagai
     * pengaman anti-OOM terhadap berkas palsu yang mendeklarasikan panjang ekstrem.
     */
    const val MAX_META_STRING_CHARS = 4096
}
