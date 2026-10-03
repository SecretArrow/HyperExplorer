package com.hyperexplorer.core.common

/**
 * Strategi penanganan konflik nama saat copy/move (padanan perilaku file manager klasik).
 */
enum class ConflictStrategy {
    /** Timpa berkas/folder tujuan. */
    OVERWRITE,

    /** Ganti nama otomatis: "nama (1).ext", "nama (2).ext", dst. */
    RENAME,

    /** Lewati item yang namanya sudah ada di tujuan. */
    SKIP,
}
