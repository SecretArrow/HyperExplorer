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

package com.hyperexplorer.feature.sync.engine

import com.hyperexplorer.data.remote.RemoteEntry
import com.hyperexplorer.data.remote.RemoteFileSystem
import com.hyperexplorer.data.remote.RemotePath
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.abs

/**
 * Engine sinkronisasi satu arah folder lokal <-> remote (murni JVM, tanpa
 * import `android.*` sehingga dapat diuji lewat unit test JVM biasa).
 *
 * Keputusan desain penting (v1):
 * - NON-DESTRUKTIF: berkas yang hanya ada di sisi TUJUAN tidak pernah dihapus.
 *   PUSH membiarkan berkas remote ekstra; PULL membiarkan berkas lokal ekstra.
 *   Mirror/penghapusan dua arah sengaja ditunda ke versi berikutnya.
 * - Kegagalan per-item TIDAK menggagalkan run: kegagalan upload/unduh/mkdir/
 *   list subfolder dicatat ke [SyncStats.failures] lalu eksekusi lanjut ke
 *   item berikutnya. Hanya kegagalan fail-fast (folder lokal tidak valid,
 *   listing folder remote gagal) yang menjadi `Result.failure`.
 * - ANTI-TRAVERSAL: nama entri remote yang mengandung "..", '/', '\\', atau
 *   kosong ditolak; target unduh diverifikasi ulang via canonicalPath agar
 *   selalu di dalam folder tujuan. Pelanggaran masuk [SyncStats.failures],
 *   bukan crash.
 * - Perbandingan per path relatif: berkas dianggap berubah bila ukuran beda
 *   ATAU selisih mtime > [MTIME_TOLERANCE_MS] (toleransi granularitas
 *   timestamp filesystem); berkas yang hanya ada di satu sisi = baru.
 */
class SyncEngine(private val dispatcher: CoroutineDispatcher = Dispatchers.IO) {
    /**
     * Menjalankan satu kali sinkronisasi [direction] antara [localRoot] dan
     * [remotePath] pada koneksi [fs].
     *
     * Skenario yang ditangani:
     * - normal: berkas baru/berubah ditransfer; folder hilang dibuat per level;
     * - kedua sisi kosong: statistik nol, sukses;
     * - folder lokal tidak ada: PUSH -> `Result.failure`; PULL -> folder tujuan
     *   dibuat (`mkdirs`), dan bila gagal dibuat -> `Result.failure`;
     * - [localRoot] bukan folder (mis. berkas) -> `Result.failure`;
     * - listing folder remote gagal -> `Result.failure` berupa [IOException]
     *   (sifatnya bisa transien; keputusan retry ada pada penelepon);
     * - gagal jaringan per item / traversal -> masuk failures, run lanjut.
     *
     * @param localRoot folder lokal; PUSH = sumber, PULL = tujuan.
     * @param remotePath koordinat remote; dinormalisasi ulang via
     *   [RemotePath.normalize] sehingga aman menerima masukan bebas.
     * @param fs koneksi remote yang SUDAH terhubung; penutupan koneksi adalah
     *   tanggung jawab penelepon, bukan engine.
     * @param direction arah sinkronisasi.
     * @return `Result.success([SyncStats])` — tetap sukses meski ada failure
     *   per-item — atau `Result.failure` dengan pesan APA + MENGAPA.
     */
    suspend fun sync(
        localRoot: File,
        remotePath: String,
        fs: RemoteFileSystem,
        direction: SyncDirection,
    ): Result<SyncStats> =
        withContext(dispatcher) {
            syncInternal(localRoot, remotePath, fs, direction)
        }

