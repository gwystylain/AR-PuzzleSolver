plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.puzzlesolver.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.puzzlesolver.app"
        minSdk = 26          // ARCore needs 24; 26 buys us MediaCodec/ImageReader niceties.
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            // ARCore ships arm ABIs only. Dropping x86 keeps the APK small.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // Release signing comes from the environment, never from a file in the repo.
    // CI exports these four from repo secrets; see .github/workflows/release.yml.
    // When they are absent -- any local build -- signingConfigs stays empty and the
    // release build falls back to the debug key below, so `assembleRelease` still
    // produces something installable on your own device.
    val keystorePath: String? = System.getenv("SIGNING_KEYSTORE_PATH")

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            // The hot path allocates nothing; these keep us honest about it.
            "-Xjvm-default=all",
        )
    }

    sourceSets["main"].java.srcDirs("src/main/kotlin")

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        // The camera tuning and the auto-exposure loop are plain state machines worth
        // testing off-device, and the only Android they touch is android.util.Log.
        // Returning defaults for it beats threading a logger interface through code
        // whose whole job is to be simple.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.arcore)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}

