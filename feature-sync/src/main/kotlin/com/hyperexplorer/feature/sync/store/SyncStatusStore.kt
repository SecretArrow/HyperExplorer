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

/**
 * Penyimpan status hasil sinkronisasi terakhir per pasangan memakai
 * SharedPreferences BIASA (non-sensitif): isinya hanya ringkasan hitungan dan
 * pesan hasil — TIDAK ada kredensial, sehingga tidak perlu enkripsi.
 *
 * Semua metode dibungkus runCatching dan tidak pernah melempar ke pemanggil:
 * penyimpanan status yang rusak tidak boleh mematikan worker maupun UI.
 */
class SyncStatusStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences by lazy {
        runCatching {
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }.getOrDefault(SILENT_PREFS)
    }

    /** Ringkasan hasil terakhir [pairId]; null bila belum pernah jalan/baca gagal. */
    fun lastResult(pairId: Long): String? = runCatching { prefs.getString(keyResult(pairId), null) }.getOrNull()

    /** Epoch ms sinkronisasi terakhir [pairId]; 0L bila belum pernah jalan. */
    fun lastSyncAt(pairId: Long): Long = runCatching { prefs.getLong(keySyncAt(pairId), 0L) }.getOrDefault(0L)

    /**
     * Catat hasil sinkronisasi [pairId] pada waktu [atEpochMs].
     *
     * @param pairId id pasangan yang dieksekusi.
     * @param ok true bila run sukses (tetap boleh berisi failure per-item).
     * @param summary teks ringkasan satu baris (mis. "OK 3↑ 0↓ 2 dir [1 gagal]");
     *   bila kosong, dipakai "OK"/"Gagal" sesuai [ok].
     * @param atEpochMs waktu pencatatan (epoch ms).
     */
    fun record(
        pairId: Long,
        ok: Boolean,
        summary: String,
        atEpochMs: Long,
    ) {
        runCatching {
            val message = summary.ifBlank { if (ok) RESULT_OK else RESULT_FAILED }
            prefs.edit().putString(keyResult(pairId), message).putLong(keySyncAt(pairId), atEpochMs).apply()
        }
    }

    /** Hapus status terakhir [pairId] (mis. saat jadwal pasangan dibatalkan). */
    fun clear(pairId: Long) {
        runCatching { prefs.edit().remove(keyResult(pairId)).remove(keySyncAt(pairId)).apply() }
    }

    private fun keyResult(pairId: Long): String = "$KEY_RESULT_PREFIX$pairId"

    private fun keySyncAt(pairId: Long): String = "$KEY_SYNC_AT_PREFIX$pairId"

    private companion object {
        const val PREFS_NAME = "hyper_sync_status"
        const val KEY_RESULT_PREFIX = "last_result_"
        const val KEY_SYNC_AT_PREFIX = "last_sync_at_"
        const val RESULT_OK = "OK"
        const val RESULT_FAILED = "Gagal"

        /**
         * Fallback no-op bila getSharedPreferences gagal (defensive; praktis
         * tidak terjadi): baca mengembalikan default, tulis diabaikan diam-diam.
         */
        val SILENT_PREFS: SharedPreferences = SilentSharedPreferences
    }
}

/** Implementasi [SharedPreferences] no-op untuk fallback [SyncStatusStore]. */
private object SilentSharedPreferences : SharedPreferences {
    override fun getAll(): MutableMap<String, *> = mutableMapOf<String, Any>()

    override fun getString(
        key: String?,
        defValue: String?,
    ): String? = defValue

    override fun getStringSet(
        key: String?,
        defValues: MutableSet<String>?,
    ): MutableSet<String>? = defValues

    override fun getInt(
        key: String?,
        defValue: Int,
    ): Int = defValue

    override fun getLong(
        key: String?,
        defValue: Long,
    ): Long = defValue

    override fun getFloat(
        key: String?,
        defValue: Float,
    ): Float = defValue

    override fun getBoolean(
        key: String?,
        defValue: Boolean,
    ): Boolean = defValue

    override fun contains(key: String?): Boolean = false

    override fun edit(): SharedPreferences.Editor = SilentEditor

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
}

/** [SharedPreferences.Editor] no-op untuk [SilentSharedPreferences]. */
private object SilentEditor : SharedPreferences.Editor {
    override fun putString(
        key: String?,
        value: String?,
    ): SharedPreferences.Editor = this

    override fun putStringSet(
        key: String?,
        values: MutableSet<String>?,
    ): SharedPreferences.Editor = this

    override fun putInt(
        key: String?,
        value: Int,
    ): SharedPreferences.Editor = this

    override fun putLong(
        key: String?,
        value: Long,
    ): SharedPreferences.Editor = this

    override fun putFloat(
        key: String?,
        value: Float,
    ): SharedPreferences.Editor = this

    override fun putBoolean(
        key: String?,
        value: Boolean,
    ): SharedPreferences.Editor = this

    override fun remove(key: String?): SharedPreferences.Editor = this

    override fun clear(): SharedPreferences.Editor = this

    override fun commit(): Boolean = true

    override fun apply() = Unit
}
