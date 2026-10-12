pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    // smali-dexlib2 is published to Google's Maven repository, everything else to Maven Central.
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "vortex-dex"

include(
    "vortex-ir",
    "vortex-dex",
    "vortex-analysis",
    "vortex-emit",
    "vortex-plugin",
    "vortex-engine",
    "vortex-cli",
)
