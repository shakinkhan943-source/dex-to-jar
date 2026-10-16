package riad.codes.vortex.analysis

import riad.codes.vortex.ir.HirGoto
import riad.codes.vortex.ir.HirIfTest
import riad.codes.vortex.ir.HirInstruction
import riad.codes.vortex.ir.HirMethodBody
import riad.codes.vortex.ir.HirReturn
import riad.codes.vortex.ir.HirSwitch
import riad.codes.vortex.ir.HirThrow
import riad.codes.vortex.ir.HirTryBlock
import riad.codes.vortex.ir.HirUnsupported
import riad.codes.vortex.ir.TranslationException

/** How a basic block can be entered. */
enum class BlockRole { NORMAL, ENTRY, EXCEPTION_HANDLER }

/** A basic block of the high-level IR. */
class HirBlock(val id: Int, val startAddress: Int) {

    val instructions: MutableList<HirInstruction> = ArrayList()

    val successors: MutableSet<HirBlock> = LinkedHashSet()

    val predecessors: MutableSet<HirBlock> = LinkedHashSet()

    var role: BlockRole = BlockRole.NORMAL

    /** First code unit after this block. */
    var endAddressExclusive: Int = startAddress

    /** Try regions whose protected range covers this block. */
    val protectedBy: MutableList<HirTryBlock> = ArrayList()

    val isExceptionHandler: Boolean get() = role == BlockRole.EXCEPTION_HANDLER

    val terminator: HirInstruction? get() = instructions.lastOrNull()

    fun containsAddress(address: Int): Boolean = address in startAddress until endAddressExclusive

    /** True when this block ends by falling into [other] rather than by branching. */
    fun fallsThroughTo(other: HirBlock): Boolean = successors.contains(other) && when (terminator) {
        is HirGoto, is HirReturn, is HirThrow, is HirSwitch, is HirUnsupported -> false
        is HirIfTest -> false
        else -> true
    }

    override fun toString(): String = "hB$id@$startAddress"
}

/**
 * The control flow graph of one method, built directly from DEX code.
 *
 * The graph is the shared substrate for the rest of the pipeline: type inference propagates register
 * types over it, liveness runs a backward dataflow over it, and the emitter walks it to place labels.
 * Exception handlers are nodes in the graph too, reached by exception edges, because a handler sees
 * whatever the protected blocks left in registers.
 */
class HirControlFlowGraph(
    val blocks: List<HirBlock>,
    val entry: HirBlock,
    val body: HirMethodBody,
    /**
     * Catch-handler entry blocks synthesised between a protected block and a `goto`-only jump into
     * the real handler, one per shared handler pc. Empty when the method needs no split.
     */
    val trampolineBlocks: Set<HirBlock> = emptySet(),
) {
    private val byAddress: Map<Int, HirBlock> = blocks.associateBy { it.startAddress }

    /** The try regions of this method with handler entries pointing at catch trampolines. */
    val tryBlocks: List<HirTryBlock> = run {
        // The trampoline's only successor is the real handler block, so the lookup is exact.
        val trampolineOf: Map<Int, HirBlock> =
            trampolineBlocks.associateByTo(HashMap()) { it.successors.first().startAddress }
        body.tryBlocks.map { tryBlock ->
            val rewritten = tryBlock.handlers.map { handler ->
                trampolineOf[handler.handlerAddress]?.let { handler.copy(handlerAddress = it.startAddress) } ?: handler
            }
            if (rewritten == tryBlock.handlers) tryBlock else tryBlock.copy(handlers = rewritten)
        }
    }

    fun blockAt(address: Int): HirBlock? = byAddress[address]

    val handlerBlocks: List<HirBlock> get() = blocks.filter { it.isExceptionHandler }

    val exitBlocks: List<HirBlock> get() = blocks.filter { it.successors.isEmpty() }

    /**
     * Handler blocks that some other block can also fall into.
     *
     * A JVM exception handler label must be entered *only* through the exception table: the verifier
     * sees the pending throwable on the operand stack. A DEX handler that is also a fall-through
     * target would produce a frame merge the verifier rejects, so the analyser reports it instead of
     * emitting bytecode that cannot load.
     */
    fun handlersWithFallThroughPredecessors(): List<HirBlock> = handlerBlocks.filter { handler ->
        handler.predecessors.any { it.fallsThroughTo(handler) }
    }
}

