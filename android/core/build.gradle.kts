// The Rust core (crates/ffi) as an Android library: the UniFFI Kotlin
// bindings plus libmessenger_ffi.so for each ABI, built with cargo-ndk.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

val minSdkVersion = 26

android {
    namespace = "com.qwantarc.messenger.core"
    compileSdk = 35

    defaultConfig {
        minSdk = minSdkVersion
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

// Phones are arm64-v8a; x86_64 covers emulators on Intel hosts. When Android
// Studio deploys to a device it passes that device's ABIs, and building only
// the first one we support halves the Rust build. Other builds get both.
val supportedAbis = listOf("arm64-v8a", "x86_64")
val abis: List<String> = providers.gradleProperty("android.injected.build.abi").orNull
    ?.split(",")
    ?.map { it.trim() }
    ?.firstOrNull { it in supportedAbis }
    ?.let { listOf(it) }
    ?: supportedAbis

// Debug builds use Cargo's dev profile; anything else builds --release.
val cargoBuild = tasks.register<Exec>("cargoBuild") {
    description = "Builds libmessenger_ffi.so with cargo-ndk"
    val release = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }
    inputs.dir(File(repoRoot, "crates"))
    inputs.files(File(repoRoot, "Cargo.toml"), File(repoRoot, "Cargo.lock"))
    inputs.property("release", release)
    inputs.property("abis", abis)
    inputs.property("minSdk", minSdkVersion)
    outputs.dir(rustOut)
    workingDir = repoRoot
    environment("ANDROID_ABIS", abis.joinToString(","))
    environment("ANDROID_PLATFORM", minSdkVersion.toString())
    // The script picks the NDK: ANDROID_NDK_HOME if set, else the newest one
    // installed in this SDK. AGP only installs NDKs for CMake/ndk-build.
    environment("ANDROID_SDK_ROOT", android.sdkDirectory.absolutePath)
    commandLine(
        "bash",
        File(repoRoot, "scripts/android-build-rust.sh").absolutePath,
        rustOut.get().asFile.absolutePath,
        if (release) "release" else "debug",
    )
}

tasks.named("preBuild") { dependsOn(cargoBuild) }
