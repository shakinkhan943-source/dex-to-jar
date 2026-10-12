package riad.codes.vortex.engine

import riad.codes.vortex.dex.DexContainerKind
import java.nio.file.Path

/** One translated class, as the report sees it. */
data class ClassReport(
    /** The name the class has in the output jar, after any plugin renames. */
    val name: String,
    /** The DEX class name it came from, `null` when nothing renamed it. */
    val originalName: String?,
    val methodCount: Int,
    val fieldCount: Int,
    /** Methods replaced by a throwing stub because their body could not be translated. */
    val stubbedMethods: List<String>,
    /** Methods whose analysis failed outright; the stub names the reason. */
    val failedMethods: List<MethodFailure>,
    val verificationErrors: List<String>,
    val sizeBytes: Int,
    val dexIndex: Int,
) {
    val isVerified: Boolean get() = verificationErrors.isEmpty()

    val isFullyTranslated: Boolean get() = stubbedMethods.isEmpty() && failedMethods.isEmpty()
}

/** Why one method could not be translated. */
data class MethodFailure(val method: String, val message: String)

/** A class that could not be emitted at all. */
data class ClassFailure(val name: String, val message: String)

/**
 * The outcome of a translation run.
 *
 * The report is the product as much as the jar is: a translation that silently drops a method or
 * leaves a stub behind has to say so, and the numbers here are what a caller checks before trusting
 * the output. [isFullyTranslated] is the single question most callers have.
 */
data class TranslationReport(
    val input: Path,
    val output: Path,
    val containerKind: DexContainerKind,
    /** DEX entry names that were read, in the order they were merged. */
    val dexEntries: List<String>,
    val classes: List<ClassReport>,
    val failedClasses: List<ClassFailure>,
    /** Jar entries dropped because a class with that name had already been written. */
    val duplicates: List<String>,
    val pluginNotes: List<String>,
    val warnings: List<String>,
    val jarSizeBytes: Long,
    val durationMillis: Long,
    val parallelism: Int,
) {
    val emittedClassCount: Int get() = classes.size

    val classCount: Int get() = classes.size + failedClasses.size

    val methodCount: Int get() = classes.sumOf { it.methodCount }

    val stubbedMethods: List<String> get() = classes.flatMap { it.stubbedMethods }

    val verificationErrors: List<String> get() = classes.flatMap { report ->
        report.verificationErrors.map { "${report.name}: $it" }
    }

    val isVerified: Boolean get() = verificationErrors.isEmpty()

    /** True when every class was emitted, verified, and no method was replaced by a stub. */
    val isFullyTranslated: Boolean
        get() = failedClasses.isEmpty() && stubbedMethods.isEmpty() && classes.all { it.failedMethods.isEmpty() }

    /** One-line summary, suitable for a log line. */
    fun summaryLine(): String = buildString {
        append("$emittedClassCount/${classCount} classes -> $output ")
        append("($methodCount methods, ${stubbedMethods.size} stubbed, ${jarSizeBytes / 1024} KiB, ")
        append("${durationMillis}ms, $parallelism threads)")
    }

    /** Multi-line report for a terminal. */
    fun render(verbose: Boolean = false): String = buildString {
        appendLine("vortex-dex translation")
        appendLine("  input      : $input (${containerKind.displayName})")
        appendLine("  output     : $output")
        appendLine("  dex entries: ${dexEntries.size}${if (dexEntries.size > 1) " (${dexEntries.joinToString()})" else ""}")
        appendLine("  classes    : $emittedClassCount emitted, ${failedClasses.size} failed, $classCount total")
        appendLine("  methods    : $methodCount, ${stubbedMethods.size} replaced by stubs")
        appendLine("  verified   : ${if (isVerified) "yes" else "${verificationErrors.size} error(s)"}")
        appendLine("  plugins    : ${if (pluginNotes.isEmpty()) "none active" else pluginNotes.size.toString() + " note(s)"}")
        appendLine("  duration   : ${durationMillis}ms on $parallelism thread(s)")

        if (pluginNotes.isNotEmpty()) {
            appendLine()
            appendLine("plugin notes:")
            pluginNotes.forEach { appendLine("  $it") }
        }
        if (stubbedMethods.isNotEmpty()) {
            appendLine()
            appendLine("stubbed methods (present in the jar, they throw when called):")
            stubbedMethods.take(if (verbose) Int.MAX_VALUE else 20).forEach { appendLine("  $it") }
            if (!verbose && stubbedMethods.size > 20) appendLine("  ... ${stubbedMethods.size - 20} more")
        }
        val failures = classes.flatMap { report -> report.failedMethods.map { "${report.name}#${it.method}: ${it.message}" } }
        if (failures.isNotEmpty() || failedClasses.isNotEmpty()) {
            appendLine()
            appendLine("failures:")
            failedClasses.forEach { appendLine("  ${it.name}: ${it.message}") }
            failures.take(if (verbose) Int.MAX_VALUE else 20).forEach { appendLine("  $it") }
            if (!verbose && failures.size > 20) appendLine("  ... ${failures.size - 20} more")
        }
        if (verificationErrors.isNotEmpty()) {
            appendLine()
            appendLine("verification errors:")
            verificationErrors.take(if (verbose) Int.MAX_VALUE else 20).forEach { appendLine("  $it") }
            if (!verbose && verificationErrors.size > 20) appendLine("  ... ${verificationErrors.size - 20} more")
        }
        if (duplicates.isNotEmpty()) {
            appendLine()
            appendLine("duplicate classes (first occurrence kept): ${duplicates.size}")
            duplicates.take(if (verbose) Int.MAX_VALUE else 10).forEach { appendLine("  $it") }
        }
        if (warnings.isNotEmpty()) {
            appendLine()
            appendLine("warnings:")
            warnings.forEach { appendLine("  $it") }
        }
        if (verbose) {
            appendLine()
            appendLine("classes:")
            classes.sortedBy { it.name }.forEach { report ->
                val renamed = report.originalName?.let { " (was $it)" } ?: ""
                appendLine("  ${report.name}$renamed ${report.sizeBytes} bytes, ${report.methodCount} methods")
            }
        }
    }.trimEnd()
}
