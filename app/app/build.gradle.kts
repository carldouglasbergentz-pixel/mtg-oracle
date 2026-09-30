// The entry point: wires data, forge and ui into the window, plus a few
// headless modes (scripted evidence run, image prefetch, schema check).
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":data"))
    implementation(project(":forge"))
    implementation(project(":ui"))
    testImplementation(testFixtures(project(":data")))
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

val repoRoot = rootProject.extra["repoRoot"] as File
val forgeAssets = rootProject.extra["forgeAssets"] as File

compose.desktop {
    application {
        mainClass = "mtgoracle.app.MainKt"
        args += listOf("window")
    }
}

tasks.withType<JavaExec>().configureEach {
    // data/ holds mtg.db, game_logs/ and the app's home (app/: Forge user data,
    // image cache, settings). -Pdata=<dir> points a run at a copy instead.
    systemProperty("mtgoracle.data", repoRoot.resolve(findProperty("data")?.toString() ?: "data").path) // relative to the repo root
    systemProperty("mtgoracle.forgeAssets", forgeAssets.path)
    systemProperty("mtgoracle.evidence", layout.buildDirectory.dir("evidence").get().asFile.path)
}

fun registerMode(name: String, description: String, vararg mainArgs: String) =
    tasks.register<JavaExec>(name) {
        group = "application"
        this.description = description
        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("mtgoracle.app.MainKt")
        args(*mainArgs)
        findProperty("args")?.toString()?.split(' ')?.filter { it.isNotBlank() }?.let { args(it) }
    }

// ---------------------------------------------------------------------------
// installLocal: the build the user plays. A running JVM loads classes lazily
// from its jars and class dirs, so `gradlew run` breaks when a later build or
// test replaces them (NoClassDefFoundError: ClickTarget$Less, mid-game). A
// snapshot is a copy of everything the app loads (jars, Forge's assets) in
// app/dist/<timestamp>/, which only this task writes; app/run-mtg-oracle.cmd
// runs the newest (app/dist/current names it). It uses the repo's data/.
// ---------------------------------------------------------------------------
val distRoot: File = rootDir.resolve("dist")
val keptSnapshots = 3
val forgeJvmArgs = rootProject.extra["forgeJvmArgs"] as List<*>
val snapshotJava = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }

fun git(vararg command: String): String? = runCatching {
    val process = ProcessBuilder("git", *command).directory(repoRoot).redirectErrorStream(true).start()
    process.inputStream.bufferedReader().readText().trim().takeIf { process.waitFor() == 0 }
}.getOrNull()

fun slash(file: File) = file.absolutePath.replace('\\', '/')

/** Everything [jars] (in classpath order) and Forge's assets need to run, as [snapshot], with launchers. */
fun writeSnapshot(snapshot: File, jars: List<File>, version: String, java: File) {
    // The position prefixes each name: the classpath order decides between duplicate classes in
    // Forge's fat jar, and androidx and JetBrains Compose both ship a runtime-desktop-1.12.1.jar.
    val copied = jars.mapIndexed { i, jar -> jar.copyTo(snapshot.resolve("lib").resolve("%03d-%s".format(i, jar.name))) }
    forgeAssets.copyRecursively(snapshot.resolve("forge-assets"))
    // An argument file, because the classpath is too long for cmd.exe's command line.
    val args = forgeJvmArgs.map { it.toString() } + listOf(
        "-Xmx4g",
        "-Dmtgoracle.data=${slash(repoRoot.resolve("data"))}",
        "-Dmtgoracle.forgeAssets=${slash(snapshot.resolve("forge-assets"))}",
        "-Dmtgoracle.version=$version",
        "-cp", copied.joinToString(File.pathSeparator) { slash(it) },
        "mtgoracle.app.MainKt",
    )
    snapshot.resolve("jvm.args").writeText(args.joinToString("\n") { "\"$it\"" } + "\n")
    snapshot.resolve("run.cmd").writeText("@echo off\r\nrem MTG Oracle $version\r\n\"${slash(java)}\" @\"%~dp0jvm.args\" %*\r\n")
    snapshot.resolve("run.sh").writeText("#!/bin/sh\n# MTG Oracle $version\nexec \"${slash(java)}\" @\"\$(dirname \"\$0\")/jvm.args\" \"\$@\"\n")
    snapshot.resolve("VERSION").writeText("$version\n")
}

tasks.register("installLocal") {
    group = "application"
    description = "Installs a runnable snapshot of the app into app/dist/ (run it with app/run-mtg-oracle.cmd); keeps the newest $keptSnapshots."
    dependsOn(tasks.named("jar"), rootProject.tasks.named("prepareForgeAssets"), configurations.named("runtimeClasspath"))
    val appJar = tasks.named<Jar>("jar").flatMap { it.archiveFile }
    val runtime = configurations.named("runtimeClasspath")
    outputs.upToDateWhen { false } // every run is a new snapshot
    doLast {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val commit = git("rev-parse", "--short", "HEAD") ?: "no-git"
        val dirty = git("status", "--porcelain")?.isNotEmpty() ?: false
        val version = "$commit${if (dirty) "-dirty" else ""} $stamp"
        // `current` is written last, so a failed install is never what the launcher runs. (Building under a
        // temporary name and renaming it failed on Windows: the virus scanner still holds the fresh files.)
        val snapshot = distRoot.resolve(stamp)
        try {
            writeSnapshot(snapshot, listOf(appJar.get().asFile) + runtime.get().files, version, snapshotJava.get().executablePath.asFile)
        } catch (e: Exception) {
            snapshot.deleteRecursively()
            throw e
        }
        distRoot.resolve("current").writeText(stamp)
        logger.lifecycle("installed MTG Oracle $version in $snapshot")

        // Keep the newest few. A snapshot still running can't be renamed on Windows (its jars are open):
        // renaming first means a running game never loses files under it; it is pruned next time.
        distRoot.listFiles { f -> f.isDirectory && f.name.matches(Regex("\\d{8}-\\d{6}")) }.orEmpty()
            .sortedByDescending { it.name }.drop(keptSnapshots).filter { it.name != stamp }.forEach { old ->
                val doomed = distRoot.resolve(".pruning-${old.name}")
                if (old.renameTo(doomed)) { doomed.deleteRecursively(); logger.lifecycle("pruned ${old.name}") }
                else logger.warn("kept ${old.name}: it is in use (a running game?); pruned on a later install")
            }
        distRoot.listFiles { f -> f.name.startsWith(".pruning-") }.orEmpty().forEach { it.deleteRecursively() }
    }
}

registerMode("checkSchema", "Checks that data/mtg.db has the schema this app needs.", "check-schema")
registerMode("prefetchImages", "Downloads card art for the cards in your decks (-Pargs=\"<deck name>\" for one).", "prefetch")
registerMode("stagedPictures", "Headless: the staged boards (table, lands, stack box, trail, hidden info, reveal, watch) rendered with real art; -Pargs=\"<board names>\" for some.", "snapshots")
registerMode("scriptedGame", "Headless: a scripted seat plays through the UI against the AI; use -Pdata=<copy>.", "scripted")
