import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension

// Publishing lives here, not in qaidfeedback/build.gradle.kts: apps build that module from
// source with their own plugin classpath, which has no publishing plugin on it. Only this
// standalone build loads the plugin and applies it to :qaidfeedback.
//
//   ./gradlew --no-daemon :qaidfeedback:publishToMavenLocal      no keys needed
//   ./gradlew --no-daemon :qaidfeedback:publishToMavenCentral    Central Portal upload
//
// Central needs mavenCentralUsername / mavenCentralPassword (a Portal user token) and a
// signing key: signingInMemoryKey (+ signingInMemoryKeyPassword, optional
// signingInMemoryKeyId), or signing.keyId / signing.password / signing.secretKeyRingFile.
// Set them in ~/.gradle/gradle.properties or as ORG_GRADLE_PROJECT_<name> env vars.
plugins {
    id("com.android.library") apply false
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.11" apply false
}

/** Sign only when a key is present, so a local publish works on a machine without one. */
val hasSigningKey = listOf("signingInMemoryKey", "signing.keyId", "signing.gnupg.keyName")
    .any { providers.gradleProperty(it).isPresent }

project(":qaidfeedback") {
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
            coordinates("dev.qaid", "feedback", "0.2.0")
            publishToMavenCentral()
            if (hasSigningKey) signAllPublications()
            pom {
                name.set("qaid feedback for Android")
                description.set(
                    "In-app feedback for qaid.dev: a screenshot marked up on qaid's annotate page, " +
                        "or a screen recording, sent to your qaid project's inbox.",
                )
                url.set("https://github.com/qaiddev/qaid-native")
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
                    url.set("https://github.com/qaiddev/qaid-native")
                    connection.set("scm:git:https://github.com/qaiddev/qaid-native.git")
                    developerConnection.set("scm:git:ssh://git@github.com/qaiddev/qaid-native.git")
                }
            }
        }
    }
}

// Coverage, also from here and not the module's own build, for the same reason as publishing:
// an app that builds :qaidfeedback from source has no Kover on its classpath.
//
//   ./gradlew --no-daemon :qaidfeedback:koverVerifyCore          the gate (dev.qaid.feedback.core)
//   ./gradlew --no-daemon :qaidfeedback:koverXmlReportCore       the gated scope, as XML
//   ./gradlew --no-daemon :qaidfeedback:koverHtmlReportDebug     everything, internal/ included
//
// `core` is the pure, JVM-tested package; internal/ and QaidFeedback.kt need a device, so
// they are reported but not gated. ../scripts/coverage-android.sh runs all three.
//
// The gate is one point under the measured numbers, never under 95; raise it with them.
// The branches still missed are Kotlin's null checks inside `?.` chains on calls that
// never return null (`value?.trim()?.take(n)`), which no input can reach.
// -PcoverageLineGate / -PcoverageBranchGate override it, to check the gate fails.
val coreLineGate = providers.gradleProperty("coverageLineGate").map(String::toInt).getOrElse(99)
val coreBranchGate = providers.gradleProperty("coverageBranchGate").map(String::toInt).getOrElse(97)

project(":qaidfeedback") {
    pluginManager.withPlugin("com.android.library") {
        pluginManager.apply("org.jetbrains.kotlinx.kover")
        extensions.configure<KoverProjectExtension> {
            currentProject {
                createVariant("core") { add("debug") }
            }
            reports {
                variant("core") {
                    filters { includes { packages("dev.qaid.feedback.core") } }
                    verify {
                        rule("core lines") { bound { minValue = coreLineGate; coverageUnits = CoverageUnit.LINE } }
                        rule("core branches") { bound { minValue = coreBranchGate; coverageUnits = CoverageUnit.BRANCH } }
                    }
                }
            }
        }
    }
}
