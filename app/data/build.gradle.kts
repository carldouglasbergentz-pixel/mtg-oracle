// data/mtg.db over JDBC. Read-only, except for inserting `games` rows.
// Python's scripts/self_heal.py owns every migration; this module only checks
// that the schema it needs is there (SchemaCheck).
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
