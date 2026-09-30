// The house-style component kit and the screens, in Compose. Takes core's
// plain data and reports gestures back; it never sees Forge or the database.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

dependencies {
    api(project(":core"))
    api(compose.desktop.currentOs)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}
