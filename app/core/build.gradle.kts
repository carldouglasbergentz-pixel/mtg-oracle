// Pure logic and the plain data every other module speaks: the board and
// prompt model, decks, AI copies, phase stops, game records. No I/O, no Forge,
// no Compose — the equivalent of the Python pure layer.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(libs.coroutines.core) // StateFlow is the seam's observable
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}
