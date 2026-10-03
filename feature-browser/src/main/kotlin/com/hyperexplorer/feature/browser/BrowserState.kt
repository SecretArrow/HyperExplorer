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

package com.hyperexplorer.feature.browser

import com.hyperexplorer.core.model.FileNode
import com.hyperexplorer.data.local.FileOperations
import com.hyperexplorer.data.local.FileRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

data class UiState(
    val root: File,
    val current: File,
    val items: List<FileNode> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val selectionMode: Boolean = false,
    val selection: Set<String> = emptySet(),
)

data class Clipboard(val files: List<File>, val isCut: Boolean)

/**
 * State holder layar browser: navigasi folder, seleksi, recycle bin,
 * clipboard salin/potong, dan operasi berkas. Semua I/O berjalan di [ioDispatcher].
 */
class BrowserState(
    private val repo: FileRepository,
    root: File,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val _ui = MutableStateFlow(UiState(root = root, current = root))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _clipboard = MutableStateFlow<Clipboard?>(null)
    val clipboard: StateFlow<Clipboard?> = _clipboard.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val dir = _ui.value.current
        _ui.update { it.copy(loading = true, error = null) }
        scope.launch {
            val items =
                try {
                    repo.list(dir)
                } catch (t: Throwable) {
                    null
                }
            _ui.update { state ->
                if (items == null) {
                    state.copy(loading = false, items = emptyList(), error = "Tidak dapat membaca folder")
                } else {
                    state.copy(loading = false, items = items, error = null)
                }
            }
        }
    }

    fun open(node: FileNode) {
        val target = File(node.path)
        if (!target.isDirectory) return
        _ui.update { it.copy(current = target, selectionMode = false, selection = emptySet()) }
        refresh()
    }

    fun up() {
        val parent = _ui.value.current.parentFile ?: return
        _ui.update { it.copy(current = parent, selectionMode = false, selection = emptySet()) }
        refresh()
    }

    fun setSelectionMode(enabled: Boolean) {
        _ui.update { it.copy(selectionMode = enabled, selection = if (enabled) it.selection else emptySet()) }
    }

    fun toggleSelection(node: FileNode) {
        _ui.update { state ->
            val next = if (node.path in state.selection) state.selection - node.path else state.selection + node.path
            state.copy(selection = next, selectionMode = next.isNotEmpty() || state.selectionMode)
        }
    }

    fun clearSelection() {
        _ui.update { it.copy(selectionMode = false, selection = emptySet()) }
    }

    fun deleteSelected() {
        val files = selectedFiles()
        if (files.isEmpty()) return
        scope.launch {
            repo.moveToTrash(files)
            _ui.update { it.copy(selectionMode = false, selection = emptySet()) }
            refresh()
        }
    }

    fun copySelected() {
        val files = selectedFiles()
        if (files.isEmpty()) return
        _clipboard.value = Clipboard(files, isCut = false)
        clearSelection()
    }

    fun cutSelected() {
        val files = selectedFiles()
        if (files.isEmpty()) return
        _clipboard.value = Clipboard(files, isCut = true)
        clearSelection()
    }

    fun paste() {
        val clip = _clipboard.value ?: return
        val destination = _ui.value.current
        scope.launch {
            for (file in clip.files) {
                if (!file.exists()) continue
                try {
                    if (clip.isCut) {
                        FileOperations.move(file, destination)
                    } else {
                        FileOperations.copy(file, destination)
                    }
                } catch (e: IOException) {
                    // lanjutkan item berikutnya; pelaporan error UI menyusul
                }
            }
            if (clip.isCut) _clipboard.value = null
            refresh()
        }
    }

    fun createFolder(name: String) {
        val destination = _ui.value.current
        scope.launch {
            try {
                FileOperations.mkdir(destination, name)
            } catch (e: IOException) {
                // validasi & pesan error diserahkan ke lapisan UI berikutnya
            }
            refresh()
        }
    }

    private fun selectedFiles(): List<File> = _ui.value.selection.map { File(it) }.filter { it.exists() }
}
