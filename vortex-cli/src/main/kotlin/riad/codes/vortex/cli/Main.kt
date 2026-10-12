package riad.codes.vortex.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import kotlin.system.exitProcess

/** The root command: `vortex <command>`. */
class VortexCommand : CliktCommand(name = "vortex") {

    override fun help(context: Context): String =
        "Translate Dalvik DEX bytecode into a verifiable JVM jar."

    override fun run() = Unit
}

/**
 * Entry point.
 *
 * Clikt's `parse` is used directly rather than the convenience `main` extension so that the exit
 * code contract is explicit: Clikt errors (usage mistakes, `--help`) keep their own status, and any
 * other failure — a malformed DEX, an unreadable file, a plugin error — prints one line instead of a
 * stack trace and exits 1. A stack trace is what `--stacktrace`-style debugging is for, not what a
 * build script should have to parse.
 */
fun main(args: Array<String>) {
    val command = VortexCommand().subcommands(
        TranslateCommand(),
        PluginsCommand(),
        InspectCommand(),
    )
    try {
        command.parse(args)
    } catch (error: CliktError) {
        error.message?.let { println(it) }
        if (error.statusCode != 0) exitProcess(error.statusCode)
    } catch (error: StrictModeFailure) {
        System.err.println("vortex: ${error.message}")
        exitProcess(STRICT_EXIT_CODE)
    } catch (error: Exception) {
        System.err.println("vortex: ${error.message ?: error::class.java.simpleName}")
        exitProcess(1)
    }
}

/** Exit code used by `--strict` when a jar was written but something had to be stubbed. */
const val STRICT_EXIT_CODE = 2

/** Raised by `--strict`: the output exists, but the caller asked to be told about it. */
class StrictModeFailure(message: String) : RuntimeException(message)
