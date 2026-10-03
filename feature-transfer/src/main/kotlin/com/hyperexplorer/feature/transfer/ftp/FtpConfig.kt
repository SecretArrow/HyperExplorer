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

package com.hyperexplorer.feature.transfer.ftp

import java.io.File

/**
 * Konfigurasi server FTP transfer berkas.
 *
 * Kelas ini sengaja murni data tanpa validasi: seluruh pemeriksaan
 * (kredensial non-kosong, rentang port, keberadaan direktori root) dilakukan
 * oleh [FtpServer.start] sehingga bisa diuji pada satu tempat.
 *
 * @property port port yang diikat; `0` berarti pilih port acak (ephemeral).
 * @property username nama pengguna; kosong/blank DITOLAK (anonim dilarang).
 * @property password kata sandi; kosong/blank DITOLAK (anonim dilarang).
 * @property rootDir direktori akar yang diekspos ke klien FTP.
 * @property idleTimeoutMinutes berapa menit tanpa koneksi sebelum server
 *   berhenti otomatis; `0` berarti berhenti segera saat tidak ada koneksi.
 */
data class FtpConfig(
    val port: Int,
    val username: String,
    val password: String,
    val rootDir: File,
    val idleTimeoutMinutes: Int = 15,
)
