// The entry point: wires data, forge and ui into the window, plus the
// headless modes (Main.kt lists them: the CLI, sync, migrate, schema check,
// image prefetch, the scripted evidence run, staged snapshots).
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

dependencies {
    implementation(libs.serialization.json) // the CLI's --json
    implementation(project(":core"))
    implementation(project(":data"))
    implementation(project(":forge"))
    implementation(project(":ui"))
    implementation(project(":net"))
    testImplementation(testFixtures(project(":data")))
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

val repoRoot = rootProject.extra["repoRoot"] as File
val forgeAssets = rootProject.extra["forgeAssets"] as File
val forgeDir = rootProject.extra["forgeDir"] as File

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

// ---------------------------------------------------------------------------
// packageRelease: the app as a folder anyone can unpack and run, and its zip.
// `MTG Oracle.exe` (no console) and `mtg.exe` (the command line) from jpackage,
// with a Java runtime of its own, so the machine needs no JDK. Every path is
// the package's own: the data sits beside the exe in data/, Forge's assets and
// the points lists inside app/. -PreleaseVersion=0.1.0 (numbers only, as
// jpackage wants) names it; the build stamps the commit beside it.
// ---------------------------------------------------------------------------
val releaseRoot: File = layout.buildDirectory.dir("release").get().asFile
// What jdeps finds the jars need, and what they load only at run time (charsets, locale data, zip, TLS, accessibility).
val releaseModules = listOf(
    "java.base", "java.compiler", "java.desktop", "java.instrument", "java.management", "java.naming", "java.net.http",
    "java.rmi", "java.scripting", "java.security.jgss", "java.sql", "jdk.httpserver", "jdk.sctp", "jdk.unsupported",
    "jdk.charsets", "jdk.localedata", "jdk.zipfs", "jdk.accessibility", "jdk.crypto.ec", "jdk.crypto.cryptoki",
    "java.logging", "java.prefs", "java.xml", "jdk.net",
)

/** [folder] and everything in it as [zip], under the folder's own name; an empty folder too (data\\exports\\ before anything is exported). */
fun zipFolder(folder: File, zip: File) {
    ZipOutputStream(zip.outputStream().buffered()).use { out ->
        folder.walkTopDown().forEach { file ->
            val name = folder.name + "/" + file.relativeTo(folder).invariantSeparatorsPath
            if (file.isDirectory) {
                if (file.listFiles().isNullOrEmpty()) { out.putNextEntry(ZipEntry("$name/")); out.closeEntry() }
                return@forEach
            }
            out.putNextEntry(ZipEntry(name))
            file.inputStream().use { it.copyTo(out) }
            out.closeEntry()
        }
    }
}

tasks.register("packageRelease") {
    group = "distribution"
    description = "Builds the release folder (MTG Oracle.exe, mtg.exe, its own Java) and its zip in app/app/build/release/: -PreleaseVersion=0.1.0."
    dependsOn(tasks.named("jar"), rootProject.tasks.named("prepareForgeAssets"), configurations.named("runtimeClasspath"))
    val appJar = tasks.named<Jar>("jar").flatMap { it.archiveFile }
    val runtime = configurations.named("runtimeClasspath")
    outputs.upToDateWhen { false }
    doLast {
        val release = findProperty("releaseVersion")?.toString()
            ?: throw GradleException("name the release: -PreleaseVersion=0.1.0")
        require(release.matches(Regex("""\d+\.\d+\.\d+"""))) { "a release version is numbers only, as jpackage wants: $release" }
        val commit = git("rev-parse", "--short", "HEAD") ?: "no-git"
        val dirty = git("status", "--porcelain")?.isNotEmpty() == true
        // -PallowDirty for a trial build; a release goes out from a commit, which the version names.
        if (dirty && findProperty("allowDirty") == null) throw GradleException("commit first: a release is built from a clean tree (-PallowDirty for a trial)")
        // jpackage refuses a destination that exists; a folder still open (a running release) can't go.
        // jpackage's launchers are read-only, which a plain delete can't remove.
        releaseRoot.walkBottomUp().forEach { it.setWritable(true) }
        if (releaseRoot.exists() && !releaseRoot.deleteRecursively()) throw GradleException("can't clear $releaseRoot: is the release running?")
        val input = releaseRoot.resolve("input")
        // The jars in classpath order, as installLocal copies them: Forge's fat jar and Compose share classes.
        (listOf(appJar.get().asFile) + runtime.get().files).forEachIndexed { i, jar -> jar.copyTo(input.resolve("%03d-%s".format(i, jar.name))) }
        forgeAssets.copyRecursively(input.resolve("forge-assets"))
        repoRoot.resolve("data/formats").copyRecursively(input.resolve("formats"))
        val cli = releaseRoot.resolve("mtg.properties").apply {
            writeText("main-class=mtgoracle.app.CliMain\nwin-console=true\n")
        }
        val javaOptions = forgeJvmArgs.map { it.toString() } + listOf(
            "-Xmx4g",
            "-Dmtgoracle.data=\$APPDIR/../data",
            "-Dmtgoracle.forgeAssets=\$APPDIR/forge-assets",
            "-Dmtgoracle.formats=\$APPDIR/formats",
            // No spaces: jpackage's launcher splits a java option on them, and "(abc1234)" became the main class.
            "-Dmtgoracle.version=$release+$commit",
            "-Dmtgoracle.release=$release",
            // Where the package is, for the update to swap (Updates); data\ beside it is kept.
            "-Dmtgoracle.install=\$APPDIR/..",
        )
        val jpackage = snapshotJava.get().executablePath.asFile.resolveSibling("jpackage.exe")
        val command = listOf(
            jpackage.path, "--type", "app-image", "--name", "MTG Oracle", "--app-version", release,
            "--input", input.path, "--main-jar", "000-${appJar.get().asFile.name}", "--main-class", "mtgoracle.app.MainKt",
            "--add-modules", releaseModules.joinToString(","),
            "--jlink-options", "--strip-debug --no-header-files --no-man-pages",
            "--add-launcher", "mtg=${cli.path}", "--dest", releaseRoot.path,
        ) + javaOptions.flatMap { listOf("--java-options", it) }
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val said = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) throw GradleException("jpackage failed:\n$said")
        val image = releaseRoot.resolve("MTG Oracle")
        // The licence and credits beside the exe, and the Forge inside named by its commit: the GPL's source goes with the binary.
        repoRoot.resolve("LICENSE").copyTo(image.resolve("LICENSE.txt"), overwrite = true)
        repoRoot.resolve("NOTICE.md").copyTo(image.resolve("NOTICE.txt"), overwrite = true)
        val forgeCommit = forgeDir.resolve("build.txt").takeIf { it.isFile }?.readLines()?.getOrNull(1)?.substringBefore(' ')
            ?: throw GradleException("no Forge commit in ${forgeDir.resolve("build.txt")}: stage Forge with tools/stage_forge.py")
        image.resolve("FORGE-SOURCE.txt").writeText(
            "MTG Oracle $release embeds Forge (GPL-3.0), built from commit $forgeCommit of\r\n" +
                "https://github.com/carldouglasbergentz-pixel/forge\r\n" +
                "Its source: https://github.com/carldouglasbergentz-pixel/forge/tree/$forgeCommit\r\n",
        )
        // data\ as the app makes it on a first start, there before it: where an export goes, where a package
        // to import goes (its README says how), and the playmats. An update never touches data\ (Updates).
        val data = image.resolve("data")
        listOf("exports", "playmats").forEach { data.resolve(it).mkdirs() }
        data.resolve("import").mkdirs()
        data.resolve("import/README.txt").writeText(
            project.file("src/main/resources/mtgoracle/app/import-README.txt").readText().lines().joinToString("\r\n"),
        )
        val zip = releaseRoot.resolve("MTG-Oracle-$release-windows-x64.zip")
        zipFolder(image, zip)
        val sha = MessageDigest.getInstance("SHA-256").digest(zip.readBytes()).joinToString("") { "%02x".format(it) }
        releaseRoot.resolve("${zip.name}.sha256").writeText("$sha  ${zip.name}\n")
        input.deleteRecursively()
        logger.lifecycle("MTG Oracle $release ($commit): $image\n  $zip\n  SHA-256 $sha")
    }
}

registerMode("checkSchema", "Checks, read-only, that data/mtg.db has its schema version's every table and column.", "check-schema")
registerMode("migrate", "Brings data/mtg.db to this build's schema version, as the app does at start (backup first).", "migrate")
registerMode("cli", "The command line: -Pargs=\"card Sol Ring --json\" (or app\\mtg.cmd card Sol Ring --json).", "cli")
registerMode("sync", "Fetches what moved upstream into data/mtg.db: -Pargs=\"[--force] [cards rules combos tags oracletags formats printings]\".", "sync")
registerMode("prefetchImages", "Downloads card art for the cards in your decks (-Pargs=\"<deck name>\" for one).", "prefetch")
registerMode("stagedPictures", "Headless: the staged boards (table, lands, stack box, trail, hidden info, reveal, watch) rendered with real art; -Pargs=\"<board names>\" for some.", "snapshots")
registerMode("localDuel", "Network play on this machine: a host's window and a guest's, over a loopback link: -Pargs=\"<host deck>;<guest deck>\".", "local-duel")
registerMode("scriptedGame", "Headless: a scripted seat plays through the UI against the AI; use -Pdata=<copy>.", "scripted")
