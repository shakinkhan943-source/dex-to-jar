plugins {
    application
}

dependencies {
    implementation(project(":vortex-engine"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.clikt)
    // This module is the runnable console entry point. Keep ASM, dexlib2, ASM analysis/tree and
    // vortex-analysis reachable on the compile classpath so a dependency on this module can drive a
    // full pipeline without pulling each transitive piece in by hand.
    implementation(libs.clikt)
}

application {
    mainClass.set("riad.codes.vortex.cli.MainKt")
    applicationName = "vortex"
}
