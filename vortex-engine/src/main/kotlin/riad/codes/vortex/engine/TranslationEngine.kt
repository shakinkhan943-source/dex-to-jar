package riad.codes.vortex.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import riad.codes.vortex.analysis.MethodAnalyzer
import riad.codes.vortex.dex.DexIngestion
import riad.codes.vortex.dex.DexInput
import riad.codes.vortex.dex.DexLoader
import riad.codes.vortex.dex.DexToHir
import riad.codes.vortex.dex.readEntryHeader
import riad.codes.vortex.emit.AsmClassEmitter
import riad.codes.vortex.emit.EmittedClass
import riad.codes.vortex.emit.JarEntryData
import riad.codes.vortex.emit.JarOptions
import riad.codes.vortex.emit.JarWriter
import riad.codes.vortex.ir.HirClass
import riad.codes.vortex.ir.HirMethod
import riad.codes.vortex.ir.HirProgram
import riad.codes.vortex.ir.LirClass
import riad.codes.vortex.ir.LirMethod
import riad.codes.vortex.ir.ProgramSource
import riad.codes.vortex.ir.TranslationException
import riad.codes.vortex.plugin.PluginContext
import riad.codes.vortex.plugin.PluginRegistry
import riad.codes.vortex.plugin.VortexPlugin

/**
 * The DEX to JAR pipeline.
 *
 * ```
 * discover -> load + convert (per DEX, in parallel) -> merge -> plugin program pass
 *          -> analyse + emit + plugin bytecode pass (per class, in parallel) -> write jar
 * ```
 *
 * Where the concurrency is, and why it is there: a multi-dex APK has independent DEX entries, and
 * every class is independent of the others once the whole program has been assembled for analysis.
 * Both stages are therefore farmed out over the available cores, bounded by
 * [TranslationOptions.parallelism] so a translation can be told to leave CPU for the rest of a build.
 * The plugin *program* pass deliberately runs on the single merged program, because a whole-program
 * transformation (a rename plan, dropping classes) makes no sense per DEX file.
 *
 * A failure is never silent. A method whose body cannot be translated is emitted as a throwing stub
 * and reported; a class that cannot be emitted at all is reported in
 * [TranslationReport.failedClasses]; a caller that would rather stop can set
 * [TranslationOptions.failFast].
 */
