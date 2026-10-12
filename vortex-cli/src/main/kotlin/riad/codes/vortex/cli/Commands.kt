package riad.codes.vortex.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import riad.codes.vortex.dex.DexIngestion
import riad.codes.vortex.dex.DexLoader
import riad.codes.vortex.dex.DexToHir
import riad.codes.vortex.dex.DexToHirOptions
import riad.codes.vortex.dex.readEntryHeader
import riad.codes.vortex.engine.TranslationEngine
import riad.codes.vortex.engine.TranslationOptions
import riad.codes.vortex.engine.TranslationRequest
import riad.codes.vortex.ir.ProgramSource
import riad.codes.vortex.plugin.PluginRegistry
import java.nio.file.Path

/** `vortex translate input.apk -o output.jar` */
class TranslateCommand : CliktCommand(name = "translate") {

    override fun help(context: Context): String =
        "Translate every class of a DEX file, APK/JAR or OAT container into a JVM jar."

    private val input by argument("input", help = "DEX file, APK/JAR holding .dex entries, or an OAT container")

    private val output by option("-o", "--output", help = "Output jar path (default: <input>.jar)")
    private val pluginIds by option("--plugin", "-p", help = "Plugin to run; repeatable")
        .multiple()
    private val threads by option("-j", "--threads", help = "Classes translated at once (default: all cores)")
    private val noVerify by option("--no-verify", help = "Skip the JVM type checker on emitted classes")
        .flag()
    private val noDebugInfo by option("--no-debug-info", help = "Drop DEX debug info (line numbers, local names)")
        .flag()
    private val failFast by option("--fail-fast", help = "Stop at the first class that cannot be translated")
        .flag()
    private val strict by option("--strict", help = "Exit with code $STRICT_EXIT_CODE if anything was stubbed")
        .flag()
    private val verbose by option("-v", "--verbose", help = "Print every class in the report")
        .flag()

    override fun run() {
        val inputPath = Path.of(input)
        val options = TranslationOptions(
            plugins = pluginIds,
            verify = !noVerify,
            parallelism = parseThreads(threads),
            failFast = failFast,
            conversion = DexToHirOptions(includeDebugInfo = !noDebugInfo),
        )
        val report = TranslationEngine().translateBlocking(
            TranslationRequest(input = inputPath, output = outputPath(inputPath), options = options),
        )
        println(report.render(verbose))

        if (strict && !report.isFullyTranslated) {
            throw StrictModeFailure(
                "${report.stubbedMethods.size} method(s) replaced by stubs and ${report.failedClasses.size} " +
                    "class(es) failed; the jar at ${report.output} is incomplete",
            )
        }
    }

    private fun outputPath(inputPath: Path): Path {
        output?.let { return Path.of(it) }
        val name = inputPath.fileName?.toString() ?: return Path.of("vortex.jar")
        val base = name.substringBeforeLast('.', name)
        val parent = inputPath.toAbsolutePath().parent
        return parent.resolve("$base.jar")
    }

    private fun parseThreads(raw: String?): Int {
        if (raw == null) return Runtime.getRuntime().availableProcessors()
        return raw.toIntOrNull()?.takeIf { it > 0 }
            ?: throw IllegalArgumentException("--threads must be a positive integer, got '$raw'")
    }
}

/** `vortex plugins` */
class PluginsCommand : CliktCommand(name = "plugins") {

    override fun help(context: Context): String =
        "List the plugins this build knows about, including ones discovered on the classpath."

    override fun run() {
        val registry = PluginRegistry.discover()
        println("plugins (enable with --plugin <id>):")
        registry.all().forEach { plugin ->
            println("  ${plugin.id.padEnd(22)} ${plugin.description}")
        }
    }
}

/** `vortex inspect input.apk` */
class InspectCommand : CliktCommand(name = "inspect") {

    override fun help(context: Context): String =
        "Read a container's DEX headers and count what a translation would cover, without writing anything."

    private val input by argument("input", help = "DEX file, APK/JAR holding .dex entries, or an OAT container")
    private val listClasses by option("--classes", help = "Also print every class name").flag()

    override fun run() {
        val path = Path.of(input)
        val ingestion = DexIngestion()
        val container = ingestion.discoverBlocking(path)
        val loader = DexLoader()
        val converter = DexToHir()

        println("input      : $path")
        println("container  : ${container.kind.displayName}")
        println("dex entries: ${container.entries.size}")
        container.header?.let { println("header     : $it") }

        var classCount = 0
        var methodCount = 0
        var methodsWithCode = 0
        var fieldCount = 0

        container.entries.forEach { entry ->
            val dexFile = loader.load(container, entry)
            val header = runCatching { readEntryHeader(path, entry.name) }.getOrNull()
            val program = converter.convert(
                dexFile,
                ProgramSource(
                    archiveName = container.sourceName,
                    dexEntryName = container.entryLabel(entry),
                    dexVersion = header?.version ?: 0,
                    checksum = header?.checksum ?: 0L,
                    classCount = 0,
                    dexIndex = entry.dexIndex,
                ),
            )
            classCount += program.classes.size
            methodCount += program.methodCount()
            fieldCount += program.fieldCount()
            methodsWithCode += program.classes.sumOf { hirClass -> hirClass.methods.count { it.body != null } }

            val headerText = header?.let { ", DEX ${it.version}, checksum 0x${it.checksum.toString(16)}" } ?: ""
            println("  ${container.entryLabel(entry)}: ${program.classes.size} class(es)$headerText")
            if (listClasses) {
                program.classes.sortedBy { it.type.internalName }.forEach { println("    ${it.type.internalName}") }
            }
        }

        println(
            "totals     : $classCount class(es), $methodCount method(s) " +
                "($methodsWithCode with code), $fieldCount field(s)",
        )
    }
}
