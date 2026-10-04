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

package com.hyperexplorer.feature.browser.search

import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32
import java.util.zip.CheckedOutputStream

/**
 * Dilempar bila berkas indeks tidak dapat di-parse karena struktur rusak
 * (magic/versi/count/flag/CRC salah, stream terpotong, atau panjang string UTF tak wajar).
 * Subclass [IOException] agar pemanggil bisa menangkap keduanya sekaligus.
 */
class IndexFormatException(detail: String) : IOException(detail)

/**
 * Penyimpan indeks pencarian pada berkas biner (murni JVM, tanpa import android.*).
 *
 * **Format berkas indeks** (big-endian, [DataOutputStream]/[DataInputStream]):
 *
 * ```
 * magic "HXSI" (4 B, DI LUAR cakupan CRC)
 * u16 versi = 1
 * i32 jumlah entri (>= 0)
 * per entri (jumlah kali):
 *   u16 panjang path + byte path (UTF-8)
 *   u8  flag direktori (0 = berkas, 1 = direktori)
 *   i64 sizeBytes
 *   i64 lastModifiedEpochMs
 * i64 CRC32 atas seluruh payload sejak offset 4 (setelah magic, termasuk header)
 * ```
 *
 * Penulisan ATOMIK: selalu tulis ke `<target>.tmp` lalu `Files.move` (REPLACE_EXISTING)
 * ke target; sisa `.tmp` dibersihkan di finally. Kolom `name` entri TIDAK disimpan —
 * saat baca, name direkonstruksi dari segmen terakhir path (format v1).
 */
object SearchIndexStore {
    private const val TEMP_SUFFIX = ".tmp"
    private val MAGIC = "HXSI".toByteArray(Charsets.US_ASCII)
    private const val VERSION = 1
    private const val FLAG_FILE = 0
    private const val FLAG_DIRECTORY = 1
    private const val CRC_BYTES = 8
    private const val BUFFER_SIZE = 8_192

    /** Batas byte u16 untuk satu path. */
    private const val MAX_UTF_BYTES = 65_535

    /** Ukuran minimum berkas valid: magic(4) + versi(2) + count(4) + CRC(8). */
    private val MIN_FILE_BYTES = MAGIC.size + 2 + 4 + CRC_BYTES

    /**
     * Menulis [entries] secara ATOMIK ke [target]: tulis ke `<target>.tmp` lalu
     * rename (Files.move) ke [target]; berkas lama (bila ada) ditimpa.
     *
     * @throws IOException bila direktori induk tidak tersedia, target adalah direktori,
     *   penulisan sementara gagal, atau rename gagal (pesan menyebut apa & mengapa).
     * @throws IndexFormatException bila ada path melebihi batas u16 (tidak mungkin dienkode).
     */
    fun write(
        entries: List<IndexedEntry>,
        target: File,
    ) {
        val parent = target.parentFile
        if (parent == null || !parent.isDirectory) {
            throw IOException("direktori induk tidak tersedia untuk indeks: ${target.absolutePath}")
        }
        if (target.isDirectory) {
            throw IOException("target indeks adalah direktori, bukan berkas: ${target.absolutePath}")
        }
        // Validasi sebelum menyentuh disk: path harus muat dalam u16.
        for (entry in entries) {
            val pathLength = entry.path.toByteArray(Charsets.UTF_8).size
            if (pathLength > MAX_UTF_BYTES) {
                throw IndexFormatException(
                    "path terlalu panjang untuk format indeks: ${entry.path} ($pathLength byte, batas $MAX_UTF_BYTES)",
                )
            }
        }
        val tmp = File(parent, target.name + TEMP_SUFFIX)
        try {
            FileOutputStream(tmp).use { raw ->
                val buffered = BufferedOutputStream(raw, BUFFER_SIZE)
                // Magic ditulis langsung ke buffered (DI LUAR cakupan CRC); CRC mulai setelah magic.
                buffered.write(MAGIC)
                val checked = CheckedOutputStream(buffered, CRC32())
                val out = DataOutputStream(checked)
                out.writeShort(VERSION)
                out.writeInt(entries.size)
                for (entry in entries) {
                    val pathBytes = entry.path.toByteArray(Charsets.UTF_8)
                    out.writeShort(pathBytes.size)
                    out.write(pathBytes)
                    out.writeByte(if (entry.isDirectory) FLAG_DIRECTORY else FLAG_FILE)
                    out.writeLong(entry.sizeBytes)
                    out.writeLong(entry.lastModifiedEpochMs)
                }
                out.flush()
                // CRC (8 byte big-endian) ditulis setelah payload, masih di luar cakupan CRC.
                writeLongBE(buffered, checked.checksum.value)
                buffered.flush()
                buffered.close()
            }
            // Files.move melempar IOException bila gagal — sukses berarti rename tuntas.
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            throw IOException("menulis indeks '${target.absolutePath}' gagal: ${e.message ?: e.javaClass.simpleName}", e)
        } finally {
            // Bersihkan .tmp sisa (sukses: sudah berpindah jadi target; gagal: hapus jejak).
            if (tmp.exists()) {
                tmp.delete()
            }
        }
    }

