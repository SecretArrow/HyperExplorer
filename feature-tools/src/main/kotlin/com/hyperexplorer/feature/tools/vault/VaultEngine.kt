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

import com.hyperexplorer.core.common.ConflictStrategy
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Mesin vault terenkripsi: impor → simpan blob `.hve` (AES-256-GCM) + indeks
 * biner, ekspor/buka/hapus/verifikasi/rename entri.
 *
 * Ketentuan perilaku:
 * - Semua metode [Synchronized] untuk menserialisasi mutasi `index.bin`.
 * - Tidak pernah melempar exception ke pemanggil; setiap kegagalan dipetakan ke
 *   [VaultError] yang informatif (tanpa pernah menaruh materi kunci di pesan).
 * - Commit impor: (1) validasi nama & sumber → (2) tulis blob `.hve.tmp` lalu
 *   rename `.hve` → (3) baca indeks → (4) tulis indeks baru (atomik, dengan
 *   cadangan `.bak`) → (5) hanya jika semua sukses dan diminta: hapus sumber.
 *   Gagal di (3)/(4) → blob baru dibersihkan (kompensasi), tanpa menyentuh data lama.
 * - Indeks rusak (utama & cadangan) → operasi tulis ditolak dengan
 *   [VaultError.IndexCorrupt]; blob tidak pernah dihancurkan otomatis.
 * - Seluruh I/O streaming dengan buffer [VaultFormat.STREAM_BUFFER_SIZE]; berkas
 *   tidak pernah dimuat utuh ke memori.
 *
 * @property vaultDir direktori penyimpanan vault (blob + indeks); dibuat bila belum ada.
 * @property keyProvider penyedia pembungkusan DEK, mis. [AndroidKeystoreKeyProvider].
 */
class VaultEngine(private val vaultDir: File, private val keyProvider: VaultKeyProvider) {
    private val indexFile = File(vaultDir, VaultIndex.INDEX_FILE_NAME)
    private val secureRandom = SecureRandom()

