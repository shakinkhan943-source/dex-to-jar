package riad.codes.vortex.engine

import riad.codes.vortex.analysis.AnalysisOptions
import riad.codes.vortex.dex.DexToHirOptions
import riad.codes.vortex.dex.IngestionOptions
import riad.codes.vortex.emit.EmitOptions
import riad.codes.vortex.emit.JarOptions
import java.nio.file.Path

/**
 * Everything one translation needs.
 *
 * The input is a [Path] rather than a stream on purpose: the DEX header is memory-mapped, an APK is
 * enumerated from its central directory, and each DEX entry is parsed lazily from its own coroutine,
 * all of which need a seekable file.
 */
data class TranslationRequest(
    val input: Path,
    val output: Path,
    val options: TranslationOptions = TranslationOptions.DEFAULT,
)

/** The knobs of a translation, grouped by the stage they affect. */
data class TranslationOptions(
    val ingestion: IngestionOptions = IngestionOptions.DEFAULT,
    val conversion: DexToHirOptions = DexToHirOptions(),
    val analysis: AnalysisOptions = AnalysisOptions(),
    val emit: EmitOptions = EmitOptions(),
    val jar: JarOptions = JarOptions.DEFAULT,

    /** Plugin ids to run, in order. An unknown id is an error, never a silent no-op. */
    val plugins: List<String> = emptyList(),

    /**
     * Run the JVM type checker over every emitted class.
     *
     * On by default: the checker catches frame and stack errors that the JDK's own verifier would
     * only report when the class is first loaded, and it runs on this thread rather than at load time.
     */
    val verify: Boolean = true,

    /** Classes translated at once. Defaults to the number of available processors. */
    val parallelism: Int = Runtime.getRuntime().availableProcessors(),

    /**
     * Stop at the first class that cannot be translated.
     *
     * Off by default so one bad class in a 30 000 class APK does not cost the other 29 999. When on,
     * the engine throws instead of reporting, which is what a build integration wants.
     */
    val failFast: Boolean = false,
) {
    /** Emission options with the [verify] flag applied, so the two can never disagree. */
    internal val effectiveEmit: EmitOptions
        get() = emit.copy(verifyBytecode = verify)

    companion object {
        val DEFAULT = TranslationOptions()
    }
}
