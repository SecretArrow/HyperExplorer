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

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.hyperexplorer.data.remote.RemoteConnection
import com.hyperexplorer.data.remote.RemoteCredentials
import com.hyperexplorer.data.remote.RemoteProtocol
import org.json.JSONArray
import org.json.JSONObject

/**
 * Penyimpan daftar sambungan jaringan memakai EncryptedSharedPreferences
 * (kunci master AES256-GCM) sehingga kredensial tersimpan terenkripsi di perangkat.
 *
 * Daftar sambungan diserialisasi sebagai satu array JSON (org.json, bawaan Android)
 * pada satu kunci. Kredensial tidak pernah ditulis ke log: satu-satunya tempatnya
 * adalah berkas preferences terenkripsi ini.
 *
 * Pembuatan MasterKey cukup berat, jadi akses [prefs] dibuat lazy dan seluruh metode
 * harus dipanggil dari worker thread; gagal baca/tulis tidak pernah melempar ke
 * pemanggil (kembali kosong) agar UI tidak pernah mati karena penyimpanan rusak.
 */
class ConnectionStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences by lazy { createPrefs(appContext) }

    /** Baca seluruh sambungan tersimpan; kosong bila belum ada atau berkas rusak. */
    fun read(): List<RemoteConnection> = runCatching { decode(prefs.getString(KEY_CONNECTIONS, null)) }.getOrDefault(emptyList())

    /** Timpa seluruh daftar sambungan; gagal ditulis diabaikan begitu saja. */
    fun save(connections: List<RemoteConnection>) {
        runCatching { prefs.edit().putString(KEY_CONNECTIONS, encode(connections)).apply() }
    }

    /**
     * Sisipkan atau perbarui [connection] (dicocokkan lewat id).
     * Id 0 berarti sambungan baru: id dipilih maxId+1. Mengembalikan id tersimpan.
     */
    fun upsert(connection: RemoteConnection): Long {
        val current = read().toMutableList()
        val id = if (connection.id == 0L) (current.maxOfOrNull { it.id } ?: 0L) + 1L else connection.id
        val stored = connection.copy(id = id)
        val index = current.indexOfFirst { it.id == id }
        if (index >= 0) {
            current[index] = stored
        } else {
            current.add(stored)
        }
        save(current)
        return id
    }

    /** Hapus sambungan dengan [id] bila ada. */
    fun delete(id: Long) {
        save(read().filterNot { it.id == id })
    }

    private fun createPrefs(context: Context): SharedPreferences =
        try {
            open(context)
        } catch (t: Throwable) {
            // MasterKey/berkas preferences rusak (mis. pemulihan cadangan) —
            // mulai dari nol agar aplikasi tetap terpakai; kredensial lama hilang.
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

    private fun encode(connections: List<RemoteConnection>): String {
        val array = JSONArray()
        connections.forEach { connection -> array.put(connection.toJson()) }
        return array.toString()
    }

    private fun decode(raw: String?): List<RemoteConnection> {
        if (raw.isNullOrEmpty()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.toConnection() }
        }.getOrDefault(emptyList())
    }

    private fun RemoteConnection.toJson(): JSONObject =
        JSONObject().apply {
            put(KEY_ID, id)
            put(KEY_PROTOCOL, protocol.name)
            put(KEY_PROTOCOL_INDEX, protocol.ordinal)
            put(KEY_HOST, host)
            put(KEY_PORT, port)
            put(KEY_SHARE, share)
            put(KEY_BASE_PATH, basePath)
            put(KEY_USERNAME, credentials.username)
            put(KEY_PASSWORD, credentials.password)
            put(KEY_ANONYMOUS, credentials.anonymous)
            put(KEY_DISPLAY_NAME, displayName)
            put(KEY_SECURE, secure)
        }

    private fun JSONObject.toConnection(): RemoteConnection? =
        runCatching {
            RemoteConnection(
                id = optLong(KEY_ID),
                protocol = readProtocol(),
                host = optString(KEY_HOST),
                port = optInt(KEY_PORT),
                share = optString(KEY_SHARE),
                basePath = optString(KEY_BASE_PATH),
                credentials =
                    RemoteCredentials(
                        username = optString(KEY_USERNAME),
                        password = optString(KEY_PASSWORD),
                        anonymous = optBoolean(KEY_ANONYMOUS, true),
                    ),
                displayName = optString(KEY_DISPLAY_NAME),
                secure = optBoolean(KEY_SECURE, false),
            )
        }.getOrNull()

    /** Protokol dibaca dari namanya; bila tak dikenal, jatuh ke indeks enum. */
    private fun JSONObject.readProtocol(): RemoteProtocol =
        RemoteProtocol.entries.firstOrNull { it.name == optString(KEY_PROTOCOL) }
            ?: RemoteProtocol.entries.getOrNull(optInt(KEY_PROTOCOL_INDEX))
            ?: RemoteProtocol.FTP

    private companion object {
        const val PREFS_NAME = "hyper_network_prefs"
        const val KEY_CONNECTIONS = "connections"
        const val KEY_ID = "id"
        const val KEY_PROTOCOL = "protocol"
        const val KEY_PROTOCOL_INDEX = "protocolIndex"
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_SHARE = "share"
        const val KEY_BASE_PATH = "basePath"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
        const val KEY_ANONYMOUS = "anonymous"
        const val KEY_DISPLAY_NAME = "displayName"
        const val KEY_SECURE = "secure"
    }
}
