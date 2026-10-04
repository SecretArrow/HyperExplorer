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
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.hyperexplorer.feature.sync.engine.SyncPair
import com.hyperexplorer.feature.sync.store.SyncStatusStore
import java.util.concurrent.TimeUnit

/**
 * Pendaftar jadwal WorkManager untuk pasangan sinkronisasi.
 *
 * - [schedule]: periodic unik per pasangan (nama `hyper-sync-<id>`), policy
 *   KEEP agar perubahan yang tidak relevan tidak me-reset periode berjalan;
 *   syarat: jaringan terhubung + penyimpanan tidak hampir penuh.
 * - [cancel]: batalkan periodic unik + hapus status terakhir pasangan.
 * - [runNow]: eksekusi sekali langsung (nama `hyper-sync-now-<id>`), policy
 *   REPLACE agar menekan ulang saat sync manual ditrigger berulang.
 */
object SyncScheduler {
    /** Tag umum seluruh pekerjaan sinkronisasi (mudah difilter/debug). */
    const val TAG_SYNC = "hyper-sync"

    /** Prefix nama pekerjaan periodic per pasangan. */
    const val UNIQUE_PREFIX = "hyper-sync-"

    /** Prefix nama pekerjaan sekali-jalan per pasangan. */
    const val UNIQUE_NOW_PREFIX = "hyper-sync-now-"

    /**
     * Jadwalkan periodic sync untuk [pair]. Interval di-clamp ke >= 15 menit
     * (floor WorkManager) sebagai pengaman meski store sudah menjaminnya.
     */
    fun schedule(
        context: Context,
        pair: SyncPair,
    ) {
        val intervalMinutes = pair.intervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES)
        val request =
            PeriodicWorkRequestBuilder<SyncWorker>(intervalMinutes.toLong(), TimeUnit.MINUTES)
                .setConstraints(
                    Constraints
                        .Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresStorageNotLow(true)
                        .build(),
                ).setInputData(workDataOf(SyncWorker.KEY_PAIR_ID to pair.id))
                .addTag(TAG_SYNC)
                .build()
        WorkManager
            .getInstance(context)
            .enqueueUniquePeriodicWork("$UNIQUE_PREFIX${pair.id}", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Batalkan jadwal pasangan [pairId] dan bersihkan status tersimpannya. */
    fun cancel(
        context: Context,
        pairId: Long,
    ) {
        WorkManager.getInstance(context).cancelUniqueWork("$UNIQUE_PREFIX$pairId")
        SyncStatusStore(context).clear(pairId)
    }

    /** Jalankan sinkronisasi [pair] sekarang juga (sekali jalan, REPLACE). */
    fun runNow(
        context: Context,
        pair: SyncPair,
    ) {
        val request =
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(workDataOf(SyncWorker.KEY_PAIR_ID to pair.id))
                .addTag(TAG_SYNC)
                .build()
        WorkManager
            .getInstance(context)
            .enqueueUniqueWork("$UNIQUE_NOW_PREFIX${pair.id}", ExistingWorkPolicy.REPLACE, request)
    }

    private const val MIN_INTERVAL_MINUTES = 15
}
