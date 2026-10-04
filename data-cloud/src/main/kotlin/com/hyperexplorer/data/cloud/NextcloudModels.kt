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

package com.hyperexplorer.data.cloud

/**
 * Awal sesi Nextcloud Login flow v2.
 *
 * Data ini dihasilkan oleh [NextcloudLoginFlow.start] dari respons
 * `POST {server}/index.php/login/v2` dan menjadi jembatan antara aplikasi,
 * browser perangkat, dan server Nextcloud selama proses otorisasi berlangsung.
 * Tidak ada kredensial yang ada di dalam objek ini — nilainya masih boleh
 * ditampilkan/dilog tanpa risiko kebocoran.
 *
 * @property loginUrl URL yang DIBUKA DI BROWSER pengguna (bukan di WebView aplikasi)
 *   agar pengguna memasukkan kredensial Nextcloud langsung ke server, bukan ke aplikasi.
 * @property pollEndpoint URL polling absolut dari server (berisi host dan path lengkap);
 *   dipanggil berulang oleh [NextcloudLoginFlow.poll] atau [NextcloudLoginFlow.awaitCredentials]
 *   sampai pengguna menyetujui login (HTTP 200) atau sesi kedaluwarsa (HTTP 404).
 * @property pollToken Token unik sesi polling yang dikirim sebagai body form `token=...`
 *   pada setiap permintaan polling; berlaku hanya untuk sesi login ini.
 */
data class LoginFlowStart(
    val loginUrl: String,
    val pollEndpoint: String,
    val pollToken: String,
)

/**
 * Kredensial hasil Nextcloud Login flow v2, diterima setelah pengguna menyetujui
 * permintaan login di browser (respons polling HTTP 200).
 *
 * `appPassword` adalah application password yang dibuat khusus untuk "Hyper Explorer"
 * oleh server Nextcloud: pengguna tidak pernah memasukkan kata sandi utamanya ke aplikasi,
 * dan kata sandi aplikasi ini dapat dicabut kapan saja dari halaman keamanan Nextcloud
 * tanpa mengganggu sesi perangkat lain. Nilai ini sensitif — simpan di penyimpanan
 * terenkripsi, jangan ditampilkan utuh di UI atau log.
 *
 * @property server URL server hasil normalisasi milik Nextcloud, mis.
 *   "https://cloud.example.com/nextcloud"; dipakai sebagai basis seluruh permintaan berikutnya.
 * @property loginName Nama akun pengguna Nextcloud yang melakukan login
 *   (login name akun, bukan email dan bukan nama tampilan).
 * @property appPassword Kata sandi aplikasi hasil Login flow v2; dipakai sebagai kredensial
 *   HTTP Basic (bersama [loginName]) pada permintaan WebDAV/berkas ke [server].
 */
data class NextcloudCredentials(
    val server: String,
    val loginName: String,
    val appPassword: String,
)