    /**
     * Mengimpor [src] ke vault. Lihat KDoc kelas untuk urutan commit; kegagalan
     * penghapusan sumber BUKAN kegagalan impor ([ImportOutcome.sourceDeleted] = false).
     */
    @Synchronized
    fun importFile(
        src: File,
        deleteSource: Boolean,
    ): VaultResult<ImportOutcome> {
        if (!src.exists()) return VaultResult.Err(VaultError.SourceMissing(src.path))
        if (src.isDirectory) return VaultResult.Err(VaultError.InvalidInput("sumber adalah direktori, bukan berkas: ${src.path}"))
        val nameError = VaultNames.validate(src.name)
        if (nameError != null) {
            return VaultResult.Err(VaultError.InvalidInput("nama berkas sumber tidak valid (${describe(nameError)}): ${src.name}"))
        }
        if (!src.canRead()) {
            return VaultResult.Err(VaultError.IoFailure("membaca berkas sumber", "tidak ada izin baca: ${src.path}"))
        }
        val id = UUID.randomUUID().toString().replace("-", "")
        val storedFileName = id + BLOB_FILE_SUFFIX
        val blobTmp = File(vaultDir, storedFileName + TEMP_FILE_SUFFIX)
        val blob = File(vaultDir, storedFileName)
        val dek = ByteArray(DEK_SIZE_BYTES).also { secureRandom.nextBytes(it) }
        val wrapped =
            try {
                keyProvider.wrapDek(dek, id.toByteArray(Charsets.UTF_8))
            } catch (e: GeneralSecurityException) {
                return VaultResult.Err(VaultError.KeyUnavailable("pembungkusan DEK gagal: ${causeText(e)}"))
            }
        if (!vaultDir.isDirectory && !vaultDir.mkdirs()) {
            return VaultResult.Err(VaultError.IoFailure("membuat direktori vault", "gagal membuat ${vaultDir.path}"))
        }
        try {
            writeBlob(blobTmp, id, dek, wrapped, src.name, src)
            if (!blobTmp.renameTo(blob)) {
                throw IOException("rename ${blobTmp.name} → ${blob.name} gagal")
            }
        } catch (e: IOException) {
            blobTmp.delete()
            return VaultResult.Err(VaultError.IoFailure("menulis blob $storedFileName", causeText(e)))
        }
        val entries =
            try {
                readEntries()
            } catch (e: VaultIndexFormatException) {
                discardBlob(blobTmp, blob)
                return VaultResult.Err(VaultError.IndexCorrupt(causeText(e)))
            } catch (e: IOException) {
                discardBlob(blobTmp, blob)
                return VaultResult.Err(VaultError.IoFailure("membaca indeks vault", causeText(e)))
            }
        if (entries.any { it.id == id }) {
            discardBlob(blobTmp, blob)
            return VaultResult.Err(VaultError.IoFailure("komit entri vault", "id entri bentrok: $id sudah terdaftar di indeks"))
        }
        val newEntry =
            VaultEntry(
                id = id,
                storedFileName = storedFileName,
                originalName = src.name,
                originalPath = src.absolutePath,
                mimeType = MIME_TYPE_V1,
                sizeBytes = src.length(),
                addedAtEpochMs = System.currentTimeMillis(),
                wrapMethod = WrapMethod.KEYSTORE,
            )
        try {
            VaultIndex.writeAtomic(indexFile, entries + newEntry)
        } catch (e: VaultIndexFormatException) {
            discardBlob(blobTmp, blob)
            return VaultResult.Err(VaultError.IndexCorrupt(causeText(e)))
        } catch (e: IOException) {
            discardBlob(blobTmp, blob)
            return VaultResult.Err(VaultError.IoFailure("menulis indeks vault", causeText(e)))
        }
        var sourceDeleted = false
        if (deleteSource) sourceDeleted = src.delete()
        return VaultResult.Ok(ImportOutcome(newEntry, sourceDeleted))
    }

    /**
     * Daftar seluruh entri. Vault tanpa `index.bin` (baru) → daftar kosong.
     * Indeks utama rusak → dicoba cadangan `.bak`; bila cadangan terbaca,
     * indeks utama dipulihkan (self-heal) dan isinya dikembalikan.
     */
    @Synchronized
    fun listEntries(): VaultResult<List<VaultEntry>> =
        try {
            VaultResult.Ok(readEntries())
        } catch (e: VaultIndexFormatException) {
            VaultResult.Err(VaultError.IndexCorrupt(causeText(e)))
        } catch (e: IOException) {
            VaultResult.Err(VaultError.IoFailure("membaca indeks vault", causeText(e)))
        }

    /**
     * Mengekspor entri [id] ke [destDir] dengan nama aslinya. Konflik nama
     * ditangani sesuai [conflict]:
     * - [ConflictStrategy.OVERWRITE]: target lama dihapus dulu, lalu diekspor.
     * - [ConflictStrategy.RENAME]: dipakai nama bebas pertama "nama (1).ext", "nama (2).ext", dst.
     * - [ConflictStrategy.SKIP]: target lama dikembalikan apa adanya TANPA perubahan apa pun.
     */
    @Synchronized
    fun exportEntry(
        id: String,
        destDir: File,
        conflict: ConflictStrategy,
    ): VaultResult<File> {
        if (!destDir.exists()) return VaultResult.Err(VaultError.InvalidInput("direktori tujuan tidak ada: ${destDir.path}"))
        if (!destDir.isDirectory) return VaultResult.Err(VaultError.InvalidInput("tujuan bukan direktori: ${destDir.path}"))
        val (entry, blob) =
            when (val resolved = resolveEntryBlob(id)) {
                is VaultResult.Ok -> resolved.value
                is VaultResult.Err -> return resolved
            }
        val target =
            when (val resolution = resolveExportTarget(entry, destDir, conflict)) {
                is VaultResult.Ok ->
                    when (resolution.value) {
                        is ExportTargetResolution.Proceed -> resolution.value.target
                        is ExportTargetResolution.Skip -> return VaultResult.Ok(resolution.value.target)
                    }
                is VaultResult.Err -> return resolution
            }
        return decryptToTemporary(entry, blob, target, "mengekspor entri ${entry.storedFileName}")
    }

