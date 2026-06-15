plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.blokz.arxiver.core.ai"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // LiteRT-LM 0.13.1 ships Kotlin 2.3.0 metadata; our toolchain is 2.1.21 (reads up
        // to 2.2.0). We only consume its public API, so read the newer metadata rather than
        // force a project-wide Kotlin upgrade. Scoped to this module alone.
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:common"))
    // :core:network exposes OkHttp via `api`; the cloud transports reuse the
    // shared client rather than pulling a second copy.
    implementation(project(":core:network"))
    // On-device LLM (P1.2): reuse the model downloader/state from :core:ml and run
    // Gemma via LiteRT-LM. These heavy deps live here (the AI module) and only reach :app.
    implementation(project(":core:ml"))
    implementation(libs.litertlm.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.security.crypto)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}
