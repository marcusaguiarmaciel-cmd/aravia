import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Firebase values of the Android app registered in the aravia-7b078 project (Firebase console ›
// Project settings › the Android app). Kept in firebase.properties; empty until then, and the
// app says the account server is not set up yet.
val firebase = Properties().apply {
    val file = rootProject.file("firebase.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "app.aravia"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.aravia.watch"
        minSdk = 29
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0-beta.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
        buildConfigField("Boolean", "IS_BETA", "true")
        buildConfigField("Boolean", "SHOW_BETA_SUPPORT", "true")
        buildConfigField("String", "FIREBASE_API_KEY", "\"${firebase.getProperty("apiKey", "")}\"")
        buildConfigField("String", "FIREBASE_APP_ID", "\"${firebase.getProperty("appId", "")}\"")
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${firebase.getProperty("projectId", "aravia-7b078")}\"")
        // Strava app credentials from the build's environment (the repository's secrets); empty without them.
        buildConfigField("String", "STRAVA_CLIENT_ID", "\"${System.getenv("STRAVA_CLIENT_ID") ?: ""}\"")
        buildConfigField("String", "STRAVA_CLIENT_SECRET", "\"${System.getenv("STRAVA_CLIENT_SECRET") ?: ""}\"")
        // Google AdMob optional Rewarded Ads (2 ads -> 24h Premium; falls back to Google's official test IDs until secrets are configured).
        val admobAppId = System.getenv("ADMOB_APP_ID")?.takeIf { it.isNotBlank() } ?: "ca-app-pub-3940256099942544~3347511713"
        val admobRewardedId = System.getenv("ADMOB_REWARDED_UNIT_ID")?.takeIf { it.isNotBlank() } ?: "ca-app-pub-3940256099942544/5224354917"
        val admobIsTest = System.getenv("ADMOB_REWARDED_UNIT_ID").isNullOrBlank()
        manifestPlaceholders["admobAppId"] = admobAppId
        buildConfigField("String", "ADMOB_REWARDED_UNIT_ID", "\"$admobRewardedId\"")
        buildConfigField("Boolean", "ADMOB_USING_TEST_IDS", admobIsTest.toString())
        // LGPD controller document injected from CI secret LEGAL_CONTROLLER_DOC (never committed).
        buildConfigField("String", "LEGAL_CONTROLLER_DOC", "\"${System.getenv("LEGAL_CONTROLLER_DOC") ?: ""}\"")
        // Keep only Portuguese & English strings from Play Services / AndroidX libraries.
        resourceConfigurations += listOf("pt", "pt-rBR", "en")
    }

    signingConfigs {
        // The test key in the repository: every build is signed the same way, so a new APK
        // installs over the last one. Google Play signs its own copies with its own key.
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            // The same key for every build, from the repository's secrets, or Android refuses an
            // update over the installed app.
            val path = System.getenv("ARAVIA_KEYSTORE")
            if (path != null) {
                storeFile = file(path)
                storePassword = System.getenv("ARAVIA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ARAVIA_KEY_ALIAS")
                keyPassword = System.getenv("ARAVIA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-rules.pro",
            )
            // Real Android 10+ devices are ARM; omit x86/x86_64 emulator binaries (~23 MB saved)
            // while keeping debug universal so CI x86_64 emulator tests continue to run.
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
            signingConfig = if (System.getenv("ARAVIA_KEYSTORE") != null) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }

    packaging {
        // Compress native .so and .dex entries inside the direct-download APK (Google Play
        // re-packs AABs automatically later). Cuts APK download size dramatically.
        jniLibs { useLegacyPackaging = true }
        dex { useLegacyPackaging = true }
        resources {
            excludes += listOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")

    implementation(project(":protocol"))
    // Universal watch support via Android Health Connect (Galaxy Watch, Garmin, Amazfit, Xiaomi, Polar, Fitbit, Wear OS).
    implementation("androidx.health.connect:connect-client:1.1.0")
    // Optional opt-in Rewarded Ads (2 ads -> 24h Premium, no in-app purchases).
    implementation("com.google.android.gms:play-services-ads:23.6.0")
    // QR scanner from Play services: no camera permission, the scan runs outside the app.
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    // Route maps: OpenStreetMap through MapLibre, the same map as on iOS.
    implementation("org.maplibre.gl:android-sdk:11.8.8")
    testImplementation("junit:junit:4.13.2")
}