    /**
     * Mendekripsi entri [id] ke berkas cache `hve_<id>_<originalName>` di dalam
     * [cacheDir] (dibuat bila perlu). Berkas lama dengan nama sama selalu ditimpa.
     * Pemanggil bertanggung jawab membersihkan cache; jangan simpan rahasia di cache ini.
     */
    @Synchronized
    fun openDecrypted(
        id: String,
        cacheDir: File,
    ): VaultResult<File> {
        if (!cacheDir.isDirectory && !cacheDir.mkdirs()) {
            return VaultResult.Err(VaultError.IoFailure("menyiapkan direktori cache", "gagal membuat ${cacheDir.path}"))
        }
        val (entry, blob) =
            when (val resolved = resolveEntryBlob(id)) {
                is VaultResult.Ok -> resolved.value
                is VaultResult.Err -> return resolved
            }
        val target = File(cacheDir, CACHE_FILE_PREFIX + entry.id + "_" + entry.originalName)
        return decryptToTemporary(entry, blob, target, "menulis berkas cache terdekripsi")
    }

    /**
     * Menghapus entri [id] dari indeks lalu menghapus blob-nya. Entri idempoten:
     * bila blob sudah hilang, operasi tetap [VaultResult.Ok] dan indeks tetap
     * diperbarui; id tak dikenal → [VaultError.EntryNotFound].
     */
    @Synchronized
    fun deleteEntry(id: String): VaultResult<Unit> {
        val entries =
            try {
                readEntries()
            } catch (e: VaultIndexFormatException) {
                return VaultResult.Err(VaultError.IndexCorrupt(causeText(e)))
            } catch (e: IOException) {
                return VaultResult.Err(VaultError.IoFailure("membaca indeks vault", causeText(e)))
            }
        val entry = entries.firstOrNull { it.id == id } ?: return VaultResult.Err(VaultError.EntryNotFound(id))
        val blob = File(vaultDir, entry.storedFileName)
        if (blob.exists() && !blob.delete()) {
            return VaultResult.Err(
                VaultError.IoFailure("menghapus blob ${entry.storedFileName}", "berkas tidak dapat dihapus (izin/terkunci): ${blob.path}"),
            )
        }
        try {
            VaultIndex.writeAtomic(indexFile, entries.filter { it.id != id })
        } catch (e: VaultIndexFormatException) {
            return VaultResult.Err(VaultError.IndexCorrupt(causeText(e)))
        } catch (e: IOException) {
            return VaultResult.Err(VaultError.IoFailure("menulis indeks vault", causeText(e)))
        }
        return VaultResult.Ok(Unit)
    }

