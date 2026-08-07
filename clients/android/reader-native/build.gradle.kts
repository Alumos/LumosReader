plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "xyz.alumos.lumosreader.reader.nativeview"
    compileSdk = 36
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":design"))
}
