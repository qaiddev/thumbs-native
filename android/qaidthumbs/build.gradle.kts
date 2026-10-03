// qaid.dev thumbs for Android apps: a PixelCopy screenshot marked up in a native editor, or a
// MediaProjection screen recording, sent with a thumb and a message to the project's inbox.
// The sheet and the editor are Jetpack Compose (Material 3) — no web view.
//
// Apps include this module by source with their own AGP and Kotlin, so this file applies only
// what an app build already has: com.android.library and the Compose compiler plugin (from
// Kotlin 2.0 the Compose compiler ships with Kotlin and is versioned with it). Maven Central
// publishing and Kover are wired from the standalone root build (../build.gradle.kts), which
// apps never load.
//
// AGP 9 builds Kotlin itself — no kotlin-android plugin, and the Kotlin DSL nests in
// `android { kotlin { } }`.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.qaid.thumbs"
    compileSdk = 37
    defaultConfig {
        // Matches both host apps; PixelCopy(Window) is API 26.
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        // Left alone, AGP stamps the AAR with minCompileSdk = compileSdk (37), refusing every
        // app on 35 or 36. 35 is the true floor: Compose 1.11 and core/activity below need it.
        aarMetadata { minCompileSdk = 35 }
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlin { jvmToolchain(17) }
    testOptions {
        unitTests {
            // Robolectric reads the merged manifest and resources.
            isIncludeAndroidResources = true
            all { it.maxHeapSize = "1536m" }
        }
    }
}

dependencies {
    // Held below the releases that raise the compileSdk an app needs, so an app compiling
    // against SDK 35 can take the library: Compose 1.12 (BOM 2026.05+) forces 37, core 1.17+ /
    // activity 1.11+ force 36, core 1.19 and okhttp-android 5.5 force 37. Apps on newer
    // versions simply resolve those instead. The same pins as qaid quests for Android.
    implementation(platform("androidx.compose:compose-bom:2026.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.core:core-ktx:1.16.0")
    // ComponentDialog hosts the sheet; the activity result registry asks to record.
    implementation("androidx.activity:activity-ktx:1.10.1")
    // `api`: QaidThumbs.networkInterceptor() returns an okhttp3.Interceptor.
    api("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation("junit:junit:4.13.2")
    // android.jar's org.json is a stub under JVM unit tests; this is the real one.
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation(platform("androidx.compose:compose-bom:2026.04.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    // The empty ComponentActivity that createComposeRule() launches. Debug-only, so it never
    // reaches the release AAR.
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
