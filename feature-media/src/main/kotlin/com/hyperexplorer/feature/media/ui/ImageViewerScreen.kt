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

package com.hyperexplorer.feature.media.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import com.hyperexplorer.core.ui.components.EmptyState
import com.hyperexplorer.feature.media.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Sisi terpanjang hasil dekode tidak melebihi nilai ini (hemat memori untuk foto besar). */
private const val MAX_DECODED_DIMENSION_PX = 2048

/**
 * Viewer gambar layar penuh dengan latar hitam. Dekode memakai BitmapFactory
 * (tanpa pustaka pihak ketiga) dan menurunkan resolusi via inSampleSize.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(
    path: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var bitmap by remember(path) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(path) { mutableStateOf(true) }

    LaunchedEffect(path) {
        val decoded = withContext(Dispatchers.IO) { decodeScaledBitmap(path) }
        bitmap = decoded
        loading = false
    }

    Scaffold(
        modifier = modifier,
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text(text = path.substringAfterLast('/')) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.media_cd_close))
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Black,
                        titleContentColor = Color.White,
                        navigationIconContentColor = Color.White,
                    ),
            )
        },
    ) { padding ->
        when {
            loading ->
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            bitmap == null -> EmptyState(message = stringResource(R.string.media_error_load_image), modifier = Modifier.padding(padding))
            else -> {
                val decoded = bitmap
                if (decoded != null) {
                    Image(
                        bitmap = decoded.asImageBitmap(),
                        contentDescription = path.substringAfterLast('/'),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(padding),
                    )
                }
            }
        }
    }
}

/** Dekode gambar dengan inSampleSize pangkat dua terkecil agar sisi terpanjang <= 2048 px. */
private fun decodeScaledBitmap(path: String): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    val longestSide = maxOf(bounds.outWidth, bounds.outHeight)
    while (longestSide / sampleSize > MAX_DECODED_DIMENSION_PX) {
        sampleSize *= 2
    }

    val options =
        BitmapFactory.Options().apply {
            inSampleSize = sampleSize
        }
    return BitmapFactory.decodeFile(path, options)
}