    /**
     * Memverifikasi integritas entri [id]: dekripsi streaming ke sink nol sambil
     * menghitung byte, membandingkan dengan [VaultEntry.sizeBytes]. Tag GCM yang
     * gagal berarti [VaultError.CorruptEntry]; tidak ada berkas yang ditulis.
     */
    @Synchronized
    fun verifyEntry(id: String): VaultResult<Unit> {
        val (entry, blob) =
            when (val resolved = resolveEntryBlob(id)) {
                is VaultResult.Ok -> resolved.value
                is VaultResult.Err -> return resolved
            }
        val sink = NullByteSink()
        try {
            val count = decryptStreaming(entry, blob, sink)
            if (count != entry.sizeBytes) {
                return VaultResult.Err(
                    VaultError.CorruptEntry(id, "ukuran plaintext $count tidak cocok dengan indeks ${entry.sizeBytes} (blob terpotong?)"),
                )
            }
        } catch (e: VaultBlobFormatException) {
            return VaultResult.Err(VaultError.CorruptEntry(id, e.message ?: "blob tidak sesuai format"))
        } catch (e: IOException) {
            return if (isGcmTagFailure(e)) {
                VaultResult.Err(VaultError.CorruptEntry(id, "verifikasi GCM gagal: ${causeText(e)}"))
            } else {
                VaultResult.Err(VaultError.IoFailure("memverifikasi ${entry.storedFileName}", causeText(e)))
            }
        } catch (e: AEADBadTagException) {
            return VaultResult.Err(
                VaultError.CorruptEntry(id, "verifikasi GCM gagal pada wrapped DEK (kunci/AAD tidak cocok): ${causeText(e)}"),
            )
        } catch (e: GeneralSecurityException) {
            return VaultResult.Err(VaultError.KeyUnavailable("kunci tidak dapat membuka DEK entri $id: ${causeText(e)}"))
        }
        return VaultResult.Ok(Unit)
    }

    /**
     * Mengganti nama tampilan entri [id] menjadi [newName]. `storedFileName` dan
     * blob TIDAK berubah (id permanen); hanya `originalName` di indeks yang diperbarui.
     */
    @Synchronized
    fun renameEntry(
        id: String,
        newName: String,
    ): VaultResult<VaultEntry> {
        val nameError = VaultNames.validate(newName)
        if (nameError != null) {
            return VaultResult.Err(VaultError.InvalidInput("nama baru tidak valid (${describe(nameError)}): $newName"))
        }
        val entries =
            try {
                readEntries()
            } catch (e: VaultIndexFormatException) {
                return VaultResult.Err(VaultError.IndexCorrupt(causeText(e)))
            } catch (e: IOException) {
                return VaultResult.Err(VaultError.IoFailure("membaca indeks vault", causeText(e)))
            }
        val entry = entries.firstOrNull { it.id == id } ?: return VaultResult.Err(VaultError.EntryNotFound(id))
        if (entry.originalName.equals(newName, ignoreCase = true)) {
            return VaultResult.Err(
                VaultError.InvalidInput("nama baru sama dengan nama lama (perbandingan tanpa membedakan huruf besar/kecil): $newName"),
            )
        }
        val updated = entry.copy(originalName = newName)
        try {
            VaultIndex.writeAtomic(indexFile, entries.map { if (it.id == id) updated else it })
        } catch (e: VaultIndexFormatException) {
            return VaultResult.Err(VaultError.IndexCorrupt(causeText(e)))
        } catch (e: IOException) {
            return VaultResult.Err(VaultError.IoFailure("menulis indeks vault", causeText(e)))
        }
        return VaultResult.Ok(updated)
    }

    /** Hasil penentuan target ekspor ketika nama tujuan sudah ada. */
    private sealed interface ExportTargetResolution {
        /** Lanjutkan dekripsi ke [target] (target dijamin tidak ada). */
        data class Proceed(val target: File) : ExportTargetResolution

        /** SKIP: [target] lama dikembalikan tanpa disentuh sama sekali. */
        data class Skip(val target: File) : ExportTargetResolution
    }

    private fun resolveExportTarget(
        entry: VaultEntry,
        destDir: File,
        conflict: ConflictStrategy,
    ): VaultResult<ExportTargetResolution> {
        val direct = File(destDir, entry.originalName)
        if (!direct.exists()) return VaultResult.Ok(ExportTargetResolution.Proceed(direct))
        return when (conflict) {
            ConflictStrategy.OVERWRITE ->
                if (direct.delete()) {
                    VaultResult.Ok(ExportTargetResolution.Proceed(direct))
                } else {
                    VaultResult.Err(VaultError.IoFailure("menimpa berkas tujuan", "tidak dapat menghapus target lama: ${direct.path}"))
                }
            ConflictStrategy.RENAME -> VaultResult.Ok(ExportTargetResolution.Proceed(findNumberedVariant(direct)))
            ConflictStrategy.SKIP -> VaultResult.Ok(ExportTargetResolution.Skip(direct))
        }
    }

