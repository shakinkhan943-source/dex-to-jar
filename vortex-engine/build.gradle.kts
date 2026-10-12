dependencies {
    api(project(":vortex-ir"))
    api(project(":vortex-dex"))
    api(project(":vortex-analysis"))
    api(project(":vortex-emit"))
    api(project(":vortex-plugin"))

    implementation(libs.kotlin.stdlib)
    // `translate` and `translateBlocking` are the public entry points, so a consumer that drives the
    // engine needs coroutines (runBlocking, launch, tests) on its compile classpath.
    api(libs.kotlinx.coroutines.core)

    testImplementation(testFixtures(project(":vortex-dex")))
    testImplementation(libs.asm)
    testImplementation(libs.asm.tree)
}
