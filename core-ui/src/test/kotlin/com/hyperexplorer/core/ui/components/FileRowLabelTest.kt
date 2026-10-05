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

package com.hyperexplorer.core.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uji [buildFileRowLabel] murni JVM: gabungan "typeWord, name" (+ ", subtitle" bila tidak blank),
 * pembuangan komponen blank/whitespace-only, fallback hardcoded "Folder"/"File" bila semua blank,
 * urutan komponen, koma di dalam nama dibiarkan apa adanya, dan invarian hasil tidak pernah blank.
 */
class FileRowLabelTest {
    @Test
    fun `folder dgn nama dan subtitle null menghasilkan typeWord lalu nama`() {
        val result = buildFileRowLabel(isDirectory = true, name = "Dokumen", subtitle = null, typeWord = "Folder")

        assertEquals("folder tanpa subtitle harus 'Folder, Dokumen', aktual: $result", "Folder, Dokumen", result)
    }

    @Test
    fun `berkas dgn nama dan subtitle null menghasilkan typeWord lalu nama`() {
        val result = buildFileRowLabel(isDirectory = false, name = "catatan.txt", subtitle = null, typeWord = "File")

        assertEquals("berkas tanpa subtitle harus 'File, catatan.txt', aktual: $result", "File, catatan.txt", result)
    }

    @Test
    fun `subtitle berisi ditambahkan setelah nama`() {
        val result = buildFileRowLabel(isDirectory = false, name = "arsip.zip", subtitle = "1,2 MB", typeWord = "File")

        assertEquals(
            "subtitle tidak blank harus ditambahkan setelah nama dgn pemisah koma, aktual: $result",
            "File, arsip.zip, 1,2 MB",
            result,
        )
    }

    @Test
    fun `subtitle string kosong diabaikan`() {
        val result = buildFileRowLabel(isDirectory = true, name = "Dokumen", subtitle = "", typeWord = "Folder")

        assertEquals("subtitle kosong tidak boleh ikut digabung, aktual: $result", "Folder, Dokumen", result)
    }

    @Test
    fun `subtitle whitespace saja diabaikan`() {
        val result = buildFileRowLabel(isDirectory = true, name = "Dokumen", subtitle = "   \t ", typeWord = "Folder")

        assertEquals("subtitle whitespace-only tidak boleh ikut digabung, aktual: $result", "Folder, Dokumen", result)
    }

    @Test
    fun `nama kosong diabaikan menyisakan typeWord dan subtitle`() {
        val result = buildFileRowLabel(isDirectory = false, name = "", subtitle = "2 MB", typeWord = "File")

        assertEquals("nama kosong harus dibuang, sisanya tetap digabung, aktual: $result", "File, 2 MB", result)
    }

    @Test
    fun `nama whitespace saja diabaikan menyisakan typeWord dan subtitle`() {
        val result = buildFileRowLabel(isDirectory = true, name = "   ", subtitle = "12 item", typeWord = "Folder")

        assertEquals("nama whitespace-only harus dibuang, sisanya tetap digabung, aktual: $result", "Folder, 12 item", result)
    }

    @Test
    fun `typeWord blank diabaikan menyisakan nama dan subtitle`() {
        val result = buildFileRowLabel(isDirectory = false, name = "Dokumen", subtitle = "12 item", typeWord = "  ")

        assertEquals("typeWord blank harus dibuang tanpa fallback, aktual: $result", "Dokumen, 12 item", result)
    }

    @Test
    fun `semua komponen blank folder menghasilkan fallback Folder`() {
        val result = buildFileRowLabel(isDirectory = true, name = "", subtitle = "", typeWord = "")
        val resultWhitespace = buildFileRowLabel(isDirectory = true, name = " ", subtitle = " ", typeWord = " ")

        assertEquals(
            "semua blank pada folder harus fallback hardcoded 'Folder', aktual: '$result' / '$resultWhitespace'",
            "Folder",
            result,
        )
        assertEquals("semua whitespace pada folder juga fallback 'Folder', aktual: $resultWhitespace", "Folder", resultWhitespace)
    }

    @Test
    fun `semua komponen blank berkas menghasilkan fallback File`() {
        val result = buildFileRowLabel(isDirectory = false, name = "", subtitle = null, typeWord = "")

        assertEquals("semua blank pada berkas harus fallback hardcoded 'File', aktual: $result", "File", result)
    }

    @Test
    fun `urutan komponen typeWord lalu nama lalu subtitle`() {
        val result = buildFileRowLabel(isDirectory = true, name = "N", subtitle = "S", typeWord = "T")

        assertEquals("urutan gabungan wajib typeWord, nama, subtitle, aktual: $result", "T, N, S", result)
    }

    @Test
    fun `nama mengandung koma digabung apa adanya`() {
        val result = buildFileRowLabel(isDirectory = false, name = "laporan, final.txt", subtitle = "2 MB", typeWord = "File")

        assertEquals(
            "koma di dalam nama tidak di-escape/di-quote, digabung apa adanya, aktual: $result",
            "File, laporan, final.txt, 2 MB",
            result,
        )
    }

    @Test
    fun `hanya subtitle tersisa menghasilkan subtitle tanpa koma`() {
        val result = buildFileRowLabel(isDirectory = true, name = "", subtitle = "1 MB", typeWord = " ")

        assertEquals("satu komponen tersisa tidak boleh mengandung koma pemisah, aktual: $result", "1 MB", result)
    }

    @Test
    fun `hasil tidak pernah blank pada semua kombinasi ekstrem`() {
        for (isDirectory in listOf(true, false)) {
            for (name in listOf("", " ", "\t", "berkas.txt")) {
                for (subtitle in listOf(null, "", "  ", "1 MB")) {
                    for (typeWord in listOf("", "   ", "Folder")) {
                        val result = buildFileRowLabel(isDirectory = isDirectory, name = name, subtitle = subtitle, typeWord = typeWord)

                        assertTrue(
                            "hasil tidak boleh blank utk ($isDirectory, '$name', '$subtitle', '$typeWord'), aktual: '$result'",
                            result.isNotBlank(),
                        )
                        assertTrue(
                            "hasil tidak boleh diawali ', ' utk ($isDirectory, '$name', '$subtitle', '$typeWord'), aktual: '$result'",
                            !result.startsWith(", "),
                        )
                        assertTrue(
                            "hasil tidak boleh diakhiri ', ' utk ($isDirectory, '$name', '$subtitle', '$typeWord'), aktual: '$result'",
                            !result.endsWith(", "),
                        )
                        assertTrue(
                            "hasil tidak boleh mengandung ', ,' utk ($isDirectory, '$name', '$subtitle', '$typeWord'), aktual: '$result'",
                            !result.contains(", ,"),
                        )
                    }
                }
            }
        }
    }
}