    /**
     * Membaca indeks dengan perbaikan mandiri: utama → cadangan `.bak` → gagal.
     * Bila utama hilang tetapi cadangan ada (jendela crash saat commit), isi
     * cadangan dipulihkan ke utama. Melempar [VaultIndexFormatException] atau
     * [IOException]; pemanggil yang memetakan ke [VaultError].
     */
    private fun readEntries(): List<VaultEntry> {
        if (!indexFile.exists()) {
            val backup = indexBackupFile
            if (!backup.exists()) return emptyList()
            val recovered = VaultIndex.read(backup)
            VaultIndex.restore(backup, indexFile)
            return recovered
        }
        return try {
            VaultIndex.read(indexFile)
        } catch (e: IOException) {
            tryReadBackup(e)
        } catch (e: VaultIndexFormatException) {
            tryReadBackup(e)
        }
    }

    private fun tryReadBackup(primary: Exception): List<VaultEntry> {
        val backup = indexBackupFile
        if (!backup.exists()) throw primary
        val recovered =
            try {
                VaultIndex.read(backup)
            } catch (e: Exception) {
                when (e) {
                    is IOException, is VaultIndexFormatException -> throw VaultIndexFormatException(
                        "indeks utama dan cadangan sama-sama tidak terbaca; " +
                            "utama (${indexFile.path}): ${causeText(primary)}; cadangan (${backup.path}): ${causeText(e)}",
                    )
                    else -> throw e
                }
            }
        VaultIndex.restore(backup, indexFile)
        return recovered
    }

    private val indexBackupFile: File
        get() = File(vaultDir, VaultIndex.INDEX_FILE_NAME + VaultIndex.BACKUP_SUFFIX)

    /** Mengambil entri [id] + blob-nya; memetakan kegagalan indeks/keberadaan blob. */
    private fun resolveEntryBlob(id: String): VaultResult<Pair<VaultEntry, File>> {
        val entries =
            try {
                readEntries()
            } catch (e: VaultIndexFormatException) {
                return VaultResult.Err(VaultError.IndexCorrupt(causeText(e)))
            } catch (e: IOException) {
                return VaultResult.Err(VaultError.IoFailure("membaca indeks vault", causeText(e)))
            }
        val entry = entries.firstOrNull { it.id == id } ?: return VaultResult.Err(VaultError.EntryNotFound(id))
        val blob = File(vaultDir, entry.storedFileName)
        if (!blob.isFile) return VaultResult.Err(VaultError.CorruptEntry(id, "blob hilang dari vault: ${entry.storedFileName}"))
        if (entry.sizeBytes < 0) {
            return VaultResult.Err(VaultError.CorruptEntry(id, "ukuran plaintext negatif di indeks: ${entry.sizeBytes}"))
        }
        return VaultResult.Ok(entry to blob)
    }