/** Splits a decoded method body into basic blocks and wires up its edges. */
object CfgBuilder {

    fun build(body: HirMethodBody): HirControlFlowGraph {
        val instructions = body.instructions
        if (instructions.isEmpty()) throw TranslationException("method body has no instructions to build a CFG from")

        val addressSet = HashSet<Int>(instructions.size * 2)
        instructions.forEach { addressSet += it.address }

        // Leaders: the entry point, every branch target, whatever follows a control transfer, and
        // every address the exception table mentions (a try range can start or end mid-block).
        val leaders = sortedSetOf<Int>()
        leaders += instructions.first().address
        instructions.forEachIndexed { index, instruction ->
            val nextAddress = instructions.getOrNull(index + 1)?.address
            when (instruction) {
                is HirGoto -> leaders += instruction.target

                is HirIfTest -> {
                    leaders += instruction.target
                    nextAddress?.let { leaders += it }
                }

                is HirSwitch -> {
                    instruction.targets.forEach { leaders += it }
                    leaders += instruction.defaultTarget
                    nextAddress?.let { leaders += it }
                }

                is HirReturn, is HirThrow, is HirUnsupported -> nextAddress?.let { leaders += it }

                else -> Unit
            }
        }
        body.tryBlocks.forEach { tryBlock ->
            leaders += tryBlock.startAddress
            // The end of a protected range may be the end of the code rather than an instruction.
            if (tryBlock.endAddress in addressSet) leaders += tryBlock.endAddress
            tryBlock.handlers.forEach { leaders += it.handlerAddress }
        }

        val dangling = leaders.filter { it !in addressSet }
        if (dangling.isNotEmpty()) {
            throw TranslationException(
                "branch target(s) ${dangling.joinToString()} do not name a DEX instruction; the method cannot be split into blocks",
            )
        }

        val blocks = ArrayList<HirBlock>(leaders.size)
        var current: HirBlock? = null
        instructions.forEach { instruction ->
            val openBlock = current
            if (openBlock == null || instruction.address in leaders) {
                val block = HirBlock(blocks.size, instruction.address)
                blocks += block
                current = block
                block.instructions += instruction
            } else {
                openBlock.instructions += instruction
            }
        }

        blocks.forEachIndexed { index, block ->
            block.endAddressExclusive = blocks.getOrNull(index + 1)?.startAddress
                ?: (block.instructions.last().address + 1).coerceAtLeast(block.startAddress + 1)
        }

        val byAddress = blocks.associateBy { it.startAddress }
        val edges = linkEdges(blocks, byAddress, body.tryBlocks)
        val (extended, trampolines) = fixSharedHandlers(blocks, edges)
        blocks.first().role = BlockRole.ENTRY
        return HirControlFlowGraph(
            blocks = extended,
            entry = blocks.first(),
            body = body,
            trampolineBlocks = trampolines,
        )
    }

    /**
     * The normal- and exceptional-edge record a graph build produces.
     *
     * A *normal* edge is a fall-through, an explicit branch or a switch case; an *exceptional* edge is
     * the protected range to handler connection. A handler pc with a normal edge can also be entered
     * by ordinary control flow, which is exactly the layout the JVM forbids for a catch body and is
     * what [fixSharedHandlers] remedies.
     */
    private class EdgeRecord {
        val normalTargets: LinkedHashSet<HirBlock> = LinkedHashSet()
        val exceptional: LinkedHashSet<Pair<HirBlock, HirBlock>> = LinkedHashSet()
    }