class TranslationEngine(
    private val registry: PluginRegistry = PluginRegistry.builtIn(),
) {

    /** Translates [TranslationRequest.input] into [TranslationRequest.output], blocking. */
    fun translateBlocking(request: TranslationRequest): TranslationReport = runBlocking { translate(request) }

    /** Translates [TranslationRequest.input] into [TranslationRequest.output]. */
    suspend fun translate(request: TranslationRequest): TranslationReport = withContext(Dispatchers.Default) {
        val started = System.nanoTime()
        val options = request.options
        val warnings = ArrayList<String>()

        val input = DexIngestion(options.ingestion).discover(request.input)
        if (!input.kind.isTranslatable) {
            throw TranslationException("${input.kind.displayName} input is not translatable: ${request.input}")
        }

        val loaded = loadPrograms(input, options, warnings)
        val program = loaded.program
        if (program.classes.isEmpty()) {
            throw TranslationException("${request.input} contains no classes to translate")
        }

        val plugins = registry.select(options.plugins)
        val pluginContext = PluginContext()
        val transformed = plugins.fold(program) { current, plugin ->
            try {
                plugin.transformProgram(current, pluginContext)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                throw TranslationException("plugin '${plugin.id}' failed during its program pass: ${error.describe()}", error)
            }
        }

        val emitter = AsmClassEmitter(transformed.hierarchy, options.effectiveEmit)
        val analyzer = MethodAnalyzer(transformed, options.analysis)
        val emissions = emitAll(transformed, analyzer, emitter, plugins, pluginContext, options)

        val jarEntries = emissions.mapNotNull { it.emitted }
            .map { emitted -> JarEntryData(emitted.internalName + ".class", emitted.bytes) }
        val jarResult = withContext(Dispatchers.IO) {
            JarWriter(withManifest(options, input, emissions.size)).write(request.output, jarEntries)
        }

        plugins.forEach { plugin ->
            try {
                plugin.onFinish(pluginContext)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                warnings += "plugin '${plugin.id}' failed in onFinish: ${error.describe()}"
            }
        }

        if (jarResult.skippedDuplicates.isNotEmpty()) {
            warnings += "${jarResult.skippedDuplicates.size} duplicate class file(s) dropped from the jar"
        }

        TranslationReport(
            input = request.input,
            output = request.output,
            containerKind = input.kind,
            dexEntries = input.entries.map { input.entryLabel(it) },
            classes = emissions.mapNotNull { it.report }.sortedBy { it.name },
            failedClasses = emissions.mapNotNull { it.failure },
            duplicates = loaded.duplicates + jarResult.skippedDuplicates,
            pluginNotes = pluginContext.notes(),
            warnings = warnings,
            jarSizeBytes = jarResult.sizeBytes,
            durationMillis = (System.nanoTime() - started) / 1_000_000,
            parallelism = options.parallelism.coerceAtLeast(1),
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Loading
    // ---------------------------------------------------------------------------------------------

    private suspend fun loadPrograms(
        input: DexInput,
        options: TranslationOptions,
        warnings: MutableList<String>,
    ): LoadedProgram = coroutineScope {
        val loader = DexLoader(options.ingestion)
        val converter = DexToHir(options.conversion)
        val programs = input.entries.map { entry ->
            async(Dispatchers.Default) {
                // Parsing is I/O bound, decoding the instructions is CPU bound: keep them apart so a
                // slow disk does not idle the cores and a busy decoder does not serialise the reads.
                val dexFile = withContext(Dispatchers.IO) { loader.load(input, entry) }
                converter.convert(dexFile, programSourceOf(input, entry))
            }
        }.awaitAll()

        // A class that appears in two DEX entries is a real repackaging pattern, and the first
        // occurrence wins. Dropping the losers here, rather than emitting them and letting the jar
        // writer discard the duplicate, keeps the report, the manifest class count and the jar
        // consistent: a class the output does not contain must not be reported as translated.
        val merged = HirProgram.merge(programs)
        val seen = HashSet<String>(merged.classes.size * 2)
        val unique = ArrayList<HirClass>(merged.classes.size)
        val duplicates = ArrayList<String>()
        merged.classes.forEach { hirClass ->
            val name = hirClass.type.internalName
            if (seen.add(name)) unique += hirClass else duplicates += "$name.class"
        }
        if (input.isMultiDex && duplicates.isNotEmpty()) {
            val names = duplicates.map { it.removeSuffix(".class") }.distinct()
            warnings +=
                "${names.size} class(es) appear in more than one DEX entry " +
                    "(${names.take(5).joinToString()}${if (names.size > 5) ", ..." else ""}); " +
                    "the first occurrence wins"
        }
        LoadedProgram(merged.withClasses(unique), duplicates)
    }

    private fun programSourceOf(input: DexInput, entry: riad.codes.vortex.dex.DexArchiveEntry): ProgramSource {
        val header = input.header ?: runCatching { readEntryHeader(input.path, entry.name) }.getOrNull()
        return ProgramSource(
            archiveName = input.sourceName,
            dexEntryName = input.entryLabel(entry),
            dexVersion = header?.version ?: 0,
            checksum = header?.checksum ?: 0L,
            classCount = 0,
            dexIndex = entry.dexIndex,
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Analysis and emission
    // ---------------------------------------------------------------------------------------------

    private suspend fun emitAll(
        program: HirProgram,
        analyzer: MethodAnalyzer,
        emitter: AsmClassEmitter,
        plugins: List<VortexPlugin>,
        pluginContext: PluginContext,
        options: TranslationOptions,
    ): List<ClassEmission> = coroutineScope {
        // The fallback analyzer reduces the array-component view to the historical `Object`-first
        // reading: on type-agnostic `aget-wide`/`aput-wide` it drops the register-version epoch,
        // which occasionally produces bytecode ASM's own frame computation cannot merge even though
        // the DEX is well-formed (see MethodLowering). One such burst costs a per-method retry only.
        val fallbackAnalyzer = MethodAnalyzer(program, options.analysis.copy(wideEpochFallback = false))
        val permits = Semaphore(options.parallelism.coerceAtLeast(1))
        program.classes
            .map { hirClass ->
                async(Dispatchers.Default) {
                    permits.withPermit {
                        emitClass(hirClass, analyzer, fallbackAnalyzer, emitter, plugins, pluginContext, options)
                    }
                }
            }
            .awaitAll()
    }

    private fun emitClass(
        hirClass: HirClass,
        analyzer: MethodAnalyzer,
        fallbackAnalyzer: MethodAnalyzer,
        emitter: AsmClassEmitter,
        plugins: List<VortexPlugin>,
        pluginContext: PluginContext,
        options: TranslationOptions,
        retryFrameBurst: Boolean = true,
    ): ClassEmission {
        return try {
            val analysed = analyzeClass(hirClass, analyzer)
            var emitted = emitter.emit(analysed.lirClass)
            plugins.forEach { plugin -> emitted = plugin.transformBytecode(emitted, pluginContext) }

            val report = ClassReport(
                name = emitted.internalName,
                originalName = hirClass.type.internalName.takeIf { it != emitted.internalName },
                methodCount = emitted.methodCount,
                fieldCount = hirClass.fields.size,
                stubbedMethods = emitted.stubbedMethods,
                failedMethods = analysed.failures,
                verificationErrors = emitted.verificationErrors,
                sizeBytes = emitted.bytes.size,
                dexIndex = hirClass.dexIndex,
            )
            if (options.failFast && !report.isFullyTranslated) {
                val reason = report.failedMethods.firstOrNull()
                    ?.let { "method ${it.method}: ${it.message}" }
                    ?: "method ${report.stubbedMethods.first()}"
                throw TranslationException("${report.name} was not fully translated: $reason")
            }
            ClassEmission(emitted, report, null)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            // A failed JVM frame computation at emission is retried once with the reduced
            // array-component view; the retry rebuilds the class's LIR from the same HIR.
            if (retryFrameBurst && error is TranslationException && "frame computation burst" in error.message.orEmpty()) {
                return emitClass(hirClass, fallbackAnalyzer, fallbackAnalyzer, emitter, plugins, pluginContext, options, retryFrameBurst = false)
            }
            if (error is TranslationException && options.failFast) throw error
            ClassEmission(null, null, ClassFailure(hirClass.type.internalName, error.describe()))
        }
    }

    /**
     * Analyses every method of a class, keeping the ones that succeeded.
     *
     * A method whose analysis throws is not fatal to the class: the emitter emits a throwing stub for
     * it and the reason is recorded, because one unanalysable method in an obfuscated class must not
     * cost the other twenty.
     */
    private fun analyzeClass(hirClass: HirClass, analyzer: MethodAnalyzer): AnalyzedClass {
        val methods = ArrayList<LirMethod>(hirClass.methods.size)
        val failures = ArrayList<MethodFailure>()
        hirClass.methods.forEach { method ->
            if (!needsAnalysis(method)) return@forEach
            try {
                methods += analyzer.analyzeMethod(hirClass, method)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                failures += MethodFailure(method.reference.toString(), error.describe())
            }
        }
        return AnalyzedClass(LirClass(declaration = hirClass, methods = methods), failures)
    }

    private fun needsAnalysis(method: HirMethod): Boolean = method.body != null

    private fun withManifest(
        options: TranslationOptions,
        input: DexInput,
        classCount: Int,
    ): JarOptions {
        if (options.jar.manifestAttributes.isNotEmpty()) return options.jar
        return options.jar.copy(
            manifestAttributes = mapOf(
                "Vortex-Source" to input.sourceName,
                "Vortex-Dex-Entries" to input.entries.size.toString(),
                "Vortex-Classes" to classCount.toString(),
            ),
        )
    }

    /** A merged program, plus the class files dropped because an earlier DEX entry defined them. */
    private class LoadedProgram(val program: HirProgram, val duplicates: List<String>)

    private class AnalyzedClass(val lirClass: LirClass, val failures: List<MethodFailure>)

    private class ClassEmission(
        val emitted: EmittedClass?,
        val report: ClassReport?,
        val failure: ClassFailure?,
    )
}

/** The message to put in a report, never `null`. */
internal fun Throwable.describe(): String =
    (message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName) +
        stackTrace.take(5).joinToString(" | ") { "${it.methodName}(${it.fileName}:${it.lineNumber})" }
