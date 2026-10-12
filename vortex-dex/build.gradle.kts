plugins {
    `java-test-fixtures`
}

dependencies {
    // `DexToHir` and friends take dexlib2 interfaces in their public signatures, so dexlib2 is part of
    // this module's API surface rather than an implementation detail.
    api(project(":vortex-ir"))
    api(libs.dexlib2)
    implementation(libs.kotlin.stdlib)
    // `DexIngestion.discover` is a public `suspend` function, so callers compile against coroutines.
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.io.core)

    testFixturesApi(project(":vortex-ir"))
    testFixturesApi(libs.dexlib2)
}
