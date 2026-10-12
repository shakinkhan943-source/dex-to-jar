package riad.codes.vortex.analysis

import riad.codes.vortex.ir.HirClass
import riad.codes.vortex.ir.HirMethod
import riad.codes.vortex.ir.HirProgram
import riad.codes.vortex.ir.LirClass
import riad.codes.vortex.ir.LirExceptionHandler
import riad.codes.vortex.ir.LirMethod
import riad.codes.vortex.ir.LirTryBlock
import riad.codes.vortex.ir.LiveRange
import riad.codes.vortex.ir.LocalVariableInfo
import riad.codes.vortex.ir.MethodAnalysisSummary
import riad.codes.vortex.ir.RegisterRef
import riad.codes.vortex.ir.TranslationException
import riad.codes.vortex.ir.VortexTypes

/** Knobs for the analysis pipeline. */
data class AnalysisOptions(
    /** Generate `LocalVariableTable` entries, so decompilers can recover variable names. */
    val emitLocalVariableTable: Boolean = true,

    /** Treat an unbalanced operand stack as a hard failure for the method. */
    val failOnUnbalancedStack: Boolean = false,
)

/**
 * The analysis facade: turns a decoded class into an emit-ready [LirClass].
 *
 * The pipeline per method is
 *
 * 1. [CfgBuilder] — DEX code into basic blocks,
 * 2. [MethodTypeInference] — abstract interpretation of the register file,
 * 3. [RegisterVersioning] — one version per (register, storage class), with the ambiguous zero
 *    constant resolved from its first use,
 * 4. [LivenessAnalysis] — live ranges over versions,
 * 5. [RegisterAllocator] — JVM local slots, with parameter slots pinned,
 * 6. [MethodLowering] — high-level IR into register/stack-neutral low-level IR,
 * 7. [StackStateAnalyzer] — proof that the operand stack is empty at every instruction boundary.
 *
 * A method that uses an instruction the engine cannot translate, or whose analysis cannot be
 * completed, is not silently emitted: [MethodAnalysisSummary.isFullyTranslated] is false and the
 * emitter replaces the body with a reported fallback stub.
 */
