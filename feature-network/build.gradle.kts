// Hyper Explorer - a free and open source file manager for Android.
// Copyright (C) 2025-2026 Hyper Explorer contributors
//
// SPDX-License-Identifier: GPL-3.0-only
//
// Modul UI lokasi jaringan: kelola sambungan (SMB/FTP/SFTP/WebDAV) dan jelajahi isinya.
// Kredensial disimpan terenkripsi via androidx.security (EncryptedSharedPreferences, Apache-2.0).

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "com.hyperexplorer.feature.network"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation(project(":data-remote"))
    implementation(project(":data-cloud"))
    implementation(project(":core-model"))
    implementation(project(":core-common"))

    // Konstruktor NextcloudLoginFlow mengekspos okhttp3.OkHttpClient — modul ini
    // butuh tipe tersebut di classpath kompilasi (pemanggilan default argumen).
    implementation(libs.okhttp)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
