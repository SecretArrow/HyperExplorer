// Hyper Explorer - a free and open source file manager for Android.
// Copyright (C) 2025-2026 Hyper Explorer contributors
//
// SPDX-License-Identifier: GPL-3.0-only
//
// Modul klien cloud FOSS: Nextcloud (WebDAV + Login flow v2).
// Murni JVM (tanpa Android) agar mudah diuji; dependensi FOSS-friendly:
// OkHttp (Apache-2.0), kotlinx-serialization (Apache-2.0).

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // api() agar konsumen :data-cloud otomatis melihat kontrak RemoteFileSystem.
    api(project(":data-remote"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver)
}
