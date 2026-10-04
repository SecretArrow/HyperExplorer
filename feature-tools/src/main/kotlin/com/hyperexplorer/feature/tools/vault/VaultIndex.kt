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

package com.hyperexplorer.feature.tools.vault

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.UTFDataFormatException

/**
 * Sinyal internal bahwa berkas indeks tidak dapat di-parse. Hanya bergerak di
 * dalam modul ini; [VaultEngine] yang memetakannya menjadi
 * [VaultError.IndexCorrupt] sehingga tidak pernah bocor sebagai perilaku publik.
 */
internal class VaultIndexFormatException(detail: String) : Exception(detail)

/**
 * Codec berkas indeks biner `index.bin` milik vault.
 *
 * **Format `index.bin`** (big-endian, via [DataOutputStream]/[DataInputStream]):
 *
 * ```
 * magic "HVIX" (4 B) | versi u8 = 1 | i32 count (0..100_000)
 * per entri (count kali):
 *   u16-len UTF string id | u16-len UTF string storedFileName
 *   u16-len UTF string originalName | u16-len UTF string originalPath
 *   u16-len UTF string mimeType | i64 sizeBytes | i64 addedAtEpochMs | u8 wrapMethod
 * ```
 *
 * String memakai `writeUTF`/`readUTF` standar (panjang u16 dalam modified UTF-8;
 * string kosong valid). Byte sisa setelah `count` entri = format error. Penulisan
 * selalu atomik: tulis `index.bin.tmp` → `fd.sync()` → cadangkan `index.bin` ke
 * `index.bin.bak` → rename tmp menjadi `index.bin`.
 */
internal object VaultIndex {
    internal const val INDEX_FILE_NAME = "index.bin"
    internal const val BACKUP_SUFFIX = ".bak"
    private const val TEMP_SUFFIX = ".tmp"
    private const val MAGIC = "HVIX"
    private const val VERSION = 1
    private const val MAX_ENTRIES = 100_000

    /** Batas byte `writeUTF` (u16); string yang lebih panjang = format error. */
    private const val MAX_UTF_CHARS = 65_535

