// The Rust core (crates/ffi) as an Android library: the UniFFI Kotlin
// bindings plus libmessenger_ffi.so for each ABI, built with cargo-ndk.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.qwantarc.messenger.core"
    compileSdk = 35
    // Pinned so Gradle can install it when missing (SDK licenses accepted).
    ndkVersion = "27.2.12479018"

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("rustJniLibs"))
}

dependencies {
    // UniFFI's Kotlin bindings call into the native library through JNA.
    api("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")
}

val repoRoot: File = rootProject.projectDir.parentFile
val rustOut = layout.buildDirectory.dir("rustJniLibs")

// Debug builds use Cargo's dev profile; anything else builds --release.
val cargoBuild = tasks.register<Exec>("cargoBuild") {
    description = "Builds libmessenger_ffi.so with cargo-ndk"
    val release = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }
    inputs.dir(File(repoRoot, "crates"))
    inputs.files(File(repoRoot, "Cargo.toml"), File(repoRoot, "Cargo.lock"))
    inputs.property("release", release)
    outputs.dir(rustOut)
    workingDir = repoRoot
    environment("ANDROID_SDK_ROOT", android.sdkDirectory.absolutePath)
    // Resolved when the task runs; asking for it installs the NDK if needed.
    doFirst { environment("ANDROID_NDK_HOME", android.ndkDirectory.absolutePath) }
    commandLine(
        "bash",
        File(repoRoot, "scripts/android-build-rust.sh").absolutePath,
        rustOut.get().asFile.absolutePath,
        if (release) "release" else "debug",
    )
}

tasks.named("preBuild") { dependsOn(cargoBuild) }
