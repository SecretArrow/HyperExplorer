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

package com.hyperexplorer.data.local

import com.hyperexplorer.core.common.PathUtils
import com.hyperexplorer.core.model.FileNode
import java.io.File
import java.io.IOException
import java.util.Properties

/**
 * Repositori berkas lokal: listing terurut, recycle bin dengan metadata
 * path asli (bisa dipulihkan), dan statistik penyimpanan.
 *
 * Catatan desain: sengaja bekerja di atas java.io.File (mode "File Manager Penuh"
 * dengan MANAGE_EXTERNAL_STORAGE). Dukungan SAF/MediaStore adalah kelas lain
 * yang mengimplementasikan kontrak serupa.
 */
class FileRepository(private val trashDir: File) {
    init {
        if (!trashDir.exists()) trashDir.mkdirs()
    }

    /** Daftar anak [dir]; folder dulu, lalu nama A-Z (case-insensitive). */
    fun list(dir: File): List<FileNode> {
        val children = dir.listFiles() ?: return emptyList()
        return children
            .map { FileNode.from(it) }
            .sortedWith(
                compareByDescending<FileNode> { it.isDirectory }
                    .thenBy { it.name.lowercase() },
            )
    }

    fun node(file: File): FileNode = FileNode.from(file)

    fun listTrash(): List<FileNode> = list(trashDir).filter { !it.name.endsWith(META_SUFFIX) }

    /**
     * Pindahkan berkas ke recycle bin. Menyimpan path asli di berkas metadata
     * tersembunyi agar bisa dipulihkan. Mengembalikan jumlah item yang berhasil.
     */
    fun moveToTrash(files: List<File>): Int {
        var count = 0
        for (file in files) {
            if (!file.exists()) continue
            val entryName = PathUtils.uniqueName(trashDir, file.name)
            val entry = File(trashDir, entryName)
            val moved =
                try {
                    if (file.renameTo(entry)) {
                        writeMeta(entry, file.absolutePath)
                        true
                    } else {
                        if (file.isDirectory) {
                            FileOperations.copyDirectory(file, entry)
                        } else {
                            FileOperations.copyFile(file, entry)
                        }
                        writeMeta(entry, file.absolutePath)
                        file.deleteRecursively()
                        true
                    }
                } catch (e: IOException) {
                    false
                }
            if (moved) count++
        }
        return count
    }

    /** Pulihkan entri recycle bin ke path aslinya. */
    fun restore(entry: File): Boolean {
        val originalPath = readMeta(entry) ?: return false
        val target = File(originalPath)
        val parent = target.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        if (target.exists()) return false
        val moved =
            try {
                if (entry.renameTo(target)) {
                    true
                } else {
                    if (entry.isDirectory) {
                        FileOperations.copyDirectory(entry, target)
                        entry.deleteRecursively()
                        true
                    } else {
                        FileOperations.copyFile(entry, target)
                        entry.delete()
                        true
                    }
                }
            } catch (e: IOException) {
                false
            }
        if (moved) deleteMeta(entry)
        return moved
    }

    fun emptyTrash(): Int {
        var count = 0
        trashDir.listFiles()?.forEach { entry ->
            if (entry.name.endsWith(META_SUFFIX) || entry.name.startsWith(".$META_SUFFIX")) return@forEach
            if (entry.deleteRecursively()) {
                deleteMeta(entry)
                count++
            }
        }
        return count
    }

    fun storageStats(root: File): Pair<Long, Long> = Pair(root.usableSpace, root.totalSpace)

    private fun metaFor(entry: File): File =
        if (entry.isDirectory) File(entry, META_SUFFIX) else File(entry.parentFile, "." + entry.name + META_SUFFIX)

    private fun writeMeta(
        entry: File,
        originalPath: String,
    ) {
        val props = Properties()
        props.setProperty(KEY_ORIGINAL, originalPath)
        metaFor(entry).outputStream().use { out -> props.store(out, "Hyper Explorer trash metadata") }
    }

    private fun readMeta(entry: File): String? {
        val meta = metaFor(entry)
        if (!meta.exists()) return null
        val props = Properties()
        meta.inputStream().use { input -> props.load(input) }
        return props.getProperty(KEY_ORIGINAL)
    }

    private fun deleteMeta(entry: File) {
        metaFor(entry).delete()
    }

    companion object {
        private const val META_SUFFIX = ".trashmeta"
        private const val KEY_ORIGINAL = "original.path"
    }
}