    /**
     * Membaca seluruh entri dari [file].
     *
     * @throws VaultIndexFormatException bila struktur berkas tidak sesuai format
     * (magic/versi/count salah, terpotong, ada byte sisa, atau wrap method tak dikenal).
     * @throws IOException bila pembacaan tingkat sistem berkas gagal.
     */
    internal fun read(file: File): List<VaultEntry> {
        if (!file.exists()) {
            throw FileNotFoundException("indeks tidak ditemukan: ${file.path}")
        }
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(file), VaultFormat.STREAM_BUFFER_SIZE)).use { input ->
                parse(input)
            }
        } catch (e: EOFException) {
            throw VaultIndexFormatException("indeks terpotong (berakhir lebih awal dari yang dinyatakan): ${e.message ?: file.path}")
        } catch (e: UTFDataFormatException) {
            throw VaultIndexFormatException("string indeks melanggar modified UTF-8: ${e.message ?: file.path}")
        }
    }

    private fun parse(input: DataInputStream): List<VaultEntry> {
        val magic = ByteArray(4)
        input.readFully(magic)
        if (!magic.contentEquals(MAGIC.toByteArray(Charsets.US_ASCII))) {
            throw VaultIndexFormatException("magic indeks tidak dikenal (harus \"$MAGIC\")")
        }
        val version = input.readUnsignedByte()
        if (version != VERSION) {
            throw VaultIndexFormatException("versi indeks tidak didukung: $version (harus $VERSION)")
        }
        val count = input.readInt()
        if (count < 0 || count > MAX_ENTRIES) {
            throw VaultIndexFormatException("jumlah entri indeks di luar rentang: $count (batas 0..$MAX_ENTRIES)")
        }
        val entries = ArrayList<VaultEntry>(count)
        repeat(count) {
            val id = input.readUTF()
            val storedFileName = input.readUTF()
            val originalName = input.readUTF()
            val originalPath = input.readUTF()
            val mimeType = input.readUTF()
            val sizeBytes = input.readLong()
            if (sizeBytes < 0) {
                throw VaultIndexFormatException("ukuran berkas negatif pada entri \"$id\": $sizeBytes")
            }
            val addedAtEpochMs = input.readLong()
            val wrapMethod =
                when (val wrapMethodId = input.readUnsignedByte()) {
                    VaultFormat.WRAP_METHOD_KEYSTORE -> WrapMethod.KEYSTORE
                    else -> throw VaultIndexFormatException("wrap method tidak dikenal: $wrapMethodId (entri \"$id\")")
                }
            entries += VaultEntry(id, storedFileName, originalName, originalPath, mimeType, sizeBytes, addedAtEpochMs, wrapMethod)
        }
        val leftover = input.available()
        if (leftover > 0) {
            throw VaultIndexFormatException("byte sisa setelah $count entri indeks: $leftover byte (format harus persis)")
        }
        return entries
    }

    /**
     * Menulis [entries] secara atomik ke [target] (`index.bin`): tulis ke
     * `index.bin.tmp`, sink ke disk, cadangkan berkas lama ke `index.bin.bak`,
     * lalu rename tmp menjadi [target]. Kegagalan di tengah proses menyisakan
     * salinan lama utuh di `.bak` sehingga [read] cadangan tetap mungkin.
     *
     * @throws VaultIndexFormatException bila ada string yang melebihi batas
     * u16 dari `writeUTF` (data tidak mungkin dienkode ke format ini).
     * @throws IOException bila I/O tingkat sistem berkas gagal.
     */
    internal fun writeAtomic(
        target: File,
        entries: List<VaultEntry>,
    ) {
        val parent = target.parentFile
        if (parent == null || (!parent.isDirectory && !parent.mkdirs())) {
            throw IOException("direktori induk indeks tidak tersedia: ${target.path}")
        }
        entries.forEach { entry ->
            checkWritableString(entry.id, "id")
            checkWritableString(entry.storedFileName, "storedFileName")
            checkWritableString(entry.originalName, "originalName")
            checkWritableString(entry.originalPath, "originalPath")
            checkWritableString(entry.mimeType, "mimeType")
        }
        val tmp = File(parent, target.name + TEMP_SUFFIX)
        try {
            FileOutputStream(tmp).use { raw ->
                DataOutputStream(BufferedOutputStream(raw, VaultFormat.STREAM_BUFFER_SIZE)).use { out ->
                    out.write(MAGIC.toByteArray(Charsets.US_ASCII))
                    out.writeByte(VERSION)
                    out.writeInt(entries.size)
                    entries.forEach { entry ->
                        out.writeUTF(entry.id)
                        out.writeUTF(entry.storedFileName)
                        out.writeUTF(entry.originalName)
                        out.writeUTF(entry.originalPath)
                        out.writeUTF(entry.mimeType)
                        out.writeLong(entry.sizeBytes)
                        out.writeLong(entry.addedAtEpochMs)
                        out.writeByte(wrapMethodId(entry.wrapMethod))
                    }
                    out.flush()
                }
                raw.fd.sync()
            }
        } catch (e: UTFDataFormatException) {
            tmp.delete()
            throw VaultIndexFormatException(
                "string indeks terlalu panjang untuk u16 modified UTF-8 (batas $MAX_UTF_CHARS byte): ${e.message ?: "-"}",
            )
        } catch (e: IOException) {
            tmp.delete()
            throw IOException("menulis indeks sementara ${tmp.path} gagal: ${e.message ?: e.javaClass.simpleName}", e)
        }
        val backup = File(parent, target.name + BACKUP_SUFFIX)
        if (target.exists()) {
            if (backup.exists() && !backup.delete()) {
                throw IOException("tidak dapat menghapus cadangan indeks lama: ${backup.path}")
            }
            if (!target.renameTo(backup)) {
                throw IOException("tidak dapat membuat cadangan indeks: ${target.path} → ${backup.path}")
            }
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("tidak dapat memfinalisasi indeks: ${tmp.path} → ${target.path}")
        }
    }

    /**
     * Memulihkan [target] (indeks utama) dengan menyalin byte [backup] melalui
     * berkas sementara + `fd.sync()` + rename. Cadangan tidak diubah sehingga
     * tetap dapat dipakai ulang bila pemulihan gagal.
     *
     * @throws IOException bila penyalinan atau rename gagal.
     */
    internal fun restore(
        backup: File,
        target: File,
    ) {
        val parent = target.parentFile
        if (parent == null || (!parent.isDirectory && !parent.mkdirs())) {
            throw IOException("direktori induk indeks tidak tersedia: ${target.path}")
        }
        val tmp = File(parent, target.name + TEMP_SUFFIX)
        try {
            FileInputStream(backup).use { source ->
                FileOutputStream(tmp).use { raw ->
                    BufferedOutputStream(raw, VaultFormat.STREAM_BUFFER_SIZE).use { out ->
                        source.copyTo(out, VaultFormat.STREAM_BUFFER_SIZE)
                        out.flush()
                    }
                    raw.fd.sync()
                }
            }
            if (!tmp.renameTo(target)) {
                throw IOException("rename berkas sementara gagal saat pemulihan indeks")
            }
        } catch (e: IOException) {
            tmp.delete()
            throw IOException("memulihkan indeks dari ${backup.path} ke ${target.path} gagal: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    private fun checkWritableString(
        value: String,
        field: String,
    ) {
        if (value.length > MAX_UTF_CHARS) {
            throw VaultIndexFormatException("kolom $field terlalu panjang: ${value.length} karakter (batas $MAX_UTF_CHARS)")
        }
    }

    private fun wrapMethodId(method: WrapMethod): Int =
        when (method) {
            WrapMethod.KEYSTORE -> VaultFormat.WRAP_METHOD_KEYSTORE
        }
}
