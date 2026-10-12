dependencies {
    api(project(":vortex-ir"))
    api(project(":vortex-emit"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.asm)
    implementation(libs.asm.commons)
    implementation(libs.asm.tree)
}