    private suspend fun syncInternal(
        localRoot: File,
        remotePathRaw: String,
        fs: RemoteFileSystem,
        direction: SyncDirection,
    ): Result<SyncStats> {
        var uploaded = 0
        var downloaded = 0
        var createdDirectories = 0
        var skipped = 0
        val failures = mutableListOf<String>()

        // FAIL-FAST (1): validasi folder lokal sesuai arah.
        when (direction) {
            SyncDirection.PUSH_TO_REMOTE -> {
                if (!localRoot.exists()) {
                    return failure("Folder lokal '${localRoot.absolutePath}' tidak ada; PUSH butuh folder sumber yang valid")
                }
                if (!localRoot.isDirectory) {
                    return failure("Path lokal '${localRoot.absolutePath}' bukan folder; PUSH butuh folder sumber")
                }
            }
            SyncDirection.PULL_TO_LOCAL -> {
                if (!localRoot.exists()) {
                    // Folder TUJUAN PULL dibuat bila belum ada; gagal dibuat = failure.
                    val made =
                        try {
                            localRoot.mkdirs()
                        } catch (e: Exception) {
                            val why = e.message ?: e.javaClass.simpleName
                            return failure("Folder tujuan '${localRoot.absolutePath}' tidak ada dan gagal dibuat: $why")
                        }
                    if (!made || !localRoot.isDirectory) {
                        return failure("Folder tujuan '${localRoot.absolutePath}' tidak ada dan gagal dibuat: periksa izin penulisan")
                    }
                } else if (!localRoot.isDirectory) {
                    return failure("Path tujuan '${localRoot.absolutePath}' bukan folder; PULL butuh folder tujuan")
                }
            }
        }

        // FAIL-FAST (2): normalisasi path + listing root.
        val root = RemotePath.normalize(remotePathRaw)
        val rootEntries =
            try {
                fs.list(root)
            } catch (e: Exception) {
                return Result.failure(
                    IOException(
                        "Gagal membaca folder remote '${rootDisplay(root)}': ${e.message ?: e.javaClass.simpleName}",
                        e,
                    ),
                )
            }
        val rootCanonical =
            try {
                localRoot.canonicalPath
            } catch (e: Exception) {
                return failure("Tidak dapat menentukan path kanonik '${localRoot.absolutePath}': ${e.message ?: e.javaClass.simpleName}")
            }

        val remoteDirs = LinkedHashMap<String, RemoteEntry>()
        val remoteFiles = LinkedHashMap<String, RemoteEntry>()
        collectRemote(root, rootEntries, fs, remoteDirs, remoteFiles, failures, depth = 0)

        val localDirs = LinkedHashMap<String, File>()
        val localFiles = LinkedHashMap<String, File>()
        walkLocal(localRoot, "", localDirs, localFiles, failures, depth = 0)

        when (direction) {
            SyncDirection.PUSH_TO_REMOTE -> {
                val failedDirs = mutableSetOf<String>()
                // (a) folder lokal hilang di remote: buat PER LEVEL ("a" lalu "a/b").
                for (rel in localDirs.keys) {
                    createdDirectories += ensureRemoteDirs(rel, root, remoteDirs, failedDirs, failures)
                }
                // (b) berkas lokal baru/berubah diunggah;
                // (c) berkas remote yang TIDAK ada lokal DIBIARKAN (non-destruktif).
                for ((rel, file) in localFiles) {
                    if (hasFailedAncestor(rel, failedDirs)) {
                        failures += "$rel: dilewati karena folder induk gagal dibuat di remote"
                        skipped++
                        continue
                    }
                    val remote = remoteFiles[rel]
                    if (remote != null && !differs(file.length(), file.lastModified(), remote)) {
                        skipped++
                        continue
                    }
                    val parentRel = rel.substringBeforeLast('/', "")
                    val remoteParent = if (parentRel.isEmpty()) root else RemotePath.join(root, parentRel)
                    try {
                        fs.upload(file, remoteParent)
                        uploaded++
                    } catch (e: Exception) {
                        failures += "$rel: gagal unggah: ${e.message ?: e.javaClass.simpleName}"
                    }
                }
            }
            SyncDirection.PULL_TO_LOCAL -> {
                // (a) folder remote hilang di lokal: File.mkdirs per folder remote.
                for (rel in remoteDirs.keys) {
                    val target = resolveTarget(localRoot, rootCanonical, rel)
                    if (target == null) {
                        failures += "$rel: nama folder remote keluar dari folder tujuan, dibatalkan (anti-traversal)"
                        continue
                    }
                    if (target.isDirectory) continue
                    try {
                        if (!target.mkdirs() && !target.isDirectory) {
                            throw IOException("mkdirs gagal")
                        }
                        createdDirectories++
                    } catch (e: Exception) {
                        failures += "$rel: gagal membuat folder tujuan: ${e.message ?: e.javaClass.simpleName}"
                    }
                }
                // (b) berkas remote baru/berubah diunduh dengan guard traversal;
                // (c) berkas lokal yang TIDAK ada di remote DIBIARKAN (non-destruktif).
                for ((rel, entry) in remoteFiles) {
                    val local = localFiles[rel]
                    if (local != null && !differs(local.length(), local.lastModified(), entry)) {
                        skipped++
                        continue
                    }
                    val target = resolveTarget(localRoot, rootCanonical, rel)
                    if (target == null) {
                        failures += "$rel: nama entri remote keluar dari folder tujuan, unduh dibatalkan (anti-traversal)"
                        continue
                    }
                    val parent = target.parentFile
                    if (parent != null && !parent.isDirectory) {
                        try {
                            if (!parent.mkdirs() && !parent.isDirectory) {
                                throw IOException("mkdirs gagal")
                            }
                        } catch (e: Exception) {
                            failures += "$rel: gagal membuat folder induk: ${e.message ?: e.javaClass.simpleName}"
                            continue
                        }
                    }
                    try {
                        fs.download(entry.path, target, entry.size)
                        downloaded++
                    } catch (e: Exception) {
                        failures += "$rel: gagal unduh: ${e.message ?: e.javaClass.simpleName}"
                    }
                }
            }
        }
        return Result.success(SyncStats(uploaded, downloaded, createdDirectories, skipped, failures.toList()))
    }

