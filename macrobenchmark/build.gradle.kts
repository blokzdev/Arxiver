plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.androidx.baselineprofile)
}

// P-Prove PP.2 — the Macrobenchmark + Baseline-Profile PRODUCER module. This PR stands up the
// scaffold only: no benchmark `.kt` yet (StartupBenchmark / FrameTimingBenchmark / SearchTraceBenchmark /
// BaselineProfileGenerator + the `benchmark` build type that targets :app's profileable variant land in PP.3).
// A plain `./gradlew build` compiles this module but never runs its instrumented tests (CI has no device).
android {
    namespace = "dev.blokz.arxiver.macrobenchmark"
    compileSdk = 35

    defaultConfig {
        // Baseline Profiles require API 28+ (below that the platform fully AOT-compiles apps anyway).
        // The target app's minSdk 26 is unaffected — this test APK is never shipped.
        minSdk = 28
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Drives the :app under test; default debug/release variants resolve :app's same-named variants.
    targetProjectPath = ":app"

    // Run the benchmark harness in its own process, isolated from the target app.
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Producer side of androidx.baselineprofile. Generation runs ONLY via explicit generateBaselineProfile
// tasks (disabled repo-wide by `androidx.baselineprofile.skipgeneration=true`), never on assemble/build —
// so this cannot drag a Gradle Managed Device / KVM onto CI. The PP.5 device session generates the real
// profile with `-Pandroidx.baselineprofile.skipgeneration=false` against the connected Samsung S20.
baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.uiautomator)
}
