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

package com.hyperexplorer.feature.sync.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hyperexplorer.data.cloud.CloudFileSystems
import com.hyperexplorer.feature.sync.engine.SyncEngine
import com.hyperexplorer.feature.sync.engine.SyncStats
import com.hyperexplorer.feature.sync.store.SyncPairStore
import com.hyperexplorer.feature.sync.store.SyncStatusStore
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Worker WorkManager yang mengeksekusi satu pasangan sinkronisasi
 * ([com.hyperexplorer.feature.sync.engine.SyncPair]) berdasarkan
 * [KEY_PAIR_ID] pada inputData.
 *
 * Alur defensif (semua cabang menghasilkan keputusan eksplisit):
 * - inputData tidak ada/tidak valid -> catat galat + `Result.failure()`;
 * - pair tidak ditemukan (mis. sudah dihapus) -> catat + `Result.failure()`;
 * - koneksi gagal -> catat + `Result.failure()`;
 * - engine sukses (meski berisi failure per-item) -> catat ringkasan + success;
 * - engine gagal (fail-fast) -> catat + retry bila [IOException] dan
 *   [runAttemptCount] < [MAX_RUN_ATTEMPTS], selain itu failure;
 * - koneksi selalu ditutup di `finally` (runCatching; close idempoten).
 */
class SyncWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    private val pairStore = SyncPairStore(applicationContext)
    private val statusStore = SyncStatusStore(applicationContext)
    private val engine = SyncEngine()

    override suspend fun doWork(): Result {
        val pairId = inputData.getLong(KEY_PAIR_ID, INVALID_PAIR_ID)
        if (pairId == INVALID_PAIR_ID) {
            statusStore.record(
                INVALID_PAIR_ID,
                ok = false,
                summary = "InputData worker tidak membawa pair_id yang valid",
                atEpochMs = now(),
            )
            return Result.failure()
        }
        val pair =
            pairStore.read().firstOrNull { it.id == pairId }
                ?: run {
                    statusStore.record(
                        pairId,
                        ok = false,
                        summary = "Pasangan sinkron #$pairId tidak ditemukan (mungkin sudah dihapus)",
                        atEpochMs = now(),
                    )
                    return Result.failure()
                }
        val fs =
            try {
                CloudFileSystems.connect(pair.connection)
            } catch (e: Exception) {
                statusStore.record(
                    pairId,
                    ok = false,
                    summary = "Gagal membuka koneksi: ${e.message ?: e.javaClass.simpleName} ${clock()}",
                    atEpochMs = now(),
                )
                return Result.failure()
            }
        return try {
            val outcome = engine.sync(File(pair.localPath), pair.remotePath, fs, pair.direction)
            if (outcome.isSuccess) {
                val stats: SyncStats = outcome.getOrDefault(SyncStats(0, 0, 0, 0, emptyList()))
                val failedNote = if (stats.failures.isEmpty()) "" else " [${stats.failures.size} gagal]"
                statusStore.record(
                    pairId,
                    ok = true,
                    summary = "OK ${stats.uploaded}↑ ${stats.downloaded}↓ ${stats.createdDirectories} dir$failedNote ${clock()}",
                    atEpochMs = now(),
                )
                Result.success()
            } else {
                val error = outcome.exceptionOrNull()
                if (error == null) {
                    // Tidak terjangkau untuk Result.isFailure == true; defensive saja.
                    statusStore.record(
                        pairId,
                        ok = false,
                        summary = "Sinkronisasi gagal tanpa penyebab yang jelas ${clock()}",
                        atEpochMs = now(),
                    )
                    Result.failure()
                } else {
                    statusStore.record(
                        pairId,
                        ok = false,
                        summary = "Gagal: ${error.message ?: error.javaClass.simpleName} ${clock()}",
                        atEpochMs = now(),
                    )
                    if (error is IOException && runAttemptCount < MAX_RUN_ATTEMPTS) Result.retry() else Result.failure()
                }
            }
        } finally {
            runCatching { fs.close() }
        }
    }

    private fun now(): Long = System.currentTimeMillis()

    /** Jam lokal "HH:mm" untuk ditampilkan pada ringkasan status. */
    private fun clock(): String {
        val text =
            runCatching {
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            }.getOrDefault("")
        return if (text.isEmpty()) "" else "pukul $text"
    }

    companion object {
        /** Kunci inputData berisi id [com.hyperexplorer.feature.sync.engine.SyncPair]. */
        const val KEY_PAIR_ID = "pair_id"

        /** Nilai penanda pairId tidak valid pada inputData. */
        const val INVALID_PAIR_ID = -1L

        /** Batas percobaan retry untuk kegagalan transien (IOException). */
        const val MAX_RUN_ATTEMPTS = 3
    }
}
