plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.blokz.arxiver.core.pdf"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        // Robolectric needs the merged Android assets so PDFBoxResourceLoader.init can load pdfbox's bundled
        // font/glyphlist resources (its AAR assets) during the extract round-trip test.
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// The isolation module for pdfbox-android (Phase P-Reader2 Track A). Deliberately depends ONLY on
// :core:common + coroutines — NO :core:network / okhttp — so PdfboxNoNetworkStructuralTest has a small,
// meaningful walk-root and the ~7MB pdfbox dependency (added in PFT.5.2) stays off the pure :core:ai path.
dependencies {
    implementation(project(":core:common"))
    api(libs.kotlinx.coroutines.core)
    // FULL bundle — the extractor's public API is File -> String, so no pdfbox type leaks (implementation, not api).
    implementation(libs.pdfbox.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
