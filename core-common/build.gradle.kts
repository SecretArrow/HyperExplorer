plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core-model"))
    testImplementation(libs.junit)
}
