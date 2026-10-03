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

package com.hyperexplorer.feature.tools.zip

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

class ZipEngineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val engine = ZipEngine(Dispatchers.Unconfined)

    @Test
    fun `zip roundtrip preserves nested files byte per byte`() =
        runBlocking {
            val sourceDir = tmp.newFolder("source")
            File(sourceDir, "a/b").mkdirs()
            File(sourceDir, "a/b/c.txt").writeText("hello")
            val randomBytes = Random(42).nextBytes(10_240)
            File(sourceDir, "d.txt").writeBytes(randomBytes)
            File(sourceDir, "berkas-ñ.txt").writeText("isi unicode ñ")
            val outputDir = tmp.newFolder("output")
            val targetZip = File(outputDir, "arsip.zip")
            val sources = sourceDir.listFiles().orEmpty().sortedBy { it.name }

            val zipResult = engine.zipFiles(sources, targetZip)

            assertTrue(zipResult.isSuccess)
            assertEquals(targetZip.absolutePath, zipResult.getOrNull()?.absolutePath)
            assertTrue(zipResult.getOrNull()?.exists() == true)

            File(sourceDir, "a").deleteRecursively()
            File(sourceDir, "d.txt").delete()
            File(sourceDir, "berkas-ñ.txt").delete()

            val extractDir = tmp.newFolder("extract")
            val unzipResult = engine.unzip(targetZip, extractDir)

            assertTrue(unzipResult.isSuccess)
            assertEquals(5, unzipResult.getOrNull())
            assertArrayEquals("hello".toByteArray(), File(extractDir, "a/b/c.txt").readBytes())
            assertArrayEquals(randomBytes, File(extractDir, "d.txt").readBytes())
            assertArrayEquals("isi unicode ñ".toByteArray(), File(extractDir, "berkas-ñ.txt").readBytes())
        }

    @Test
    fun `unzip rejects zip slip entry outside target dir`() =
        runBlocking {
            val zipDir = tmp.newFolder("zips")
            val zipFile = File(zipDir, "evil.zip")
            ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zip ->
                zip.putNextEntry(ZipEntry("../evil.txt"))
                zip.write("pwn".toByteArray())
                zip.closeEntry()
            }
            val targetDir = tmp.newFolder("target")

            val result = engine.unzip(zipFile, targetDir)

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is SecurityException)
            assertFalse(File(targetDir.parentFile, "evil.txt").exists())
            assertTrue(targetDir.listFiles().isNullOrEmpty())
        }
}
