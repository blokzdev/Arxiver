plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "dev.blokz.arxiver"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.blokz.arxiver"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
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
            isMinifyEnabled = true
            isShrinkResources = true
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
        }
    }
    lint {
        warningsAsErrors = true
        abortOnError = true
        // Version-currency checks break CI whenever upstream releases; bumps are deliberate.
        disable += listOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable", "OldTargetApi")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:database"))
    implementation(project(":core:network"))
    implementation(project(":core:ml"))
    implementation(project(":core:search"))
    implementation(project(":core:claude"))

    implementation(libs.androidx.core.ktx)
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
    ksp(libs.hilt.compiler)
    ksp(libs.hilt.androidx.compiler)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.timber)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.runtime)
}