    /**
     * Membaca seluruh entri dari [file] dengan validasi ketat format v1.
     *
     * @throws FileNotFoundException bila [file] tidak ada.
     * @throws IndexFormatException bila magic/versi/count/flag/CRC salah, stream
     *   terpotong, atau panjang string UTF tak wajar (pesan Indonesia menyebut bagian rusak).
     * @throws IOException bila pembacaan tingkat sistem berkas gagal.
     */
    fun read(file: File): List<IndexedEntry> {
        if (!file.exists()) {
            throw FileNotFoundException("berkas indeks tidak ditemukan: ${file.absolutePath}")
        }
        val bytes =
            try {
                file.readBytes()
            } catch (e: IOException) {
                throw IOException("gagal membaca berkas indeks '${file.absolutePath}': ${e.message ?: e.javaClass.simpleName}", e)
            }
        if (bytes.size < MAGIC.size) {
            throw IndexFormatException("indeks terpotong: hanya ${bytes.size} byte, magic butuh ${MAGIC.size} byte (${file.absolutePath})")
        }
        if (!bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw IndexFormatException("magic indeks tidak dikenal (harus \"HXSI\"): ${file.absolutePath}")
        }
        if (bytes.size < MIN_FILE_BYTES) {
            throw IndexFormatException(
                "indeks terpotong: ${bytes.size} byte, minimal $MIN_FILE_BYTES byte (header + CRC): ${file.absolutePath}",
            )
        }
        val payload = bytes.copyOfRange(MAGIC.size, bytes.size - CRC_BYTES)
        val recordedCrc = readLongBE(bytes, bytes.size - CRC_BYTES)
        // Parse dulu, verifikasi CRC kemudian: file terpotong dilaporkan sbg "terpotong",
        // bukan sbg galat CRC (CRC memang pasti tidak cocok bila data hilang).
        val entries =
            try {
                parsePayload(payload)
            } catch (e: EOFException) {
                throw IndexFormatException(
                    "indeks terpotong di tengah data (kemungkinan panjang string UTF tidak wajar): ${file.absolutePath}",
                )
            }
        val computedCrc = CRC32()
        computedCrc.update(payload)
        if (computedCrc.value != recordedCrc) {
            throw IndexFormatException(
                "CRC indeks tidak cocok — data rusak (tercatat=$recordedCrc, dihitung=${computedCrc.value}): ${file.absolutePath}",
            )
        }
        return entries
    }

    /**
     * Membaca [file] tanpa pernah melempar: berkas tidak ada ATAU rusak ->
     * List kosong (self-heal fail-safe untuk UI — tampilkan indeks kosong, bukan crash).
     */
    fun readOrEmpty(file: File): List<IndexedEntry> = runCatching { read(file) }.getOrElse { emptyList() }

    /** Mem-parse payload (versi..akhir entri, TANPA CRC) dan memvalidasi setiap kolom. */
    private fun parsePayload(payload: ByteArray): List<IndexedEntry> {
        val input = DataInputStream(ByteArrayInputStream(payload))
        val version = input.readUnsignedShort()
        if (version != VERSION) {
            throw IndexFormatException("versi indeks tidak didukung: $version (harus $VERSION)")
        }
        val count = input.readInt()
        if (count < 0) {
            throw IndexFormatException("jumlah entri indeks negatif: $count (harus >= 0)")
        }
        // Kapasitas awal dibatasi agar header curangan (count raksasa) tak memicu alokasi besar.
        val entries = ArrayList<IndexedEntry>(count.coerceAtMost(1024))
        repeat(count) {
            val path = readString(input)
            val flag = input.readUnsignedByte()
            if (flag != FLAG_FILE && flag != FLAG_DIRECTORY) {
                throw IndexFormatException(
                    "flag direktori tidak sah: $flag (harus 0 atau 1) pada entri \"${path.substringAfterLast('/')}\"",
                )
            }
            val sizeBytes = input.readLong()
            val lastModifiedEpochMs = input.readLong()
            entries.add(
                IndexedEntry(
                    path = path,
                    name = path.substringAfterLast('/'),
                    isDirectory = flag == FLAG_DIRECTORY,
                    sizeBytes = sizeBytes,
                    lastModifiedEpochMs = lastModifiedEpochMs,
                ),
            )
        }
        val leftover = input.available()
        if (leftover > 0) {
            throw IndexFormatException("sisa $leftover byte setelah $count entri indeks (format harus persis)")
        }
        return entries
    }

    /**
     * Membaca satu string "u16 panjang + byte UTF-8". Panjang u16 tidak mungkin
     * negatif; panjang yang melampaui sisa payload ditolak eksplisit, sisanya
     * terdeteksi sbg EOF (dipetakan menjadi "terpotong" di [read]).
     */
    private fun readString(input: DataInputStream): String {
        val length = input.readUnsignedShort()
        val remaining = input.available()
        if (length > remaining) {
            throw IndexFormatException("panjang string UTF tidak wajar: $length byte (sisa payload hanya $remaining byte)")
        }
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    /** Menulis [value] sbg 8 byte big-endian (sesuai endianness DataOutputStream). */
    private fun writeLongBE(
        out: OutputStream,
        value: Long,
    ) {
        for (shift in 56 downTo 0 step 8) {
            out.write(((value ushr shift) and 0xFF).toInt())
        }
    }

    /** Membaca 8 byte big-endian mulai [offset] (kolom CRC di akhir berkas). */
    private fun readLongBE(
        bytes: ByteArray,
        offset: Int,
    ): Long {
        var value = 0L
        for (index in 0 until CRC_BYTES) {
            value = (value shl 8) or (bytes[offset + index].toLong() and 0xFF)
        }
        return value
    }
}
