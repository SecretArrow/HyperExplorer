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

package com.hyperexplorer.feature.media.player

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.hyperexplorer.feature.media.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File
import java.util.Locale

/** Interval polling posisi pemutar (audio tidak punya callback posisi periodik). */
private const val POSITION_POLL_INTERVAL_MS = 500L

/**
 * Layar pemutar media layar penuh berbasis Media3 ExoPlayer untuk varian
 * Video maupun Audio dari sealed interface ViewerRoute (package viewer).
 *
 * Perilaku ekshaustif:
 * - Guard awal: [path] blank ATAU berkas tidak ada -> panel galat layar penuh
 *   ([R.string.player_error_missing] + nama berkas) TANPA inisialisasi player.
 * - [isVideo] = true -> [PlayerView] mengisi layar dengan latar hitam dan kontrol bawaan.
 * - [isVideo] = false -> panel kontrol audio: ikon not, nama berkas, Slider posisi
 *   (disabled bila durasi <= 0), teks "m:ss / m:ss", tombol putar/jeda, tombol tutup.
 * - Galat pemutaran -> panel galat berisi [R.string.player_error_generic] + kode galat
 *   (tanpa stack trace/URL) dan tombol tutup; player dirilis via onDispose.
 * - Buffering -> LinearProgressIndicator tak tentu (video: overlay bawah; audio: atas konten).
 * - STATE_ENDED pada audio -> tombol kembali ke PlayArrow (isPlaying otomatis false).
 * - STATE_IDLE tanpa galat -> tampilan netral (tanpa indikator buffering).
 * - ON_PAUSE siklus hidup -> video dijeda; audio dibiarkan terus berjalan.
 * - Player dirilis saat layar ditutup; tidak ada toast/log dan tidak ada state
 *   yang disimpan ke berkas.
 */
@Composable
fun MediaPlayerScreen(
    path: String,
    isVideo: Boolean,
    onClose: () -> Unit,
) {
    // 1) Guard awal: jangan sentuh ExoPlayer sebelum berkas terbukti ada.
    val missingFile = remember(path) { path.isBlank() || !File(path).isFile }
    if (missingFile) {
        MediaPlayerErrorPanel(
            message = stringResource(R.string.player_error_missing),
            detail = path.substringAfterLast('/'),
            onClose = onClose,
        )
        return
    }

    val context = LocalContext.current
    val player =
        remember(path) {
            ExoPlayer.Builder(context)
                .build()
                .apply {
                    setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
                    prepare()
                    playWhenReady = true
                }
        }

    // 2) State lokal yang di-update lewat Player.Listener (callback dari thread utama).
    var isPlaying by remember(path) { mutableStateOf(false) }
    var playbackState by remember(path) { mutableIntStateOf(Player.STATE_IDLE) }
    var errorText by remember(path) { mutableStateOf<String?>(null) }
    var positionMs by remember(path) { mutableLongStateOf(0L) }
    var durationMs by remember(path) { mutableLongStateOf(0L) }
    val genericErrorText = stringResource(R.string.player_error_generic)

    // 3) Listener + rilis player digabung dalam satu DisposableEffect agar tidak bocor.
    DisposableEffect(player) {
        val listener =
            object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    playbackState = state
                }

                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }

                override fun onPlayerError(error: PlaybackException) {
                    // Pesan ringkas: teks generik + nama kode galat, tanpa stack/URL.
                    errorText = "$genericErrorText (${error.errorCodeName})"
                }
            }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // 4) Siklus hidup aplikasi: ON_PAUSE -> video dijeda, audio tetap berjalan.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, isVideo) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE && isVideo) {
                    player.pause()
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 5) Polling posisi (audio saja) agar Slider mengikuti pemutar; guard IllegalStateException
    //    untuk kasus player sudah dilepas -> posisi/durasi dianggap nol.
    if (!isVideo) {
        LaunchedEffect(player) {
            while (isActive) {
                delay(POSITION_POLL_INTERVAL_MS)
                try {
                    positionMs = player.currentPosition
                    durationMs = sanitizeDuration(player.duration)
                } catch (illegalState: IllegalStateException) {
                    positionMs = 0L
                    durationMs = 0L
                }
            }
        }
    }

    // 6) Galat pemutaran -> panel galat layar penuh; onClose melepas player via onDispose.
    val currentError = errorText
    if (currentError != null) {
        MediaPlayerErrorPanel(
            message = currentError,
            detail = path.substringAfterLast('/'),
            onClose = onClose,
        )
        return
    }

    val closeLabel = stringResource(R.string.player_close)
    val bufferingLabel = stringResource(R.string.player_buffering)
    val fileName = path.substringAfterLast('/')

    if (isVideo) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        this.player = player
                        useController = true
                    }
                },
                update = { view -> view.player = player },
                modifier = Modifier.fillMaxSize(),
            )
            if (playbackState == Player.STATE_BUFFERING) {
                LinearProgressIndicator(
                    modifier =
                        Modifier.fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .semantics { contentDescription = bufferingLabel },
                )
            }
        }
    } else {
        // 7) Panel kontrol audio; scrubPositionMs null = tidak sedang menggeser Slider.
        var scrubPositionMs by remember(path) { mutableStateOf<Long?>(null) }
        val shownPositionMs = scrubPositionMs ?: positionMs
        val playPauseLabel = stringResource(if (isPlaying) R.string.player_pause else R.string.player_play)

        Column(
            modifier =
                Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (playbackState == Player.STATE_BUFFERING) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = bufferingLabel },
                )
                Spacer(modifier = Modifier.height(24.dp))
            }
            Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(96.dp),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = fileName,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "${formatMs(shownPositionMs)} / ${formatMs(durationMs)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Slider(
                value = shownPositionMs.toFloat(),
                onValueChange = { newValue -> scrubPositionMs = newValue.toLong() },
                onValueChangeFinished = {
                    val target = scrubPositionMs
                    if (target != null) {
                        player.seekTo(target)
                        positionMs = target
                    }
                    scrubPositionMs = null
                },
                valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
                enabled = durationMs > 0L,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { player.playWhenReady = !player.playWhenReady }) {
                    Icon(
                        imageVector =
                            when {
                                isPlaying -> Icons.Filled.Pause
                                else -> Icons.Filled.PlayArrow
                            },
                        contentDescription = playPauseLabel,
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                IconButton(onClick = onClose) {
                    Icon(imageVector = Icons.Filled.Close, contentDescription = closeLabel)
                }
            }
        }
    }
}

/**
 * Panel galat layar penuh: ikon error, pesan, detail opsional (mis. nama berkas),
 * dan tombol tutup. Dipakai untuk berkas hilang maupun galat pemutaran.
 */
@Composable
private fun MediaPlayerErrorPanel(
    message: String,
    detail: String?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(48.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        if (detail != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onClose) {
            Text(text = stringResource(R.string.player_close))
        }
    }
}

/**
 * Memformat milidetik menjadi "m:ss" (mis. 65000 -> "1:05").
 * Nilai negatif (tak terduga) di-guard menjadi "0:00".
 */
private fun formatMs(milliseconds: Long): String {
    if (milliseconds < 0L) return "0:00"
    val totalSeconds = milliseconds / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
}

/**
 * Normalisasi durasi pemutar: nilai negatif termasuk konstanta TIME_UNSET
 * (durasi belum diketahui) dipetakan ke 0 agar guard durasi <= 0
 * (Slider disabled) bekerja konsisten.
 */
private fun sanitizeDuration(durationMs: Long): Long = if (durationMs < 0L) 0L else durationMs
