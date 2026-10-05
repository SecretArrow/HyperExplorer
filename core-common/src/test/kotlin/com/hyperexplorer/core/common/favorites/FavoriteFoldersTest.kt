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

package com.hyperexplorer.core.common.favorites

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteFoldersTest {
    @Test
    fun `decode null menghasilkan daftar kosong`() {
        val result = FavoriteFoldersCodec.decode(null)
        assertTrue("decode(null) harus daftar kosong, actual=$result", result.isEmpty())
    }

    @Test
    fun `decode string kosong menghasilkan daftar kosong`() {
        val result = FavoriteFoldersCodec.decode("")
        assertTrue("decode(\"\") harus daftar kosong, actual=$result", result.isEmpty())
    }

    @Test
    fun `decode whitespace saja menghasilkan daftar kosong`() {
        val spaces = FavoriteFoldersCodec.decode("   ")
        assertTrue("decode(\"   \") harus kosong (blank -> invalid), actual=$spaces", spaces.isEmpty())
        val breaks = FavoriteFoldersCodec.decode("\n \n")
        assertTrue("decode(\"\\n \\n\") harus kosong (semua baris blank), actual=$breaks", breaks.isEmpty())
    }

    @Test
    fun `decode membersihkan baris ber-whitespace dan melewati baris kosong`() {
        val result = FavoriteFoldersCodec.decode("  /sdcard/Download  \n   \n\t/DCIM\t")
        assertEquals(
            "baris valid dibersihkan dgn trim, baris blank/kosong dilewati",
            listOf("/sdcard/Download", "/DCIM"),
            result,
        )
    }

    @Test
    fun `decode dedupe mempertahankan kemunculan pertama`() {
        val result = FavoriteFoldersCodec.decode("/a\n/b\n/a\n/b/")
        assertEquals(
            "duplikat persis ('/a') & setelah normalisasi ('/b/') dibuang, kemunculan PERTAMA dipertahankan",
            listOf("/a", "/b"),
            result,
        )
    }

    @Test
    fun `decode mempertahankan urutan penyematan`() {
        val result = FavoriteFoldersCodec.decode("/z\n/a\n/m\n/b")
        assertEquals("urutan hasil = urutan baris input (bukan urut alfabetis)", listOf("/z", "/a", "/m", "/b"), result)
    }

    @Test
    fun `decode memotong 12 input ke 9 item pertama`() {
        val lines = ArrayList<String>()
        val expected = ArrayList<String>()
        for (i in 1..12) {
            val path = "/f" + i.toString().padStart(2, '0')
            lines.add(path)
            if (expected.size < 9) expected.add(path)
        }
        val result = FavoriteFoldersCodec.decode(lines.joinToString("\n"))
        assertEquals("12 input harus dipotong ke kapasitas default: 9 item PERTAMA sesuai urutan", expected, result)
        assertEquals("kapasitas hasil default harus tepat 9", 9, result.size)
    }

    @Test
    fun `decode dengan max 3 mempertahankan 3 item pertama`() {
        val result = FavoriteFoldersCodec.decode("/a\n/b\n/c\n/d\n/e", max = 3)
        assertEquals("max=3 -> hanya 3 item pertama sesuai urutan", listOf("/a", "/b", "/c"), result)
    }

    @Test
    fun `decode dengan max nol atau negatif menghasilkan daftar kosong`() {
        val zero = FavoriteFoldersCodec.decode("/a\n/b", max = 0)
        assertTrue("max=0 -> tidak ada item diterima (defensif, tanpa exception), actual=$zero", zero.isEmpty())
        val negative = FavoriteFoldersCodec.decode("/a\n/b", max = -3)
        assertTrue("max negatif -> tidak ada item diterima, actual=$negative", negative.isEmpty())
        val nullRaw = FavoriteFoldersCodec.decode(null, max = 0)
        assertTrue("max=0 pada raw null tetap kosong", nullRaw.isEmpty())
    }

    @Test
    fun `decode garbage menghasilkan daftar aman tanpa melempar`() {
        val result = FavoriteFoldersCodec.decode("///\n\n\t \n/x//\n   ")
        assertEquals(
            "slash-murni/kosong/blank = invalid (dilewati); '/x//' ternormalisasi ke '/x'",
            listOf("/x"),
            result,
        )
    }

    @Test
    fun `decode root slash tetap root slash`() {
        val result = FavoriteFoldersCodec.decode("/\n/a\n/")
        assertEquals("root '/' valid dan tetap '/'; duplikat '/' kedua dibuang (kemunculan pertama)", listOf("/", "/a"), result)
    }

    @Test
    fun `encode melewati blank newline dan path lebih dari 4096 karakter`() {
        val tooLong = "x".repeat(4097)
        val result = FavoriteFoldersCodec.encode(listOf("", "   ", "a\nb", "c\rd", tooLong, "/ok"))
        assertEquals("hanya '/ok' lolos: blank, mengandung '\\n'/'\\r', dan panjang > 4096 semuanya dilewati", "/ok", result)
    }

    @Test
    fun `encode menerima path tepat 4096 karakter`() {
        val exact = "y".repeat(4096)
        val result = FavoriteFoldersCodec.encode(listOf(exact))
        assertEquals("filter hanya menolak panjang > 4096; tepat 4096 harus lolos utuh", exact, result)
    }

    @Test
    fun `encode menulis path apa adanya setelah lolos filter`() {
        val result = FavoriteFoldersCodec.encode(listOf("  /a  ", "/b"))
        assertEquals("path ditulis TANPA trim (apa adanya), dipisah '\\n' di antara item", "  /a  \n/b", result)
    }

    @Test
    fun `roundtrip encode decode identik untuk daftar valid`() {
        val valid = listOf("/", "/sdcard/Download", "/a b/ñ空 folder", "/z")
        val encoded = FavoriteFoldersCodec.encode(valid)
        val decoded = FavoriteFoldersCodec.decode(encoded)
        assertEquals("decode(encode(valid)) harus identik utk input valid (termasuk root '/' & urutan)", valid, decoded)
        assertEquals("encode(decode(x)) stabil utk input valid", encoded, FavoriteFoldersCodec.encode(decoded))
        val emptyRoundtrip = FavoriteFoldersCodec.decode(FavoriteFoldersCodec.encode(emptyList()))
        assertTrue("roundtrip daftar kosong juga stabil (tetap kosong)", emptyRoundtrip.isEmpty())
    }

    @Test
    fun `add path baru menormalkan lalu menambahkan di akhir`() {
        val updated = FavoriteFolders(emptyList()).add("  /sdcard/Download/// ")
        assertEquals(
            "path baru disimpan ternormalisasi (trim + buang trailing slash) di posisi akhir",
            listOf("/sdcard/Download"),
            updated.items,
        )
    }

    @Test
    fun `add duplikat no-op tanpa memindah urutan`() {
        val state = FavoriteFolders(listOf("/a", "/b", "/c"))
        val exactDup = state.add("/a")
        assertEquals("duplikat persis ('/a') = no-op, urutan utuh", listOf("/a", "/b", "/c"), exactDup.items)
        val normalizedDup = state.add("/c/")
        assertEquals("duplikat setelah normalisasi ('/c/' == '/c') = no-op, urutan utuh", listOf("/a", "/b", "/c"), normalizedDup.items)
    }

    @Test
    fun `add blank atau slash murni no-op`() {
        val state = FavoriteFolders(listOf("/a"))
        val emptyPath = state.add("")
        assertEquals("add(\"\") = no-op (kosong)", listOf("/a"), emptyPath.items)
        val blankPath = state.add("   ")
        assertEquals("add(\"   \") = no-op (blank setelah trim)", listOf("/a"), blankPath.items)
        val slashOnly = state.add("///")
        assertEquals("add(\"///\") = no-op (blank setelah buang trailing slash)", listOf("/a"), slashOnly.items)
    }

    @Test
    fun `add saat penuh size 9 no-op`() {
        val nine = ArrayList<String>()
        for (i in 1..9) nine.add("/p$i")
        val updated = FavoriteFolders(nine).add("/p10")
        assertEquals("items.size (9) >= max default (9) -> no-op", nine, updated.items)
        val almostFull = FavoriteFolders(nine.take(8))
        assertEquals("size 8 < 9 masih menerima 1 item baru di akhir", nine.take(8) + "/p10", almostFull.add("/p10").items)
    }

    @Test
    fun `add dengan max nol atau negatif no-op`() {
        val state = FavoriteFolders(listOf("/a"))
        val zero = state.add("/b", max = 0)
        assertEquals("max=0 -> tidak ada item diterima, state utuh", listOf("/a"), zero.items)
        val negative = state.add("/b", max = -5)
        assertEquals("max negatif -> tidak ada item diterima, state utuh", listOf("/a"), negative.items)
        val emptyState = FavoriteFolders(emptyList()).add("/x", max = 0)
        assertTrue("max=0 pada state kosong -> tetap kosong", emptyState.items.isEmpty())
    }

    @Test
    fun `remove path yang ada menghapus sambil mempertahankan urutan`() {
        val state = FavoriteFolders(listOf("/a", "/b", "/c"))
        val removed = state.remove("/b")
        assertEquals("hapus '/b' -> item tersisa mempertahankan urutan asli", listOf("/a", "/c"), removed.items)
        val removedRoot = FavoriteFolders(listOf("/")).remove("/")
        assertEquals("hapus '/' (root) yang ada -> daftar kosong", emptyList<String>(), removedRoot.items)
    }

    @Test
    fun `remove path yang tidak ada no-op`() {
        val state = FavoriteFolders(listOf("/a", "/b"))
        val missing = state.remove("/zzz")
        assertEquals("path tidak ada -> no-op", listOf("/a", "/b"), missing.items)
        val slashVariant = state.remove("/a/")
        assertEquals("pencocokan string persis: '/a/' TIDAK menghapus '/a' (tanpa normalisasi)", listOf("/a", "/b"), slashVariant.items)
        val padded = state.remove(" /a ")
        assertEquals("pencocokan string persis: ' /a ' TIDAK menghapus '/a'", listOf("/a", "/b"), padded.items)
    }

    @Test
    fun `isFavorite cocok persis dan case-sensitive`() {
        val state = FavoriteFolders(listOf("/a", "/B"))
        assertTrue("cocok persis ('/a') -> true", state.isFavorite("/a"))
        assertTrue("cocok persis (huruf besar '/B') -> true", state.isFavorite("/B"))
        assertFalse("beda huruf ('/A') -> false (filesystem Linux case-sensitive)", state.isFavorite("/A"))
        assertFalse("string tidak persis ('/a/') -> false (isFavorite tanpa normalisasi)", state.isFavorite("/a/"))
        assertFalse("state kosong -> false", FavoriteFolders(emptyList()).isFavorite("/a"))
    }
}
