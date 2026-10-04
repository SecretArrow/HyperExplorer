// Hyper Explorer - a free and open source file manager for Android.
// Copyright (C) 2025-2026 Hyper Explorer contributors
//
// SPDX-License-Identifier: GPL-3.0-only
//
// Modul klien jaringan: FTP, SFTP, SMB, dan WebDAV.
// Murni JVM (tanpa Android) agar mudah diuji; semua klien FOSS-friendly:
// commons-net (Apache-2.0), sshj (Apache-2.0), SMBJ (Apache-2.0), OkHttp (Apache-2.0).

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.commons.net)
    implementation(libs.sshj)
    implementation(libs.smbj)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
