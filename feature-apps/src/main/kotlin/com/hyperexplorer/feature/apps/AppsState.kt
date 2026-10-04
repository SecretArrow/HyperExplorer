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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Jenis galat layar aplikasi; teksnya dipetakan ke resource string di lapisan UI. */
enum class AppsError {
    /** Daftar aplikasi tidak dapat dimuat. */
    CANNOT_LOAD_LIST,
}

data class AppsUi(
    val loading: Boolean = true,
    val error: AppsError? = null,
    val apps: List<AppEntry> = emptyList(),
)

/**
 * State holder layar aplikasi: memuat daftar aplikasi terpasang di dispatcher I/O.
 * Pola sama dengan BrowserState (scope sendiri + StateFlow + update).
 */
class AppsState(
    private val context: Context,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _ui = MutableStateFlow(AppsUi())
    val ui: StateFlow<AppsUi> = _ui.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _ui.update { it.copy(loading = true, error = null) }
        scope.launch {
            val apps =
                try {
                    AppManager.launchableApps(context.packageManager)
                } catch (t: Throwable) {
                    null
                }
            _ui.update { state ->
                if (apps == null) {
                    state.copy(loading = false, apps = emptyList(), error = AppsError.CANNOT_LOAD_LIST)
                } else {
                    state.copy(loading = false, apps = apps, error = null)
                }
            }
        }
    }
}
