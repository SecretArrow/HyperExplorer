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

package com.hyperexplorer.feature.network

import com.hyperexplorer.data.remote.RemoteProtocol

/** Dua mode layar utama modul jaringan. */
enum class NetworkScreen {
    /** Daftar sambungan tersimpan. */
    CONNECTIONS,

    /** Menjelajahi isi sebuah lokasi jaringan. */
    BROWSING,
}

/** Jenis galat operasi jaringan; teksnya dipetakan ke resource string di lapisan UI. */
enum class NetworkError {
    /** Pembuatan koneksi ke server gagal. */
    CONNECT_FAILED,

    /** Koneksi berhasil tetapi isi folder gagal dibaca. */
    LIST_FAILED,

    /** Penghapusan berkas/folder jarak jauh gagal. */
    DELETE_FAILED,

    /** Penggantian nama berkas/folder jarak jauh gagal. */
    RENAME_FAILED,

    /** Pengunduhan berkas gagal. */
    DOWNLOAD_FAILED,

    /** Pembuatan folder jarak jauh gagal. */
    MKDIR_FAILED,
}

/** Status uji koneksi pada dialog tambah/ubah sambungan. */
enum class ConnectionTestStatus {
    /** Belum diuji. */
    IDLE,

    /** Uji sedang berjalan. */
    RUNNING,

    /** Uji berhasil. */
    SUCCESS,

    /** Uji gagal; detailnya ada pada state testDetail. */
    FAILURE,
}

/**
 * Isi form tambah/ubah sambungan.
 *
 * @param id 0 berarti sambungan baru; selain itu id sambungan yang diedit.
 * @param port teks port agar terikat langsung pada TextField.
 * @param basePath share (SMB) atau path dasar (protokol lain) — labelnya dinamis.
 */
data class NetworkFormState(
    val id: Long = 0L,
    val protocol: RemoteProtocol = RemoteProtocol.FTP,
    val host: String = "",
    val port: String = defaultPortText(RemoteProtocol.FTP),
    val basePath: String = "",
    val username: String = "",
    val password: String = "",
) {
    companion object {
        /** Teks port bawaan untuk [protocol] sesuai port default protokol. */
        fun defaultPortText(protocol: RemoteProtocol): String = protocol.defaultPort.toString()
    }
}

/**
 * Validasi murni (tanpa dependensi Android) untuk form sambungan,
 * sehingga dapat diuji lewat unit test JVM biasa.
 */
object NetworkFormValidator {
    /** Jenis pelanggaran validasi form. */
    enum class ValidationError {
        /** Host kosong/blank. */
        EMPTY_HOST,

        /** Port bukan angka atau di luar 1..65535. */
        INVALID_PORT,
    }

    /** Kumpulkan seluruh pelanggaran pada [host] dan [port] (teks dari TextField). */
    fun validate(
        host: String,
        port: String,
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        if (host.isBlank()) errors += ValidationError.EMPTY_HOST
        val parsed = port.trim().toIntOrNull()
        if (parsed == null || parsed !in MIN_PORT..MAX_PORT) errors += ValidationError.INVALID_PORT
        return errors
    }

    private const val MIN_PORT = 1
    private const val MAX_PORT = 65535
}
