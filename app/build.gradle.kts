// Step-1 spike for docs/adr/0001: Forge embedded in a Kotlin/Compose desktop app.
//
// Forge is a local file dependency on the pinned install in ../tools/forge —
// never vendored, never copied. Every JVM this build launches gets a sandbox:
// APPDATA / LOCALAPPDATA point under build/forge-sandbox, so Forge's user
// data (prefs, logs, decks) never touches the real %APPDATA%\Forge.

plugins {
    kotlin("jvm") version "2.4.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
    id("org.jetbrains.compose") version "1.12.1"
}

repositories {
    mavenCentral()
    google()
}

val forgeDir: File = rootDir.resolve("../tools/forge").canonicalFile
val forgeJar: File = forgeDir.resolve("forge-gui-desktop-2.0.14-jar-with-dependencies.jar")
val sandboxDir: File = layout.buildDirectory.dir("forge-sandbox").get().asFile
val gameLogDir: File = layout.buildDirectory.dir("game-logs").get().asFile

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(files(forgeJar))
}

kotlin {
    jvmToolchain(25)
}

// The user's exported decks, copied read-only into the sandbox. Forge is
// pointed at the copies; the originals are never opened by Forge.
val realDecksDir = File(System.getenv("APPDATA") ?: "", "Forge/decks/constructed")
val prepareSandbox by tasks.registering(Copy::class) {
    from(realDecksDir) {
        include("UW Draw Go - Control.dck", "Rakdos Midrange.dck", "Rakdos Midrange (AI).dck")
    }
    into(sandboxDir.resolve("decks"))
}

tasks.withType<JavaExec>().configureEach {
    dependsOn(prepareSandbox)
    workingDir = sandboxDir
    doFirst {
        sandboxDir.resolve("appdata").mkdirs()
        sandboxDir.resolve("localappdata").mkdirs()
    }
    // Forge's defaults for userDir/cacheDir come from these on Windows.
    environment("APPDATA", sandboxDir.resolve("appdata").path)
    environment("LOCALAPPDATA", sandboxDir.resolve("localappdata").path)
    systemProperty("mtgoracle.forgeDir", forgeDir.path)
    systemProperty("mtgoracle.sandbox", sandboxDir.path)
    systemProperty("mtgoracle.logDir", gameLogDir.path)
    systemProperty("mtgoracle.pngOut", layout.buildDirectory.file("spike-board.png").get().asFile.path)
    jvmArgs(
        "-Xmx4096m",
        "-Dfile.encoding=UTF-8",
        "-Dio.netty.tryReflectionSetAccessible=true",
        // Forge's own Main sets this: its comparators violate the TimSort contract.
        "-Djava.util.Arrays.useLegacyMergeSort=true",
        "--enable-native-access=ALL-UNNAMED",
    )
}

compose.desktop {
    application {
        mainClass = "mtgoracle.MainKt"
        args += listOf("window", "human")
    }
}

fun registerSpikeTask(name: String, description: String, vararg mainArgs: String) =
    tasks.register<JavaExec>(name) {
        group = "spike"
        this.description = description
        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("mtgoracle.MainKt")
        args(*mainArgs)
    }

registerSpikeTask("runAi", "Window: watch AI vs AI.", "window", "ai")
registerSpikeTask("spikeScripted", "Headless: scripted human seat vs AI; writes the log and spike-board.png.", "scripted").configure {
    // A fixed shuffle so the evidence run is repeatable; -Pseed=N to try another.
    systemProperty("mtgoracle.seed", findProperty("seed")?.toString() ?: "20260929")
}
registerSpikeTask("spikeAi", "Headless: AI vs AI through the same seam; writes the log.", "headless-ai")
