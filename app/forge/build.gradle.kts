// Every import of Forge lives in this module, behind core's interfaces
// (GameSeat, CardArt, ...). Forge's internal API is not stable; this is the
// one place a Forge upgrade can break.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

val forgeJar = rootProject.extra["forgeJar"] as File

dependencies {
    api(project(":core"))
    implementation(files(forgeJar))
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}
