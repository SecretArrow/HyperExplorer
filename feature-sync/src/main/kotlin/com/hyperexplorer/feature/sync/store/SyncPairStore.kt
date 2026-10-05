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

package com.hyperexplorer.feature.sync.store

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.hyperexplorer.data.remote.RemoteConnection
import com.hyperexplorer.data.remote.RemoteCredentials
import com.hyperexplorer.data.remote.RemoteProtocol
import com.hyperexplorer.feature.sync.engine.SyncDirection
import com.hyperexplorer.feature.sync.engine.SyncPair
import org.json.JSONArray
import org.json.JSONObject

/**
 * Penyimpan daftar pasangan sinkronisasi ([SyncPair]) memakai
 * EncryptedSharedPreferences (kunci master AES256-GCM, PrefKey SIV /
 * Value GCM) — pola yang sama dengan ConnectionStore di :feature-network —
 * sehingga kredensial koneksi yang ikut diserialisasi tetap terenkripsi.
 *
 * Daftar pasangan diserialisasi sebagai satu array JSON (org.json, bawaan
 * Android) pada satu kunci [KEY_PAIRS]. Field koneksi disalin penuh dengan
 * prefix `c_` (protocol + ordinal, host, port, share, basePath, username,
 * password, anonymous, displayName, secure).
 *
 * Seluruh metode tidak pernah melempar ke pemanggil: baca yang gagal
 * mengembalikan daftar kosong, tulis yang gagal diabaikan. Pembuatan
 * MasterKey cukup berat, jadi akses [prefs] dibuat lazy dan metode wajib
 * dipanggil dari worker thread.
 */
