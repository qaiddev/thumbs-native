// qaid.dev feedback for Android apps: a PixelCopy screenshot marked up on qaid's hosted
// annotate page, or a MediaProjection screen recording, sent to the project's inbox.
//
// Apps include this module by source with their own AGP and Kotlin, so this file applies
// nothing but com.android.library. Maven Central publishing is wired from the standalone
// root build (../build.gradle.kts), which apps never load.
//
// AGP 9 builds Kotlin itself — no kotlin-android plugin, and the Kotlin DSL nests in
// `android { kotlin { } }`.
plugins {
    id("com.android.library")
}

android {
    namespace = "dev.qaid.feedback"
    compileSdk = 37
    defaultConfig {
        // Matches both host apps; PixelCopy(Window) is API 26.
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        // Left alone, AGP stamps the AAR with minCompileSdk = compileSdk (37), refusing every
        // app on 35 or 36. 35 is the true floor: core/activity below need it.
        aarMetadata { minCompileSdk = 35 }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlin { jvmToolchain(17) }
}

dependencies {
    // Held below the releases that raise the compileSdk an app needs, so an app compiling
    // against SDK 35 can take the library: core 1.17+ / activity 1.11+ force 36, core 1.19
    // and okhttp-android 5.5 force 37. Apps on newer versions simply resolve those instead.
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    // WebMessageListener: page → app messages scoped to the annotate page's origin.
    implementation("androidx.webkit:webkit:1.15.0")
    // `api`: QaidFeedback.networkInterceptor() returns an okhttp3.Interceptor.
    api("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation("junit:junit:4.13.2")
    // android.jar's org.json is a stub under JVM unit tests; this is the real one.
    testImplementation("org.json:json:20240303")
}