class MethodAnalyzer(
    private val program: HirProgram,
    private val options: AnalysisOptions = AnalysisOptions(),
) {

    fun analyzeClass(hirClass: HirClass): LirClass = LirClass(
        declaration = hirClass,
        methods = hirClass.methods.mapNotNull { method -> analyzeMethodOrNull(hirClass, method) },
    )

    /**
     * Analyses one method, or returns `null` when it has no body (abstract, native, or a
     * `dexlib2` implementation-less method) — those are emitted from their declaration alone.
     */
    fun analyzeMethodOrNull(hirClass: HirClass, method: HirMethod): LirMethod? {
        if (method.body == null) return null
        return analyzeMethod(hirClass, method)
    }

    fun analyzeMethod(hirClass: HirClass, method: HirMethod): LirMethod {
        val body = method.body ?: throw TranslationException("${method.reference} has no code item to analyse")
        val cfg = CfgBuilder.build(body)
        val inference = MethodTypeInference(program, method, cfg).run()
        val versions = RegisterVersioning(inference, cfg).run()
        val liveness = LivenessAnalysis(cfg, versions)

        val ranges = HashMap(liveness.liveRanges())
        // Parameters are live from the first instruction: their slots are the declared ones.
        inference.parameterRegisters().forEach { (register, _) ->
            versions.entryVersion(register)?.let { entryVersion ->
                val existing = ranges[entryVersion]
                ranges[entryVersion] = if (existing == null) LiveRange(0, 0) else LiveRange(0, existing.end)
            }
        }

        val pinned = HashMap<RegisterRef, Int>()
        var slot = 0
        inference.parameterRegisters().forEach { (register, type) ->
            versions.entryVersion(register)?.let { entryVersion ->
                pinned[entryVersion] = slot
                slot += if (type.isCategory2) 2 else 1
            }
        }

        val allocation = RegisterAllocator(pinned = pinned, reservedSlots = slot).allocate(ranges) { ref ->
            versions.storage(ref)
        }

        val lowering = MethodLowering(
            program = program,
            method = method,
            cfg = cfg,
            inference = inference,
            versions = versions,
        )
        val blocks = lowering.lower()

        val stackReport = StackStateAnalyzer(blocks, method.reference).analyze()
        val warnings = ArrayList<String>(versions.warnings)
        val fallThroughHandlers = cfg.handlersWithFallThroughPredecessors()
        if (fallThroughHandlers.isNotEmpty()) {
            warnings +=
                "exception handler(s) ${fallThroughHandlers.joinToString()} are also fall-through targets; " +
                    "the generated try/catch layout may not verify"
        }
        if (!stackReport.isBalanced) {
            warnings += stackReport.describe()
            if (options.failOnUnbalancedStack) {
                throw TranslationException("${method.reference} has an unbalanced operand stack: ${stackReport.violations.first()}")
            }
        }

        val unsupported = blocks.sumOf { block -> block.instructions.count { it is riad.codes.vortex.ir.LirInstruction.Unsupported } }
        val localVariables = if (options.emitLocalVariableTable) {
            buildLocalVariables(versions, allocation.slots, ranges, body.registerNames, liveness.blockByLinearIndex)
        } else {
            emptyList()
        }

        val lineNumbers = body.lineNumbers.filterKeys { address -> cfg.blockAt(address) != null }

        return LirMethod(
            reference = method.reference,
            accessFlags = method.accessFlags,
            blocks = blocks,
            entry = blocks.first { it.kind == riad.codes.vortex.ir.BasicBlock.Kind.ENTRY },
            locals = allocation.locals,
            slotAssignment = allocation.slots,
            liveRanges = ranges,
            localVariables = localVariables,
            lineNumbers = lineNumbers,
            tryBlocks = cfg.tryBlocks.map { tryBlock ->
                LirTryBlock(
                    start = blocks.first { it.startAddress == tryBlock.startAddress },
                    end = blocks.firstOrNull { it.startAddress == tryBlock.endAddress },
                    handlers = tryBlock.handlers.mapNotNull { handler ->
                        blocks.firstOrNull { it.startAddress == handler.handlerAddress }?.let { handlerBlock ->
                            LirExceptionHandler(handler.exceptionType, handlerBlock)
                        }
                    },
                )
            },
            blockByLinearIndex = liveness.blockByLinearIndex.toList(),
            summary = MethodAnalysisSummary(
                registerVersions = versions.orderedRefs.size,
                basicBlocks = blocks.size,
                estimatedMaxStack = stackReport.maxDepth,
                unsupportedInstructions = unsupported,
                warnings = warnings,
            ),
        )
    }

    /**
     * The `LocalVariableTable` rows for a method.
     *
     * A DEX register that changes type becomes several versions, and a decompiler wants to see each of
     * them — but two rows that start at the same code offset may not name the same local slot. A class
     * file says that at any offset a local variable index holds exactly one variable, and HotSpot
     * enforces it by rejecting the whole class with `Duplicated LocalVariableTable attribute entry`
     * rather than the offending row. The emitter anchors a range to the label of the block containing
     * it, so two versions of one register that start inside the same block and happen to share a slot
     * collide here. The later row is dropped: that costs a little debug information and nothing else.
     */
    private fun buildLocalVariables(
        versions: MethodVersions,
        slots: Map<RegisterRef, Int>,
        ranges: Map<RegisterRef, LiveRange>,
        registerNames: Map<Int, String>,
        blockByLinearIndex: IntArray,
    ): List<LocalVariableInfo> {
        val claimed = HashSet<Pair<Int, Int>>()
        return versions.orderedRefs.mapNotNull { ref ->
            val slot = slots[ref] ?: return@mapNotNull null
            val range = ranges[ref] ?: return@mapNotNull null
            val startBlock = blockByLinearIndex.getOrElse(range.start) { blockByLinearIndex.lastOrNull() ?: 0 }
            // One row per (start offset, slot): the JVM allows only one variable per slot at an offset.
            if (!claimed.add(startBlock to slot)) return@mapNotNull null
            LocalVariableInfo(
                // DEX debug info names the register; otherwise use the conventional `v<n>` spelling.
                name = registerNames[ref.register] ?: "v${ref.register}",
                descriptor = if (ref.type.isVoid) VortexTypes.OBJECT.descriptor else ref.type.descriptor,
                slot = slot,
                range = range,
            )
        }
    }
}
