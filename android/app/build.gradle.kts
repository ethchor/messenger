import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Your release signing key, if you've made one: android/keystore.properties
// (never committed) names the keystore file and its passwords. See
// docs/DEPLOY.md.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.qwantarc.messenger"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.qwantarc.messenger"
        minSdk = 26
        targetSdk = 35
        // Hours since 1970: a newer build installs over an older one, wherever
        // either was built.
        versionCode = (System.currentTimeMillis() / 3_600_000).toInt()
        versionName = "0.1.0"
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without your own key, the debug key, so a release build still installs.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    // The fingerprint and screen lock prompt for app lock. It needs a
    // FragmentActivity, and on Android 8 an AppCompat theme for its dialog.
    // The explicit fragment version replaces the old one biometric pulls in.
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.appcompat)
    // Maps for location sharing: open source, no API key, OpenStreetMap data.
    implementation(libs.maplibre.android)
    // Push without Google: a UnifiedPush distributor app (ntfy, for one)
    // hands out a Web Push endpoint and wakes the app; WorkManager runs the
    // sync a push asks for.
    implementation(libs.unifiedpush.connector)
    implementation(libs.androidx.work.runtime)
    // Videos: Transformer re-encodes them to 720p H.264 before sending (and
    // drops what the camera wrote about them); ExoPlayer plays them.
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zxing.core)
    debugImplementation(libs.androidx.ui.tooling)
}
