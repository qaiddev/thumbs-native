import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar

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
