// MTG Oracle's JVM app (docs/adr/0001). Modules: core, data, forge, ui, app.
//
// Forge is a local file dependency of :forge only, never vendored: a build of
// the Duel Commander PR branch (Card-Forge/forge#12090), staged in the release
// layout in ../tools/forge-dc by forge-dc/tools/stage_for_mtg_oracle.py
// (README, "Forge"). Back to a release when the PR is merged and released;
// -PforgeDir=tools/forge builds against the 2.0.14 release meanwhile.
//
// Forge reads its data from an assets directory we own: build/forge-assets
// holds a copy of the install's res/ (minus what the engine never reads), and
// the app writes forge.profile.properties next to it at start-up, pointing
// Forge's user data at data/app/forge/. Nothing redirects APPDATA, and the
// user's real %APPDATA%\Forge is never written.

import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose) apply false
}

val forgeJvmArgs = listOf(
    "-Dfile.encoding=UTF-8",
    "-Dio.netty.tryReflectionSetAccessible=true",
    // Forge's own Main sets this: its comparators violate the TimSort contract.
    "-Djava.util.Arrays.useLegacyMergeSort=true",
    "--enable-native-access=ALL-UNNAMED",
)

val repoRoot: File = rootDir.resolve("..").canonicalFile
// -PforgeDir=<dir, relative to the repo root> builds against another Forge with the release layout (the jar beside res/).
val forgeDir: File = repoRoot.resolve((findProperty("forgeDir") as String?) ?: "tools/forge-dc")
val forgeAssets: File = layout.buildDirectory.dir("forge-assets").get().asFile

extra["repoRoot"] = repoRoot
extra["forgeDir"] = forgeDir
extra["forgeJar"] = forgeDir.listFiles { f -> f.name.startsWith("forge-gui-desktop-") && f.name.endsWith("-jar-with-dependencies.jar") }
    ?.singleOrNull() ?: error("no single forge-gui-desktop-*-jar-with-dependencies.jar in $forgeDir")
extra["forgeAssets"] = forgeAssets
extra["forgeJvmArgs"] = forgeJvmArgs

// Forge's res/, less the parts the engine never reads: the adventure mode
// (130 MB), music, sounds, skins and card-name translations.
val prepareForgeAssets by tasks.registering(Sync::class) {
    description = "Copies Forge's res/ into build/forge-assets for the app to own."
    from(forgeDir.resolve("res")) {
        exclude("adventure/**", "music/**", "sound/**", "skins/**", "languages/cardnames-*")
        exclude { it.path.startsWith("languages/") && it.name != "en-US.properties" && !it.isDirectory }
    }
    // The profile beside res/ is the app's to write (it knows where its home is).
    into(forgeAssets.resolve("res"))
}

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> { jvmToolchain(25) }
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        dependsOn(prepareForgeAssets)
        // One JVM per module: Forge initialises once (~7 s) and is shared.
        maxParallelForks = 1
        systemProperty("mtgoracle.repoRoot", repoRoot.path)
        systemProperty("mtgoracle.forgeAssets", forgeAssets.path)
        systemProperty("mtgoracle.testHome", layout.buildDirectory.dir("test-home").get().asFile.path)
        systemProperty("mtgoracle.pngDir", layout.buildDirectory.dir("test-png").get().asFile.path)
        // Scripted seats answer the moment a prompt shows; the guard against a stray click is tested on its own (InputGuardTest).
        systemProperty("mtgoracle.inputGuardMillis", "0")
        // SoakTest plays whole matches only when asked: -PsoakSeeds=1,2,3 [-PsoakDecks="Jori En:Phelia Doggo;A:B"].
        findProperty("soakSeeds")?.let { systemProperty("mtgoracle.soakSeeds", it.toString()) }
        findProperty("soakDecks")?.let { systemProperty("mtgoracle.soakDecks", it.toString()) }
        jvmArgs(forgeJvmArgs)
        maxHeapSize = "4g"
        testLogging {
            events("passed", "failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
    tasks.withType<JavaExec>().configureEach {
        dependsOn(prepareForgeAssets)
        jvmArgs(forgeJvmArgs)
        maxHeapSize = "4g"
    }
}