    /**
     * Telusuri remote secara rekursif mulai dari [entries] (hasil listing
     * [dirPath]) menjadi peta relatif -> entri. Entri bernama tidak sah
     * (traversal/kosong) dicatat ke [failures] lalu dilewati; kegagalan list
     * subfolder juga hanya dicatat, walk tetap berlanjut.
     */
    private suspend fun collectRemote(
        dirPath: String,
        entries: List<RemoteEntry>,
        fs: RemoteFileSystem,
        dirs: MutableMap<String, RemoteEntry>,
        files: MutableMap<String, RemoteEntry>,
        failures: MutableList<String>,
        depth: Int,
    ) {
        if (depth > MAX_DEPTH) {
            failures += "${rootDisplay(dirPath)}: kedalaman folder remote melebihi $MAX_DEPTH, walk dihentikan"
            return
        }
        for (entry in entries.sortedBy { it.name }) {
            val name = entry.name
            if (name.isBlank() || name == "." || name == ".." || name.contains('/') || name.contains('\\')) {
                failures += "${entry.path}: nama entri remote tidak sah, dilewati (anti-traversal)"
                continue
            }
            val rel = relativePath(dirPath, entry.path)
            if (rel.isNullOrEmpty()) {
                failures += "${entry.path}: path entri tidak berada di dalam folder yang dipindai, dilewati"
                continue
            }
            if (entry.isDirectory) {
                if (dirs.containsKey(rel)) continue
                dirs[rel] = entry
                val children =
                    try {
                        fs.list(entry.path)
                    } catch (e: Exception) {
                        failures += "$rel: gagal membaca subfolder remote: ${e.message ?: e.javaClass.simpleName}"
                        continue
                    }
                collectRemote(entry.path, children, fs, dirs, files, failures, depth + 1)
            } else {
                files[rel] = entry
            }
        }
    }

    /** Telusuri folder lokal rekursif; berkas yang tak terbaca dicatat ke [failures]. */
    private fun walkLocal(
        dir: File,
        relPrefix: String,
        dirs: MutableMap<String, File>,
        files: MutableMap<String, File>,
        failures: MutableList<String>,
        depth: Int,
    ) {
        if (depth > MAX_DEPTH) {
            failures += "${relPrefix.ifEmpty { "." }}: kedalaman folder lokal melebihi $MAX_DEPTH, walk dihentikan"
            return
        }
        val children =
            try {
                dir.listFiles()
            } catch (e: Exception) {
                failures += "${relPrefix.ifEmpty { "." }}: gagal membaca folder lokal: ${e.message ?: e.javaClass.simpleName}"
                null
            }
        if (children == null) {
            failures += "${relPrefix.ifEmpty { "." }}: isi folder lokal tidak dapat dibaca"
            return
        }
        for (child in children.sortedBy { it.name }) {
            val rel = if (relPrefix.isEmpty()) child.name else "$relPrefix/${child.name}"
            if (child.isDirectory) {
                dirs[rel] = child
                walkLocal(child, rel, dirs, files, failures, depth + 1)
            } else if (child.isFile) {
                if (!child.canRead()) {
                    failures += "$rel: berkas lokal tidak dapat dibaca, dilewati"
                    continue
                }
                files[rel] = child
            }
            // Selain folder/berkas biasa (mis. socket) diabaikan begitu saja.
        }
    }

