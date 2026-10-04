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

package com.hyperexplorer.feature.sync.engine

import com.hyperexplorer.data.remote.RemoteConnection

/**
 * Arah sinkronisasi satu arah antara folder lokal dan folder remote.
 *
 * V1 sengaja TIDAK menyediakan arah dua arah (bidirectional) agar semantik
 * konflik sederhana: sumber kebenaran selalu sisi asal, sisi tujuan tidak
 * pernah dihapus (non-destruktif).
 */
enum class SyncDirection {
    /** Lokal adalah sumber: berkas baru/berubah diunggah ke remote. */
    PUSH_TO_REMOTE,

    /** Remote adalah sumber: berkas baru/berubah diunduh ke lokal. */
    PULL_TO_LOCAL,
}

/**
 * Ringkasan hasil satu kali eksekusi sinkronisasi.
 *
 * Run dianggap TETAP SUKSES meski ada kegagalan per-item: kegagalan dicatat di
 * [failures] dan bisa diperiksa lewat [isClean]. Hanya kegagalan fail-fast
 * (folder lokal tidak valid, listing remote gagal) yang menjadi
 * `Result.failure`.
 *
 * @property uploaded jumlah berkas yang berhasil diunggah (PUSH).
 * @property downloaded jumlah berkas yang berhasil diunduh (PULL).
 * @property createdDirectories jumlah folder yang berhasil dibuat; PUSH dihitung
 *   per level remote (`a` lalu `a/b` = 2), PULL dihitung per folder remote yang
 *   belum ada di lokal.
 * @property skipped jumlah berkas yang dilewati tanpa transfer: identik
 *   (ukuran sama dan selisih mtime <= 1000 ms) ATAU dilewati karena folder
 *   induknya gagal dibuat.
 * @property failures daftar pesan kegagalan per item dengan format
 *   `"relatif/path: pesan singkat"`; kosong bila run bersih.
 */
data class SyncStats(
    val uploaded: Int,
    val downloaded: Int,
    val createdDirectories: Int,
    val skipped: Int,
    val failures: List<String>,
) {
    /** true bila tidak ada satu pun kegagalan per-item pada run ini. */
    fun isClean(): Boolean = failures.isEmpty()
}

/**
 * Definisi satu pasangan folder lokal <-> remote yang disinkronkan.
 *
 * @property id identifier tersimpan; 0 berarti pasangan baru (id ditentukan
 *   [com.hyperexplorer.feature.sync.store.SyncPairStore.upsert] sebagai
 *   maxId+1).
 * @property localPath path absolut folder lokal di perangkat.
 * @property connection koneksi remote tujuan/asal (protokol, host, port,
 *   kredensial) dari modul :data-remote.
 * @property remotePath koordinat remote sisi sinkronisasi, SELALU hasil
 *   `RemotePath.normalize` (tanpa '/' depan/belakang, tanpa ".."; "" = root).
 * @property direction arah sinkronisasi; bila nilai tersimpan tidak dikenal,
 *   penyimpanan memakai fallback [SyncDirection.PUSH_TO_REMOTE].
 * @property intervalMinutes periode periodic sync dalam menit; disimpan dan
 *   dibaca kembali dengan clamp ke 15..1440 (floor WorkManager 15 menit).
 */
data class SyncPair(
    val id: Long = 0L,
    val localPath: String,
    val connection: RemoteConnection,
    val remotePath: String,
    val direction: SyncDirection,
    val intervalMinutes: Int,
)
