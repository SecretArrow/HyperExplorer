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

package com.hyperexplorer.app.widget

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.hyperexplorer.app.MainActivity
import com.hyperexplorer.app.R
import com.hyperexplorer.core.common.favorites.FavoriteFoldersCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Nama SharedPreferences yang menyimpan daftar path folder favorit milik widget. */
private const val PREFS_NAME = "widget_favorites"

/** Kunci di dalam [PREFS_NAME] yang menyimpan daftar path favorit hasil encode. */
private const val PREFS_KEY_PATHS = "paths"

/** Tag Log untuk pesan peringatan defensif milik file ini. */
private const val TAG = "QuickAccessWidget"

/**
 * Penerima widget "Akses cepat": menampilkan hingga [QuickAccessWidget.WIDGET_MAX_ITEMS] folder
 * favorit yang disematkan dari penjelajah berkas; setiap baris membuka [MainActivity] langsung
 * ke folder tersebut. Widget tidak berlangganan pembaruan periodik (updatePeriodMillis = 0);
 * pembaruan dipicu manual via [QuickAccessWidget.pushUpdate] saat daftar favorit berubah.
 */
class QuickAccessWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickAccessWidget

    /**
     * Widget terakhir dilepas dari layar utama: bersihkan seluruh isi prefs favorit agar tidak
     * ada data sisa. Defensif: kegagalan I/O preferensi tidak boleh menghentikan receiver.
     */
    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        try {
            context
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply()
        } catch (t: Throwable) {
            Log.w(
                TAG,
                "onDisabled: gagal membersihkan SharedPreferences '$PREFS_NAME' setelah widget " +
                    "terakhir dilepas; sisa data favorit mungkin tetap tersimpan di perangkat.",
                t,
            )
        }
    }
}

/**
 * Tampilan widget "Akses cepat". Membaca daftar path favorit dari SharedPreferences
 * [PREFS_NAME]; kegagalan baca/decode selalu jatuh ke daftar kosong sehingga widget
 * menampilkan pesan "kosong", tidak pernah melempar keluar dari provideGlance.
 */
object QuickAccessWidget : GlanceAppWidget() {
    /** Batas tampilan widget: hanya 4 folder pertama dari daftar favorit; sisanya diabaikan. */
    const val WIDGET_MAX_ITEMS = 4

    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        val paths = loadPaths(context)
        val headerLabel = context.getString(R.string.widget_quick_access_label)
        val emptyMessage = context.getString(R.string.widget_quick_access_empty)
        provideContent { Content(context, paths, headerLabel, emptyMessage) }
    }

    /**
     * Memicu pembaruan seluruh instance widget; dipanggil pihak lain setelah daftar favorit
     * berubah. I/O berjalan di Dispatchers.IO dan kegagalan hanya dicatat (Log.w), tidak
     * dilempar ke pemanggil — widget paling banter menampilkan data lama sampai update berikut.
     */
    suspend fun pushUpdate(context: Context) {
        withContext(Dispatchers.IO) {
            try {
                updateAll(context.applicationContext)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                Log.w(
                    TAG,
                    "pushUpdate: gagal memperbarui widget (AppWidgetManager tidak tersedia atau " +
                        "sistem menolak update); widget tetap menampilkan data lama.",
                    t,
                )
            }
        }
    }

    /**
     * Membaca daftar path favorit untuk ditampilkan. Selalu mengembalikan daftar (mungkin
     * kosong) dan tidak pernah melempar: prefs hilang/berisi null/blank -> kosong; baca atau
     * decode gagal -> Log.w lalu kosong; hasil decode dibatasi [WIDGET_MAX_ITEMS] dan path
     * blank ditolak.
     */
    private fun loadPaths(context: Context): List<String> =
        try {
            val raw =
                context
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(PREFS_KEY_PATHS, null)
            if (raw.isNullOrBlank()) {
                emptyList()
            } else {
                FavoriteFoldersCodec
                    .decode(raw, WIDGET_MAX_ITEMS)
                    .filter { it.isNotBlank() }
                    .take(WIDGET_MAX_ITEMS)
            }
        } catch (t: Throwable) {
            Log.w(
                TAG,
                "loadPaths: gagal membaca/meng-decode favorit dari SharedPreferences " +
                    "'$PREFS_NAME' (kunci '$PREFS_KEY_PATHS'); widget menampilkan pesan kosong.",
                t,
            )
            emptyList()
        }

    /** Isi widget: header + satu baris per folder favorit, atau pesan kosong non-clickable. */
    @Composable
    private fun Content(
        context: Context,
        paths: List<String>,
        headerLabel: String,
        emptyMessage: String,
    ) {
        GlanceTheme {
            Column(modifier = GlanceModifier.fillMaxSize().padding(8.dp)) {
                Text(
                    text = headerLabel,
                    style =
                        TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontWeight = FontWeight.Bold,
                        ),
                    maxLines = 1,
                )
                if (paths.isEmpty()) {
                    Text(
                        text = emptyMessage,
                        style = TextStyle(color = GlanceTheme.colors.onSurface),
                    )
                } else {
                    for (path in paths) {
                        FolderRow(context, path)
                    }
                }
            }
        }
    }

    /** Satu baris folder: nama tampilan + klik membuka MainActivity langsung ke folder tsb. */
    @Composable
    private fun FolderRow(
        context: Context,
        path: String,
    ) {
        Row(
            modifier =
                GlanceModifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable(actionStartActivity(intentOpen(context, path))),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = displayName(path),
                style = TextStyle(color = GlanceTheme.colors.onSurface),
                maxLines = 1,
            )
        }
    }

    /** Nama tampilan folder: segmen terakhir path; bila blank (defensif) pakai path utuh. */
    private fun displayName(path: String): String {
        val name = File(path).name
        return if (name.isBlank()) path else name
    }

    /** Intent eksplisit ke [MainActivity] membawa [MainActivity.EXTRA_OPEN_PATH] (singleTop). */
    private fun intentOpen(
        context: Context,
        path: String,
    ): Intent =
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_PATH, path)
            .setFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
}
