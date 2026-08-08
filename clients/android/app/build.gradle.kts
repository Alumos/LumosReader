plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "xyz.alumos.lumosreader"
    compileSdk = 36

    defaultConfig {
        applicationId = "xyz.alumos.lumosreader"
        minSdk = 23
        targetSdk = 36
        versionCode = providers.gradleProperty("versionCode").orNull?.toIntOrNull() ?: 1
        // Tagged release builds override this with -PversionName from android-vX.Y.Z.
        // Untagged local/CI APKs must identify themselves as development builds.
        versionName = providers.gradleProperty("versionName").orNull ?: "0.1.3-dev"
    }

    signingConfigs {
        create("release") {
            val store = providers.environmentVariable("ANDROID_KEYSTORE_FILE").orNull
            if (store != null) {
                storeFile = file(store)
                storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (providers.environmentVariable("ANDROID_KEYSTORE_FILE").isPresent) signingConfig = signingConfigs.getByName("release")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    lint { abortOnError = true }
    packaging { jniLibs.useLegacyPackaging = false }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":reader-native"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
}