    /** Dekripsi [entry] ke berkas sementara lalu rename atomik ke [finalTarget]. */
    private fun decryptToTemporary(
        entry: VaultEntry,
        blob: File,
        finalTarget: File,
        operation: String,
    ): VaultResult<File> {
        val tmp = temporaryFileFor(finalTarget)
        try {
            val count = FileOutputStream(tmp).use { out -> decryptStreaming(entry, blob, out) }
            if (count != entry.sizeBytes) {
                tmp.delete()
                return VaultResult.Err(
                    VaultError.CorruptEntry(
                        entry.id,
                        "ukuran plaintext $count tidak cocok dengan indeks ${entry.sizeBytes} (blob terpotong?)",
                    ),
                )
            }
        } catch (e: VaultBlobFormatException) {
            tmp.delete()
            return VaultResult.Err(VaultError.CorruptEntry(entry.id, e.message ?: "blob tidak sesuai format"))
        } catch (e: IOException) {
            tmp.delete()
            return if (isGcmTagFailure(e)) {
                VaultResult.Err(VaultError.CorruptEntry(entry.id, "verifikasi GCM gagal: ${causeText(e)}"))
            } else {
                VaultResult.Err(VaultError.IoFailure(operation, causeText(e)))
            }
        } catch (e: AEADBadTagException) {
            tmp.delete()
            return VaultResult.Err(
                VaultError.CorruptEntry(entry.id, "verifikasi GCM gagal pada wrapped DEK (kunci/AAD tidak cocok): ${causeText(e)}"),
            )
        } catch (e: GeneralSecurityException) {
            tmp.delete()
            return VaultResult.Err(VaultError.KeyUnavailable("kunci tidak dapat membuka DEK entri ${entry.id}: ${causeText(e)}"))
        }
        if (!tmp.renameTo(finalTarget)) {
            tmp.delete()
            return VaultResult.Err(
                VaultError.IoFailure(operation, "rename ${tmp.name} → ${finalTarget.name} gagal di ${finalTarget.parent}"),
            )
        }
        return VaultResult.Ok(finalTarget)
    }

