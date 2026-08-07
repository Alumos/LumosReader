plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "xyz.alumos.lumosreader.core"
    compileSdk = 36
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].java.srcDir(layout.buildDirectory.dir("generated/uniffi"))
}

val rustDirectory = rootProject.layout.projectDirectory.dir("rust")
val generatedBindings = layout.buildDirectory.dir("generated/uniffi")

val generateUniFfiBindings by tasks.registering(Exec::class) {
    inputs.files(rustDirectory.file("src/lumos_core.udl"), rustDirectory.file("src/lib.rs"))
    outputs.dir(generatedBindings)
    workingDir(rustDirectory)
    commandLine("cargo", "run", "--quiet", "--bin", "uniffi-bindgen", "--", "generate", "src/lumos_core.udl", "--language", "kotlin", "--out-dir", generatedBindings.get().asFile.absolutePath)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(generateUniFfiBindings)
}

dependencies {
    implementation("net.java.dev.jna:jna:5.17.0@aar")
}
