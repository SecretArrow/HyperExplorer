package com.hyperexplorer.core.common

import java.io.File

/**
 * Utilitas manipulasi path dan nama berkas.
 */
object PathUtils {

    /** Buang karakter berbahaya dari nama berkas/folder yang diketik pengguna. */
    fun sanitizeName(name: String): String {
        val cleaned = name.replace(Regex("[/\\\\]"), "_").trim()
        return cleaned.ifEmpty { "untitled" }
    }

    /**
     * Cari nama unik di [dir]: jika [desired] sudah ada, hasilnya "nama (1).ext",
     * "nama (2).ext", dst. (padanan perilaku auto-rename klasik).
     */
    fun uniqueName(dir: File, desired: String): String {
        val base = sanitizeName(desired)
        if (!File(dir, base).exists()) return base
        val dot = base.lastIndexOf('.')
        val stem = if (dot > 0) base.substring(0, dot) else base
        val ext = if (dot > 0) base.substring(dot) else ""
        var counter = 1
        while (File(dir, "$stem ($counter)$ext").exists()) {
            counter++
        }
        return "$stem ($counter)$ext"
    }

    /** Nama induk dari path (nama folder langsung di atasnya). */
    fun parentName(path: String): String = path.trimEnd('/').substringBeforeLast('/').substringAfterLast('/')
}
