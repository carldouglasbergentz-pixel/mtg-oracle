rootProject.name = "mtg-oracle-app"

// The modules are the layers (CLAUDE.md "Layers"); the direction of
// dependency is one-way: app -> ui, forge, data, net -> core.
include(":core", ":data", ":forge", ":ui", ":net", ":app")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}