    /**
     * Pastikan seluruh level folder relatif [relDir] ada di remote; dibuat SATU
     * PER SATU level ("a" sebelum "a/b") dan folder yang berhasil dibuat
     * didaftarkan sebagai entri sintetis ke [remoteDirs] agar level berikutnya
     * (dan unggahan berkas di dalamnya) tahu folder tersebut sudah ada.
     *
     * @return jumlah level yang berhasil dibuat panggilan ini (0 bila semua
     *   sudah ada; parsial bila ada level gagal). Kegagalan level tercatat di
     *   [failures] dan levelnya masuk [failedDirs]; folder di bawahnya
     *   dilewati oleh pemanggil via [hasFailedAncestor].
     */
    private suspend fun ensureRemoteDirs(
        relDir: String,
        root: String,
        remoteDirs: MutableMap<String, RemoteEntry>,
        failedDirs: MutableSet<String>,
        failures: MutableList<String>,
    ): Int {
        var current = root
        var created = 0
        for (segment in relDir.split('/')) {
            val child = RemotePath.join(current, segment)
            if (failedDirs.contains(child)) return created
            if (remoteDirs.containsKey(child)) {
                current = child
                continue
            }
            try {
                fs.makeDirectory(current, segment)
            } catch (e: Exception) {
                failedDirs.add(child)
                failures += "$child: gagal membuat folder di remote: ${e.message ?: e.javaClass.simpleName}"
                return created
            }
            remoteDirs[child] = RemoteEntry(segment, child, isDirectory = true, size = 0L, lastModified = SYNTHETIC_DIR_MTIME)
            created++
            current = child
        }
        return created
    }

    /** true bila path relatif [rel] berada di dalam folder yang gagal dibuat. */
    private fun hasFailedAncestor(
        rel: String,
        failedDirs: Set<String>,
    ): Boolean = failedDirs.any { failed -> rel.startsWith("$failed/") }

    /**
     * Bandingkan metadata berkas lokal dengan entri remote: berubah bila
     * ukuran beda ATAU selisih mtime > [MTIME_TOLERANCE_MS].
     */
    private fun differs(
        localSize: Long,
        localMtime: Long,
        remote: RemoteEntry,
    ): Boolean = localSize != remote.size || abs(localMtime - remote.lastModified) > MTIME_TOLERANCE_MS

    /**
     * Resolusi [rel] menjadi [File] di dalam [localRoot] dengan guard
     * canonicalPath (anti-traversal): mengembalikan null bila rel kosong,
     * mengandung segmen berbahaya ("..", "."), backslash/NUL, atau hasil
     * canonicalPath-nya keluar dari root — pelanggaran TIDAK pernah crash.
     */
    private fun resolveTarget(
        localRoot: File,
        rootCanonical: String,
        rel: String,
    ): File? {
        if (rel.isEmpty() || rel.contains('\\') || rel.contains('\u0000')) return null
        val segments = rel.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
        val target = File(localRoot, rel)
        return try {
            val canonical = target.canonicalPath
            if (canonical == rootCanonical || canonical.startsWith("$rootCanonical${File.separator}")) target else null
        } catch (e: IOException) {
            null
        }
    }

    /** Path relatif entri [entryPath] terhadap root pindai [root]; null bila di luar. */
    private fun relativePath(
        root: String,
        entryPath: String,
    ): String? {
        val normalized = RemotePath.normalize(entryPath)
        if (root.isEmpty()) return normalized
        return if (normalized.startsWith("$root/")) normalized.removePrefix("$root/") else null
    }

    private fun rootDisplay(root: String): String = root.ifEmpty { "<root>" }

    private fun failure(message: String): Result<SyncStats> = Result.failure(IllegalStateException(message))

    private companion object {
        /** Granularitas timestamp filesystem: selisih <= nilai ini dianggap sama. */
        const val MTIME_TOLERANCE_MS = 1000L

        /** Batas kedalaman walk (lokal & remote) sebagai pengaman loop tak berujung. */
        const val MAX_DEPTH = 64

        /** Penanda mtime folder sintetis hasil [ensureRemoteDirs]. */
        const val SYNTHETIC_DIR_MTIME = 0L
    }
}