    /**
     * Membaca header blob, membuka DEK terbungkus, lalu men-streaming plaintext
     * ke [sink] dengan buffer [VaultFormat.STREAM_BUFFER_SIZE]; mengembalikan
     * jumlah byte payload yang tertulis (tanpa metadata).
     */
    private fun decryptStreaming(
        entry: VaultEntry,
        blob: File,
        sink: OutputStream,
    ): Long {
        DataInputStream(BufferedInputStream(FileInputStream(blob), VaultFormat.STREAM_BUFFER_SIZE)).use { input ->
            val header = readBlobHeader(input, blob.length())
            val aad = entry.id.toByteArray(Charsets.UTF_8)
            val dek = keyProvider.unwrapDek(header.wrappedDek, aad)
            if (dek.size != DEK_SIZE_BYTES) {
                throw GeneralSecurityException("DEK hasil pembukaan berukuran ${dek.size} byte, seharusnya $DEK_SIZE_BYTES")
            }
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(dek, KEY_ALGORITHM),
                GCMParameterSpec(VaultFormat.GCM_TAG_BITS, header.contentNonce),
            )
            cipher.updateAAD(aad)
            val decrypted = CipherInputStream(input, cipher)
            val metadata = DataInputStream(decrypted)
            // Nama & mime dibaca agar posisi payload tepat; keduanya terautentikasi GCM.
            readMetaString(metadata, "nama")
            readMetaString(metadata, "mime")
            val buffer = ByteArray(VaultFormat.STREAM_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = decrypted.read(buffer)
                if (read < 0) break
                sink.write(buffer, 0, read)
                total += read
            }
            return total
        }
    }

    /**
     * Mem-parsing header 20 byte blob (lihat [VaultFormat]). Seluruh pelanggaran
     * struktur dilempar sebagai [VaultBlobFormatException] agar dipetakan ke
     * [VaultError.CorruptEntry].
     */
    private fun readBlobHeader(
        input: DataInputStream,
        blobSize: Long,
    ): BlobHeader {
        if (blobSize < HEADER_SIZE_BYTES) {
            throw VaultBlobFormatException("berkas terpotong: $blobSize byte < header $HEADER_SIZE_BYTES byte")
        }
        val magic = ByteArray(4)
        input.readFully(magic)
        if (!magic.contentEquals(VaultFormat.MAGIC.toByteArray(Charsets.US_ASCII))) {
            throw VaultBlobFormatException("magic berkas tidak dikenal (harus \"${VaultFormat.MAGIC}\")")
        }
        val version = input.readUnsignedByte()
        if (version != VaultFormat.VERSION.toInt()) {
            throw VaultBlobFormatException("versi format tidak didukung: $version")
        }
        val contentNonce = ByteArray(VaultFormat.CONTENT_NONCE_SIZE)
        input.readFully(contentNonce)
        val wrapMethodId = input.readUnsignedByte()
        if (wrapMethodId != VaultFormat.WRAP_METHOD_KEYSTORE) {
            throw VaultBlobFormatException("wrap method tidak dikenal: $wrapMethodId")
        }
        val wrappedLength = input.readUnsignedShort()
        val remaining = blobSize - HEADER_SIZE_BYTES
        if (wrappedLength > remaining) {
            throw VaultBlobFormatException("panjang wrapped DEK tidak masuk akal: $wrappedLength > sisa berkas $remaining byte")
        }
        if (remaining - wrappedLength < VaultFormat.GCM_TAG_BITS / 8) {
            throw VaultBlobFormatException(
                "ciphertext terlalu pendek: sisa ${remaining - wrappedLength} byte < tag GCM ${VaultFormat.GCM_TAG_BITS / 8} byte",
            )
        }
        val wrapped = ByteArray(wrappedLength)
        input.readFully(wrapped)
        return BlobHeader(contentNonce, wrapped)
    }

    /** Membaca satu string metadata u16-panjang dari plaintext (guard anti-OOM). */
    private fun readMetaString(
        input: DataInputStream,
        field: String,
    ): String {
        val length = input.readUnsignedShort()
        if (length > VaultFormat.MAX_META_STRING_CHARS) {
            throw VaultBlobFormatException(
                "panjang $field pada plaintext tidak masuk akal: $length (batas ${VaultFormat.MAX_META_STRING_CHARS})",
            )
        }
        val bytes = ByteArray(length)
        try {
            input.readFully(bytes)
        } catch (e: EOFException) {
            throw VaultBlobFormatException("metadata $field terpotong di dalam plaintext")
        }
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * Menulis blob `.hve` ke [target] (berkas `.tmp`): header 20 byte (magic,
     * versi, nonce konten acak, id wrap method, u16 panjang + wrapped DEK),
     * lalu plaintext `[u16 nama | nama | u16 mime | mime | payload]` terenkripsi
     * GCM dengan AAD = id entri.
     */
    private fun writeBlob(
        target: File,
        id: String,
        dek: ByteArray,
        wrapped: ByteArray,
        originalName: String,
        source: File,
    ) {
        val nonce = ByteArray(VaultFormat.CONTENT_NONCE_SIZE).also { secureRandom.nextBytes(it) }
        val nameBytes = originalName.toByteArray(Charsets.UTF_8)
        val mimeBytes = MIME_TYPE_V1.toByteArray(Charsets.UTF_8)
        if (nameBytes.size > U16_MAX_VALUE || mimeBytes.size > U16_MAX_VALUE) {
            throw IOException(
                "metadata plaintext terlalu panjang: nama ${nameBytes.size} byte, mime ${mimeBytes.size} byte (batas $U16_MAX_VALUE)",
            )
        }
        val contentCipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        contentCipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(dek, KEY_ALGORITHM), GCMParameterSpec(VaultFormat.GCM_TAG_BITS, nonce))
        contentCipher.updateAAD(id.toByteArray(Charsets.UTF_8))
        FileOutputStream(target).use { raw ->
            BufferedOutputStream(raw, VaultFormat.STREAM_BUFFER_SIZE).use { out ->
                out.write(VaultFormat.MAGIC.toByteArray(Charsets.US_ASCII))
                out.write(VaultFormat.VERSION.toInt())
                out.write(nonce)
                out.write(wrapMethodId(WrapMethod.KEYSTORE))
                writeU16(out, wrapped.size)
                out.write(wrapped)
                CipherOutputStream(out, contentCipher).use { encrypted ->
                    writeU16(encrypted, nameBytes.size)
                    encrypted.write(nameBytes)
                    writeU16(encrypted, mimeBytes.size)
                    encrypted.write(mimeBytes)
                    FileInputStream(source).use { input -> input.copyTo(encrypted, VaultFormat.STREAM_BUFFER_SIZE) }
                }
            }
        }
    }

    private fun writeU16(
        out: OutputStream,
        value: Int,
    ) {
        out.write(value ushr 8 and 0xFF)
        out.write(value and 0xFF)
    }

    private fun wrapMethodId(method: WrapMethod): Int =
        when (method) {
            WrapMethod.KEYSTORE -> VaultFormat.WRAP_METHOD_KEYSTORE
        }

    /** Versi bernomor bebas pertama: "nama (1).ext", "nama (2).ext", dst. */
    private fun findNumberedVariant(target: File): File {
        val name = target.name
        val dotIndex = name.lastIndexOf('.')
        val base = if (dotIndex > 0) name.substring(0, dotIndex) else name
        val extension = if (dotIndex > 0) name.substring(dotIndex) else ""
        var counter = 1
        var candidate = File(target.parentFile, "$base ($counter)$extension")
        while (candidate.exists()) {
            counter++
            candidate = File(target.parentFile, "$base ($counter)$extension")
        }
        return candidate
    }

    /** Berkas sementara yang dijamin tidak menimpa berkas lain yang sudah ada. */
    private fun temporaryFileFor(finalTarget: File): File {
        var candidate = File(finalTarget.parentFile, finalTarget.name + TEMP_FILE_SUFFIX)
        var counter = 1
        while (candidate.exists()) {
            candidate = File(finalTarget.parentFile, finalTarget.name + TEMP_FILE_SUFFIX + counter)
            counter++
        }
        return candidate
    }

    /** Kompensasi impor: hapus blob hasil percobaan; best-effort, error utama dipertahankan. */
    private fun discardBlob(
        blobTmp: File,
        blob: File,
    ) {
        blobTmp.delete()
        blob.delete()
    }

    /** Deteksi kegagalan tag GCM yang dibungkus [CipherInputStream] ke dalam [IOException]. */
    private fun isGcmTagFailure(e: IOException): Boolean {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is AEADBadTagException || cause is BadPaddingException) return true
            cause = cause.cause
        }
        return false
    }

    private fun causeText(e: Exception): String = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName

    private fun describe(error: VaultNameError): String =
        when (error) {
            VaultNameError.BLANK -> "nama kosong atau hanya spasi"
            VaultNameError.ILLEGAL_CHAR -> "memuat karakter terlarang '/', '\\', atau NUL"
            VaultNameError.RESERVED -> "nama \".\" dan \"..\" terlarang"
            VaultNameError.TOO_LONG -> "melebihi ${VaultNames.MAX_NAME_BYTES} byte UTF-8"
        }

    private class BlobHeader(val contentNonce: ByteArray, val wrappedDek: ByteArray)

    /** Sink nol untuk verifikasi: menghitung byte tanpa menyimpan apa pun. */
    private class NullByteSink : OutputStream() {
        var count: Long = 0L
            private set

        override fun write(b: Int) {
            count++
        }

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) {
            count += len
        }
    }

    private companion object {
        private const val BLOB_FILE_SUFFIX = ".hve"
        private const val TEMP_FILE_SUFFIX = ".tmp"
        private const val CACHE_FILE_PREFIX = "hve_"
        private const val MIME_TYPE_V1 = ""
        private const val DEK_SIZE_BYTES = 32
        private const val U16_MAX_VALUE = 65_535
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_ALGORITHM = "AES"

        /** Header blob: magic 4 B + versi 1 B + nonce 12 B + wrapMethod 1 B + u16 wrappedLen 2 B. */
        private const val HEADER_SIZE_BYTES = 4 + 1 + VaultFormat.CONTENT_NONCE_SIZE + 1 + 2
    }
}

/** Sinyal internal struktur blob `.hve` rusak; selalu dipetakan ke [VaultError.CorruptEntry]. */
private class VaultBlobFormatException(detail: String) : RuntimeException(detail)
