// qaid.dev feedback for Android apps: a PixelCopy screenshot marked up on qaid's hosted
// annotate page, or a MediaProjection screen recording, sent to the project's inbox.
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
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlin { jvmToolchain(17) }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
    // WebMessageListener: page → app messages scoped to the annotate page's origin.
    implementation("androidx.webkit:webkit:1.15.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation("junit:junit:4.13.2")
    // android.jar's org.json is a stub under JVM unit tests; this is the real one.
    testImplementation("org.json:json:20240303")
}
