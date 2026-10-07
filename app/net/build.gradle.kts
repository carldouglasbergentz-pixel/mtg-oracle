// Network play's wire: the messages between a host's engine and a remote
// seat, one JSON object per line, and the links that carry them. Speaks only
// core's plain data; no Forge, no Compose, no database (docs/adr/0002).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core"))
    implementation(libs.serialization.json)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}
