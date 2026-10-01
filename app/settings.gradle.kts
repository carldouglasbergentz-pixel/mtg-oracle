rootProject.name = "mtg-oracle-app"

// The modules are the layers (CLAUDE.md "Layers"); the direction of
// dependency is one-way: app -> ui, forge, data -> core.
include(":core", ":data", ":forge", ":ui", ":app")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}