    private fun linkEdges(blocks: List<HirBlock>, byAddress: Map<Int, HirBlock>, tryBlocks: List<HirTryBlock>): EdgeRecord {
        val edges = EdgeRecord()

        fun normalLink(from: HirBlock, to: HirBlock) {
            from.successors += to
            to.predecessors += from
            edges.normalTargets += to
        }

        fun exceptionalLink(from: HirBlock, to: HirBlock) {
            from.successors += to
            to.predecessors += from
            edges.exceptional += from to to
        }

        blocks.forEachIndexed { index, block ->
            val fallThrough = blocks.getOrNull(index + 1)
            when (val terminator = block.terminator) {
                is HirGoto -> normalLink(block, target(byAddress, terminator.target))

                is HirIfTest -> {
                    normalLink(block, target(byAddress, terminator.target))
                    fallThrough?.let { normalLink(block, it) }
                }

                is HirSwitch -> {
                    terminator.targets.forEach { normalLink(block, target(byAddress, it)) }
                    normalLink(block, target(byAddress, terminator.defaultTarget))
                    fallThrough?.let { normalLink(block, it) }
                }

                is HirReturn, is HirThrow, is HirUnsupported -> Unit

                else -> fallThrough?.let { normalLink(block, it) }
            }
        }

        // Exception edges: every block inside a protected range can reach that range's handlers, and a
        // handler's registers are typed from whatever the protected blocks left behind.
        tryBlocks.forEach { tryBlock ->
            val handlers = tryBlock.handlers.mapNotNull { byAddress[it.handlerAddress] }
            handlers.forEach { it.role = BlockRole.EXCEPTION_HANDLER }
            blocks.forEach { block ->
                if (block.startAddress >= tryBlock.startAddress && block.startAddress < tryBlock.endAddress) {
                    if (block.protectedBy.none { it === tryBlock }) block.protectedBy += tryBlock
                    handlers.forEach { exceptionalLink(block, it) }
                }
            }
        }
        return edges
    }

    /**
     * Splits every handler pc that normal control flow can also reach.
     *
     * The JVM exception table must be the *only* way into a catch body: an exception entry arrives
     * with the pending throwable on the operand stack, so a handler label shared with a branch or
     * fall-through target cannot frame-merge and ASM's frame computation rejects the code. DEX has no
     * such rule — a Kotlin `?:`-and-`catch` fallback keeps one pc for both entries — so the graph gets
     * a `goto`-only catch trampoline: the try tables are rewritten onto the trampoline, normal edges
     * keep entering the real block, and the same register states still merge conservatively.
     */
    private fun fixSharedHandlers(blocks: List<HirBlock>, edges: EdgeRecord): Pair<List<HirBlock>, Set<HirBlock>> {
        val shared = blocks
            .filter { it.role == BlockRole.EXCEPTION_HANDLER && it in edges.normalTargets }
            .sortedBy { it.startAddress }
        if (shared.isEmpty()) return blocks to emptySet()

        val trampolineByHandler = HashMap<HirBlock, HirBlock>(shared.size)
        val trampolines = LinkedHashSet<HirBlock>(shared.size)
        val extended = ArrayList<HirBlock>(blocks.size + shared.size)
        extended += blocks

        // Trampolines are appended past every real code unit so the emitter places them after the
        // method body, the same way the reference implementation appends its synthetic labels.
        val codeEnd = blocks.maxOf { it.endAddressExclusive }
        shared.forEachIndexed { index, handler ->
            val startAddress = codeEnd + index
            val trampoline = HirBlock(blocks.size * 1_000 + index + 1, startAddress).apply {
                role = BlockRole.EXCEPTION_HANDLER
                endAddressExclusive = startAddress + 1
                instructions += HirGoto(address = startAddress, target = handler.startAddress)
            }
            trampoline.successors += handler
            handler.predecessors += trampoline
            // The real pc keeps normal flow; its catch entries all point at the trampoline.
            handler.role = BlockRole.NORMAL
            extended += trampoline
            trampolines += trampoline
            trampolineByHandler[handler] = trampoline
        }

        edges.exceptional.forEach { (from, to) ->
            val trampoline = trampolineByHandler[to] ?: return@forEach
            from.successors.remove(to)
            to.predecessors.remove(from)
            from.successors += trampoline
            trampoline.predecessors += from
        }

        return extended to trampolines
    }

    private fun target(byAddress: Map<Int, HirBlock>, address: Int): HirBlock = byAddress[address]
        ?: throw TranslationException("branch to $address does not name a basic block")
}

