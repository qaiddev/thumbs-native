// Standalone build for the qaid feedback Android library — for its own unit tests.
//
// An app consumes the module by SOURCE, not through this build: it includes
// `:qaidfeedback` with `projectDir` pointing here, so the module compiles with the app's
// own AGP and Kotlin (both apps pin AGP 9.4.0 / Kotlin 2.4.10, the same as below). That
// sidesteps the plugin-classloader clashes an `includeBuild` of an Android build invites.
pluginManagement {
    repositories { gradlePluginPortal(); google(); mavenCentral() }
    plugins {
        id("com.android.library") version "9.4.0"
    }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "qaid-feedback-android"
include(":qaidfeedback")