class SyncPairStore(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences by lazy { createPrefs(appContext) }

    /** Baca seluruh pasangan tersimpan; kosong bila belum ada atau berkas rusak. */
    fun read(): List<SyncPair> = runCatching { decode(prefs.getString(KEY_PAIRS, null)) }.getOrDefault(emptyList())

    /**
     * Sisipkan atau perbarui [pair] (dicocokkan lewat id). Id 0 berarti
     * pasangan baru: id dipilih maxId+1. intervalMinutes di-clamp ke
     * 15..1440 sebelum disimpan. Mengembalikan id tersimpan.
     */
    fun upsert(pair: SyncPair): Long {
        val current = read()
        val id = if (pair.id == 0L) (current.maxOfOrNull { it.id } ?: 0L) + 1L else pair.id
        val stored = pair.copy(id = id, intervalMinutes = clampInterval(pair.intervalMinutes))
        val mutable = current.toMutableList()
        val index = mutable.indexOfFirst { it.id == id }
        if (index >= 0) {
            mutable[index] = stored
        } else {
            mutable.add(stored)
        }
        save(mutable)
        return id
    }

    /** Hapus pasangan dengan [id] bila ada. */
    fun delete(id: Long) {
        save(read().filterNot { it.id == id })
    }

    private fun save(pairs: List<SyncPair>) {
        runCatching { prefs.edit().putString(KEY_PAIRS, encode(pairs)).apply() }
    }

    /** Pola ConnectionStore: MasterKey/preferences rusak -> mulai dari nol. */
    private fun createPrefs(context: Context): SharedPreferences =
        try {
            open(context)
        } catch (t: Throwable) {
            // MasterKey/berkas preferences rusak (mis. pemulihan cadangan) —
            // hapus lalu buat ulang agar fitur tetap terpakai; data lama hilang.
            context.deleteSharedPreferences(PREFS_NAME)
            open(context)
        }

    private fun open(context: Context): SharedPreferences =
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    private fun encode(pairs: List<SyncPair>): String {
        val array = JSONArray()
        pairs.forEach { pair -> array.put(pair.toJson()) }
        return array.toString()
    }

    private fun decode(raw: String?): List<SyncPair> {
        if (raw.isNullOrEmpty()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.toPair() }
        }.getOrDefault(emptyList())
    }

    private fun SyncPair.toJson(): JSONObject =
        JSONObject().apply {
            put(KEY_ID, id)
            put(KEY_LOCAL_PATH, localPath)
            put(KEY_REMOTE_PATH, remotePath)
            put(KEY_DIRECTION, direction.name)
            put(KEY_INTERVAL, clampInterval(intervalMinutes))
            put(KEY_C_PROTOCOL, connection.protocol.name)
            put(KEY_C_PROTOCOL_INDEX, connection.protocol.ordinal)
            put(KEY_C_HOST, connection.host)
            put(KEY_C_PORT, connection.port)
            put(KEY_C_SHARE, connection.share)
            put(KEY_C_BASE_PATH, connection.basePath)
            put(KEY_C_USERNAME, connection.credentials.username)
            put(KEY_C_PASSWORD, connection.credentials.password)
            put(KEY_C_ANONYMOUS, connection.credentials.anonymous)
            put(KEY_C_DISPLAY_NAME, connection.displayName)
            put(KEY_C_SECURE, connection.secure)
        }

    private fun JSONObject.toPair(): SyncPair? =
        runCatching {
            SyncPair(
                id = optLong(KEY_ID),
                localPath = optString(KEY_LOCAL_PATH),
                remotePath = optString(KEY_REMOTE_PATH),
                direction = readDirection(),
                intervalMinutes = clampInterval(optInt(KEY_INTERVAL, DEFAULT_INTERVAL_MINUTES)),
                connection = readConnection(),
            )
        }.getOrNull()

    /** Arah dibaca dari nama enum; bila tak dikenal, fallback PUSH_TO_REMOTE. */
    private fun JSONObject.readDirection(): SyncDirection =
        SyncDirection.entries.firstOrNull { it.name == optString(KEY_DIRECTION) }
            ?: SyncDirection.PUSH_TO_REMOTE

    /** Koneksi dibangun ulang penuh; protokol dibaca dari nama lalu indeks enum. */
    private fun JSONObject.readConnection(): RemoteConnection =
        RemoteConnection(
            id = optLong(KEY_ID),
            protocol = readProtocol(),
            host = optString(KEY_C_HOST),
            port = optInt(KEY_C_PORT),
            share = optString(KEY_C_SHARE),
            basePath = optString(KEY_C_BASE_PATH),
            credentials =
                RemoteCredentials(
                    username = optString(KEY_C_USERNAME),
                    password = optString(KEY_C_PASSWORD),
                    anonymous = optBoolean(KEY_C_ANONYMOUS, true),
                ),
            displayName = optString(KEY_C_DISPLAY_NAME),
            secure = optBoolean(KEY_C_SECURE, false),
        )

    /** Pola ConnectionStore: nama enum dulu; bila tak dikenal, jatuh ke indeks. */
    private fun JSONObject.readProtocol(): RemoteProtocol =
        RemoteProtocol.entries.firstOrNull { it.name == optString(KEY_C_PROTOCOL) }
            ?: RemoteProtocol.entries.getOrNull(optInt(KEY_C_PROTOCOL_INDEX))
            ?: RemoteProtocol.FTP

    private companion object {
        const val PREFS_NAME = "hyper_sync_prefs"
        const val KEY_PAIRS = "pairs"
        const val KEY_ID = "id"
        const val KEY_LOCAL_PATH = "localPath"
        const val KEY_REMOTE_PATH = "remotePath"
        const val KEY_DIRECTION = "direction"
        const val KEY_INTERVAL = "intervalMinutes"

        /** Prefix key field koneksi [RemoteConnection] di dalam JSON pasangan. */
        const val KEY_C_PREFIX = "c_"
        const val KEY_C_PROTOCOL = "${KEY_C_PREFIX}protocol"
        const val KEY_C_PROTOCOL_INDEX = "${KEY_C_PREFIX}protocolIndex"
        const val KEY_C_HOST = "${KEY_C_PREFIX}host"
        const val KEY_C_PORT = "${KEY_C_PREFIX}port"
        const val KEY_C_SHARE = "${KEY_C_PREFIX}share"
        const val KEY_C_BASE_PATH = "${KEY_C_PREFIX}basePath"
        const val KEY_C_USERNAME = "${KEY_C_PREFIX}username"
        const val KEY_C_PASSWORD = "${KEY_C_PREFIX}password"
        const val KEY_C_ANONYMOUS = "${KEY_C_PREFIX}anonymous"
        const val KEY_C_DISPLAY_NAME = "${KEY_C_PREFIX}displayName"
        const val KEY_C_SECURE = "${KEY_C_PREFIX}secure"

        const val MIN_INTERVAL_MINUTES = 15
        const val MAX_INTERVAL_MINUTES = 1440
        const val DEFAULT_INTERVAL_MINUTES = 60

        /** Floor WorkManager 15 menit: clamp interval ke 15..1440. */
        fun clampInterval(minutes: Int): Int = minutes.coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)
    }
}
