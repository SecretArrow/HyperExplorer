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

package com.hyperexplorer.feature.apps

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Operasi terhadap aplikasi terpasang: daftar, buka, cadangkan APK, dan minta hapus.
 * Fungsi murni ([sortApps], [sanitizeApkFileName]) sengaja dipisah agar bisa diuji di JVM.
 */
object AppManager {
    private const val BUFFER_SIZE_BYTES = 8 * 1024
    private val ILLEGAL_FILE_NAME_CHARS = Regex("[^A-Za-z0-9._-]")

    /**
     * Daftar aplikasi yang punya launcher activity, terurut [sortApps].
     * Tidak membutuhkan izin apa pun.
     */
    fun launchableApps(pm: PackageManager): List<AppEntry> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(intent, 0)
        val apps =
            resolved.mapNotNull { ri ->
                val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg.isBlank()) return@mapNotNull null
                val appInfo = ri.activityInfo?.applicationInfo
                val info = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull()
                val apkPath = appInfo?.sourceDir ?: ""
                AppEntry(
                    label = ri.loadLabel(pm).toString(),
                    packageName = pkg,
                    versionName = info?.versionName ?: "",
                    apkPath = apkPath,
                    sizeBytes = File(apkPath).length(),
                    installedAtMillis = info?.firstInstallTime ?: 0L,
                    isSystem = ((appInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM) != 0,
                )
            }
        return sortApps(apps)
    }

    /**
     * Urutan tampil: aplikasi pengguna dulu, lalu aplikasi sistem di paling belakang;
     * masing-masing diurutkan berdasarkan label (case-insensitive, locale.ROOT).
     */
    fun sortApps(apps: List<AppEntry>): List<AppEntry> =
        apps.sortedWith(
            compareBy({ it.isSystem }, { it.label.lowercase(Locale.ROOT) }),
        )

    /**
     * Nama berkas APK yang aman: "<label>-<version>.apk" (atau "<label>.apk" bila versi kosong).
     * Karakter di luar [A-Za-z0-9._-] diganti "_", lalu titik/spasi di ujung tiap bagian dibuang
     * sehingga hasil tidak berujung titik dan tidak menjadi berkas tersembunyi.
     */
    fun sanitizeApkFileName(label: String, versionName: String): String {
        val safeLabel = label.replace(ILLEGAL_FILE_NAME_CHARS, "_").trim { it == '.' || it == ' ' }
        val safeVersion = versionName.replace(ILLEGAL_FILE_NAME_CHARS, "_").trim { it == '.' || it == ' ' }
        val base = if (versionName.isEmpty()) safeLabel else "$safeLabel-$safeVersion"
        return "$base.apk"
    }

    /** Membuka aplikasi lewat launch intent-nya. Return false bila tidak ada intent atau startActivity gagal. */
    fun launch(context: Context, packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /** Meminta sistem menampilkan dialog uninstal (bukan menghapus langsung). Return false bila gagal. */
    fun uninstall(context: Context, packageName: String): Boolean {
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /**
     * Menyalin APK sumber ke [targetDir] dengan nama dari [sanitizeApkFileName].
     * Gagal (Result.failure) bila apkPath kosong atau terjadi IOException.
     */
    suspend fun backupApk(
        entry: AppEntry,
        targetDir: File,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): Result<File> =
        withContext(dispatcher) {
            runCatching {
                if (entry.apkPath.isBlank()) throw IOException("APK sumber tidak diketahui")
                targetDir.mkdirs()
                val target = File(targetDir, sanitizeApkFileName(entry.label, entry.versionName))
                File(entry.apkPath).inputStream().use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output, BUFFER_SIZE_BYTES)
                    }
                }
                target
            }
        }
}
