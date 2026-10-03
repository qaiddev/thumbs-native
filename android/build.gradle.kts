import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension

// Publishing lives here, not in qaidthumbs/build.gradle.kts: apps build that module from
// source with their own plugin classpath, which has no publishing plugin on it. Only this
// standalone build loads the plugin and applies it to :qaidthumbs.
//
//   ./gradlew --no-daemon :qaidthumbs:publishToMavenLocal      no keys needed
//   ./gradlew --no-daemon :qaidthumbs:publishToMavenCentral    Central Portal upload
//
// Central needs mavenCentralUsername / mavenCentralPassword (a Portal user token) and a
// signing key: signingInMemoryKey (+ signingInMemoryKeyPassword, optional
// signingInMemoryKeyId), or signing.keyId / signing.password / signing.secretKeyRingFile.
// Set them in ~/.gradle/gradle.properties or as ORG_GRADLE_PROJECT_<name> env vars.
plugins {
    id("com.android.library") apply false
    id("org.jetbrains.kotlin.jvm") apply false
    id("org.jetbrains.kotlin.plugin.compose") apply false
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.11" apply false
}

/** Sign only when a key is present, so a local publish works on a machine without one. */
val hasSigningKey = listOf("signingInMemoryKey", "signing.keyId", "signing.gnupg.keyName")
    .any { providers.gradleProperty(it).isPresent }

project(":qaidthumbs") {
    // After AGP, which the plugin configures (publishing variant, sources, javadoc).
    pluginManager.withPlugin("com.android.library") {
        pluginManager.apply("com.vanniktech.maven.publish")
        extensions.configure<MavenPublishBaseExtension> {
            configure(
                AndroidSingleVariantLibrary(
                    javadocJar = JavadocJar.Javadoc(),
                    sourcesJar = SourcesJar.Sources(),
                    variant = "release",
                ),
            )
            coordinates("dev.qaid", "thumbs", "0.3.0")
            publishToMavenCentral()
            if (hasSigningKey) signAllPublications()
            pom {
                name.set("qaid thumbs for Android")
                description.set(
                    "Native in-app feedback for qaid.dev: thumbs up or down with a screenshot marked " +
                        "up in a Jetpack Compose editor, or a screen recording, sent to your qaid project's inbox.",
                )
                url.set("https://github.com/qaiddev/thumbs-native")
                inceptionYear.set("2026")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("qaiddev")
                        name.set("qaiddev")
                        url.set("https://qaid.dev")
                    }
                }
                scm {
                    url.set("https://github.com/qaiddev/thumbs-native")
                    connection.set("scm:git:https://github.com/qaiddev/thumbs-native.git")
                    developerConnection.set("scm:git:ssh://git@github.com/qaiddev/thumbs-native.git")
                }
            }
        }
    }
}

// Coverage, also from here and not the module's own build, for the same reason as publishing:
// an app that builds :qaidthumbs from source has no Kover on its classpath.
//
//   ./gradlew --no-daemon :qaidthumbs:koverVerifyCore          the gate (dev.qaid.thumbs.core)
//   ./gradlew --no-daemon :qaidthumbs:koverXmlReportCore       the gated scope, as XML
//   ./gradlew --no-daemon :qaidthumbs:koverHtmlReportDebug     everything, ui/ and internal/ included
//
// `core` is the pure, JVM-tested package. ui/, internal/ and QaidThumbs.kt are exercised by the
// Robolectric + compose-ui-test flows and reported separately, but not gated.
// ../scripts/coverage-android.sh runs all three.
//
// The gate is one point under the measured numbers, never under 95; raise it with them.
// The branches still missed are Kotlin's null checks inside `?.` chains on calls that
// never return null (`value?.trim()?.take(n)`), which no input can reach.
// -PcoverageLineGate / -PcoverageBranchGate override it, to check the gate fails.
val coreLineGate = providers.gradleProperty("coverageLineGate").map(String::toInt).getOrElse(99)
val coreBranchGate = providers.gradleProperty("coverageBranchGate").map(String::toInt).getOrElse(98)

project(":qaidthumbs") {
    pluginManager.withPlugin("com.android.library") {
        pluginManager.apply("org.jetbrains.kotlinx.kover")
        extensions.configure<KoverProjectExtension> {
            currentProject {
                createVariant("core") { add("debug") }
            }
            reports {
                variant("core") {
                    filters { includes { packages("dev.qaid.thumbs.core") } }
                    verify {
                        rule("core lines") { bound { minValue = coreLineGate; coverageUnits = CoverageUnit.LINE } }
                        rule("core branches") { bound { minValue = coreBranchGate; coverageUnits = CoverageUnit.BRANCH } }
                    }
                }
            }
        }
    }
}
