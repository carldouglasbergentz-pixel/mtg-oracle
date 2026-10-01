// data/mtg.db over JDBC: the schema and its migrations (Schema, MtgDb.migrate),
// reads, deck writes, game records, and the sync that fills it from upstream.
plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-test-fixtures` // DbFixture: a throwaway copy of mtg.db, shared with :app's tests
}

dependencies {
    api(project(":core"))
    implementation(libs.sqlite.jdbc)
    implementation(libs.serialization.json) // card_faces, and the sync's upstream JSON
    testFixturesImplementation(libs.sqlite.jdbc)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}
