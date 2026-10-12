package riad.codes.vortex.plugin

import riad.codes.vortex.emit.EmittedClass
import riad.codes.vortex.ir.HirProgram

/**
 * A hook into the translation pipeline.
 *
 * Plugins get two points, because transformations naturally live at two levels:
 *
 * * [transformProgram] runs on the *semantic* model before analysis. This is where a plugin changes
 *   what the engine sees — dropping classes, repairing metadata, adding annotations — with full type
 *   information available.
 * * [transformBytecode] runs on each emitted class. This is where ASM shines: renaming, normalising
 *   and instrumenting are all routine `ClassVisitor` work once real bytecode exists, and doing them
 *   here means a plugin never has to understand Dalvik registers.
 *
 * A plugin instance is used for a single translation run, so it may keep state between its program
 * pass and its bytecode passes (that is how a renaming plan computed from the whole program reaches
 * the per-class rewrite). [transformBytecode] can be called concurrently.
 */
interface VortexPlugin {

    /** Stable identifier used by `--plugin <id>` on the command line. */
    val id: String

    /** One line explanation, shown by `vortex plugins`. */
    val description: String

    /** Runs once, before any class is translated. */
    fun transformProgram(program: HirProgram, context: PluginContext): HirProgram = program

    /** Runs once per emitted class, after bytecode generation. */
    fun transformBytecode(emitted: EmittedClass, context: PluginContext): EmittedClass = emitted

    /** Runs once after the last class has been emitted. */
    fun onFinish(context: PluginContext) = Unit
}

/**
 * Collects what a plugin observed, so it ends up in the translation report rather than on stderr.
 *
 * A plugin must not print: the CLI owns the output format, and a library user may well be parsing it.
 */
class PluginContext {

    private val messages = ArrayList<String>()

    /** Records a note attributed to [pluginId]. */
    @Synchronized
    fun record(pluginId: String, message: String) {
        messages += "[$pluginId] $message"
    }

    @Synchronized
    fun notes(): List<String> = messages.toList()
}
