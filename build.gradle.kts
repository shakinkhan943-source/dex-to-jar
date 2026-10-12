import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

allprojects {
    group = "riad.codes.vortex"
    version = "0.1.0"
}

// JitPack serves the modules under `com.github.<user>.<repo>:<module>:<tag>`; the `group`/`version`
// above are what land in each POM, so the published metadata stays self-describing.

// Version-catalog accessors are only generated for this script, so the coordinates every module
// needs are captured once here and reused below.
val junitBom = libs.junit.bom.get()
val junitJupiter = libs.junit.jupiter.get()
val junitLauncher = libs.junit.launcher.get()
val kotestAssertions = libs.kotest.assertions.get()
val coroutinesTest = libs.kotlinx.coroutines.test.get()

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "maven-publish")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
        // JitPack's virtual repository picks up the `-sources.jar` next to the binary jar, which is
        // what lets `IDEs` and decompilers show something readable for a downloaded dependency.
        withSourcesJar()
    }

    // Byte-identical jars make a release diffable and JitPack's cached artifacts verifiable.
    tasks.withType<Jar>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

    extensions.configure<PublishingExtension> {
        publications {
            create<MavenPublication>("maven") {
                groupId = project.group.toString()
                artifactId = project.name
                version = project.version.toString()
                from(components["java"])
            }
        }
    }

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = TestExceptionFormat.FULL
            showStandardStreams = false
        }
        maxHeapSize = "1g"
    }

    dependencies {
        add("testImplementation", platform(junitBom))
        add("testImplementation", junitJupiter)
        add("testImplementation", kotestAssertions)
        add("testImplementation", coroutinesTest)
        add("testRuntimeOnly", junitLauncher)
    }
}
