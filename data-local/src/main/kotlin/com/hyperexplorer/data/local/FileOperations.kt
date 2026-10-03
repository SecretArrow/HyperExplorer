package com.hyperexplorer.data.local

import com.hyperexplorer.core.common.ConflictStrategy
import com.hyperexplorer.core.common.PathUtils
import java.io.File
import java.io.IOException

/**
 * Operasi berkas inti: copy, move, delete, rename, mkdir.
 * Mendukung strategi konflik nama ala file manager klasik:
 * OVERWRITE / RENAME / SKIP.
 */
object FileOperations {
    fun copy(
        src: File,
        dstDir: File,
        strategy: ConflictStrategy = ConflictStrategy.RENAME,
    ): File {
        require(src.exists()) { "Sumber tidak ditemukan: $src" }
        require(dstDir.isDirectory) { "Tujuan bukan folder: $dstDir" }
        val target = resolveTarget(src, dstDir, strategy) ?: return src
        if (src.isDirectory) {
            copyDirectory(src, target)
        } else {
            copyFile(src, target)
        }
        return target
    }

    fun move(
        src: File,
        dstDir: File,
        strategy: ConflictStrategy = ConflictStrategy.RENAME,
    ): File {
        require(src.exists()) { "Sumber tidak ditemukan: $src" }
        require(dstDir.isDirectory) { "Tujuan bukan folder: $dstDir" }
        val target = resolveTarget(src, dstDir, strategy) ?: return src
        if (!src.renameTo(target)) {
            // renameTo gagal lintas volume (mis. internal -> SD card): fallback copy + delete
            if (src.isDirectory) {
                copyDirectory(src, target)
            } else {
                copyFile(src, target)
            }
            src.deleteRecursively()
        }
        return target
    }

    fun delete(file: File): Boolean = file.deleteRecursively()

    fun rename(
        file: File,
        newName: String,
    ): File {
        require(file.exists()) { "Sumber tidak ditemukan: $file" }
        val parent = file.parentFile ?: throw IOException("Tidak ada folder induk")
        val target = File(parent, PathUtils.sanitizeName(newName))
        if (target.exists() && target != file) {
            throw IOException("Nama sudah dipakai: ${target.name}")
        }
        if (!file.renameTo(target)) {
            throw IOException("Gagal mengganti nama: ${file.name}")
        }
        return target
    }

    fun mkdir(
        dir: File,
        name: String,
    ): File {
        require(dir.isDirectory) { "Tujuan bukan folder: $dir" }
        val target = File(dir, PathUtils.uniqueName(dir, PathUtils.sanitizeName(name)))
        if (!target.mkdirs() && !target.isDirectory) {
            throw IOException("Gagal membuat folder: ${target.name}")
        }
        return target
    }

    /**
     * Resolusi nama target sesuai strategi konflik.
     * Mengembalikan null bila operasi harus dilewati (SKIP dan nama sudah ada).
     */
    private fun resolveTarget(
        src: File,
        dstDir: File,
        strategy: ConflictStrategy,
    ): File? {
        val candidate = File(dstDir, src.name)
        if (!candidate.exists()) return candidate
        return when (strategy) {
            ConflictStrategy.OVERWRITE -> {
                candidate.deleteRecursively()
                candidate
            }
            ConflictStrategy.RENAME -> File(dstDir, PathUtils.uniqueName(dstDir, src.name))
            ConflictStrategy.SKIP -> null
        }
    }

    fun copyFile(
        src: File,
        target: File,
    ) {
        src.inputStream().use { input ->
            target.outputStream().use { output ->
                input.copyTo(output, bufferSize = DEFAULT_BUFFER_SIZE * 8)
            }
        }
        target.setLastModified(src.lastModified())
    }

    fun copyDirectory(
        src: File,
        target: File,
    ) {
        if (!target.mkdirs() && !target.isDirectory) {
            throw IOException("Gagal membuat folder: $target")
        }
        val children = src.listFiles() ?: return
        for (child in children) {
            val childTarget = File(target, child.name)
            if (child.isDirectory) {
                copyDirectory(child, childTarget)
            } else {
                copyFile(child, childTarget)
            }
        }
    }
}
