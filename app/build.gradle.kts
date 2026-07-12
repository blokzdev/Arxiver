import com.android.build.api.variant.BuildConfigField

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "dev.blokz.arxiver"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.blokz.arxiver"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "2.0.2"

        // P-Prove: the seeding hook (ArxiverApplication) reads BuildConfig.ENABLE_TEST_CORPUS. Default OFF here,
        // flipped ON ONLY for the ephemeral non-debuggable benchmark variants via androidComponents below — so
        // release AND debug stay false (release must never seed the production DB; debug shouldn't pollute the
        // developer's real library). buildConfig is already enabled.
        buildConfigField("boolean", "ENABLE_TEST_CORPUS", "false")
    }

    // CI release signing: keystore + credentials arrive via environment
    // (see .github/workflows/release.yml). Local builds stay debug-signed.
    val releaseKeystore = System.getenv("ARXIVER_KEYSTORE_FILE")?.let(::file)
    if (releaseKeystore != null) {
        signingConfigs {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("ARXIVER_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ARXIVER_KEY_ALIAS")
                keyPassword = System.getenv("ARXIVER_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Code/resource shrinking is intentionally OFF for sideload (see
            // Decision log 2026-06-14): R8 obfuscation broke two reflection-based
            // libs in signed builds we can't device-test in CI (Hilt multibindings,
            // then DataStore's bundled protobuf). Shrinking buys ~nothing for a
            // single-user sideloaded app; re-enabling it is a deliberate, device-
            // tested task. proguardFiles + keep rules stay so re-enabling is one flag.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // One JVM per test class. Several Robolectric tests touch process-global
            // singletons (WorkManager via WorkManagerTestInitHelper, the DataStore-by-name
            // instance); same-@Config classes otherwise share a sandbox, so that state
            // leaked across classes and caused rare order-dependent CI failures on merge
            // commits (CI #42 release-variant, #46 debug-variant) that never reproduced
            // locally. Per-class isolation removes the cross-class contamination.
            all { it.setForkEvery(1) }
        }
    }
    lint {
        warningsAsErrors = true
        abortOnError = true
        // Test sources aren't shipped, and the Kotlin-FIR lint analyzer crashes
        // intermittently on Robolectric/Hilt test files (e.g. OnboardingFlowTest) —
        // skip them entirely rather than chase a flaky upstream analyzer bug.
        ignoreTestSources = true
        // Version-currency checks break CI whenever upstream releases; bumps are deliberate.
        disable += listOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable", "OldTargetApi")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// P-Prove PP.3: flip ENABLE_TEST_CORPUS ON for ONLY the two profileable measurement variants the baselineprofile
// plugin auto-creates (they aren't visible in the buildTypes{} DSL, so the override goes through the variant API,
// which runs after the plugin creates them). benchmarkRelease runs Startup/Frame/SearchTrace; nonMinifiedRelease
// runs BaselineProfileGenerator (if left false the generated profile would capture EmptyState, not the seeded feed).
// defaultConfig/release/debug stay false — release must never seed the production DB.
androidComponents {
    onVariants(selector().all()) { variant ->
        if (variant.buildType in setOf("benchmarkRelease", "nonMinifiedRelease")) {
            variant.buildConfigFields?.put(
                "ENABLE_TEST_CORPUS",
                BuildConfigField(
                    "boolean",
                    true,
                    "Benchmark measurement variants seed the deterministic corpus (PP.3).",
                ),
            )
        }
    }
}

// Consumer side of androidx.baselineprofile. Generation stays OFF during ordinary builds so
// `./gradlew build` never needs a device/GMD (belt-and-suspenders with `skipgeneration=true` in
// gradle.properties). The PP.5 device session generates + commits app/src/main/baseline-prof.txt.
baselineProfile {
    automaticGenerationDuringBuild = false
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:database"))
    implementation(project(":core:network"))
    implementation(project(":core:ml"))
    implementation(project(":core:search"))
    implementation(project(":core:claude"))
    implementation(project(":core:ai"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    implementation(libs.androidx.work.runtime)
    // Room KTX for the suspend `withTransaction` used by the P-Tools terminal write (PT.0).
    implementation(libs.androidx.room.ktx)
    ksp(libs.hilt.compiler)
    ksp(libs.hilt.androidx.compiler)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.timber)
    // Markdown parsing for rich AI output (P-Rich): pure-JVM, offline, no Android/Compose coupling.
    implementation(libs.commonmark)
    implementation(libs.commonmark.gfm.tables)

    // P-Prove: applies the committed baseline profile AOT at install (from the :macrobenchmark producer).
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":macrobenchmark"))
    // P-Prove PP.3b: the hybrid_search async section + hybrid_fuse slice in SearchViewModel.
    implementation(libs.androidx.tracing.ktx)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.runtime)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.espresso.core)
    testImplementation(libs.hilt.android.testing)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.okhttp.mockwebserver)
    kspTest(libs.hilt.compiler)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
