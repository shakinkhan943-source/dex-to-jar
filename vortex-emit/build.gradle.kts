dependencies {
    api(project(":vortex-ir"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.core)
    // `DexAwareClassWriter` extends `ClassWriter`, so ASM is part of this module's published API and
    // must stay a transitive compile dependency of anything that consumes it.
    api(libs.asm)
    implementation(libs.asm.commons)
    implementation(libs.asm.tree)
    implementation(libs.asm.analysis)
    implementation(libs.asm.util)

    testImplementation(project(":vortex-analysis"))
    testImplementation(testFixtures(project(":vortex-dex")))
}
