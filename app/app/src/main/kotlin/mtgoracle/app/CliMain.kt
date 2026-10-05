package mtgoracle.app

/**
 * The release's `mtg.exe`: the command line, as `app\mtg.cmd` is from the repo. Its own
 * entry point, because a jpackage launcher's arguments are a default that any argument
 * given replaces: `mtg.exe card Sol Ring` would have lost the `cli` mode.
 */
object CliMain {
    @JvmStatic
    fun main(args: Array<String>): Unit = mtgoracle.app.main(arrayOf("cli") + args)
}
