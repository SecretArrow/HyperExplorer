import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Hyper Explorer - a free and open source file manager for Android.
// Copyright (C) 2025-2026 Hyper Explorer contributors
//
// SPDX-License-Identifier: GPL-3.0-only
//
// Modul sinkronisasi folder (lokal <-> cloud/NAS): engine murni + penyimpan
// pasangan terenkripsi + WorkManager periodic + layar UI.
// Dependensi FOSS-friendly: WorkManager (Apache-2.0), androidx.security (Apache-2.0).

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "com.hyperexplorer.feature.sync"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    buildFeatures {
        compose = true
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":core-common"))
    implementation(project(":core-ui"))
    implementation(project(":data-remote"))
    implementation(project(":data-cloud"))
    implementation(project(":feature-network"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Kotlin 2.4: android.kotlinOptions deprecated-with-error -> kotlin.compilerOptions.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
