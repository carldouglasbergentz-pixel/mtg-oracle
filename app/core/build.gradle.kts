// Pure logic and the plain data every other module speaks: the board and
// prompt model, decks, AI copies, phase stops, game records. No I/O, no Forge,
// no Compose. The seam's types are @Serializable: a remote seat sends them as
// they are (net, docs/adr/0002).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(libs.coroutines.core) // StateFlow is the seam's observable
    api(libs.serialization.core)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}
