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
) {
    private val byAddress: Map<Int, HirBlock> = blocks.associateBy { it.startAddress }

    /** The try regions of this method, in DEX order. */
    val tryBlocks: List<HirTryBlock> = body.tryBlocks

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
        linkEdges(blocks, byAddress, body.tryBlocks)

        blocks.first().role = BlockRole.ENTRY
        return HirControlFlowGraph(blocks = blocks, entry = blocks.first(), body = body)
    }

    private fun linkEdges(blocks: List<HirBlock>, byAddress: Map<Int, HirBlock>, tryBlocks: List<HirTryBlock>) {
        fun target(address: Int): HirBlock = byAddress[address]
            ?: throw TranslationException("branch to $address does not name a basic block")

        fun link(from: HirBlock, to: HirBlock) {
            from.successors += to
            to.predecessors += from
        }

        blocks.forEachIndexed { index, block ->
            val fallThrough = blocks.getOrNull(index + 1)
            when (val terminator = block.terminator) {
                is HirGoto -> link(block, target(terminator.target))

                is HirIfTest -> {
                    link(block, target(terminator.target))
                    fallThrough?.let { link(block, it) }
                }

                is HirSwitch -> {
                    terminator.targets.forEach { link(block, target(it)) }
                    link(block, target(terminator.defaultTarget))
                    fallThrough?.let { link(block, it) }
                }

                is HirReturn, is HirThrow, is HirUnsupported -> Unit

                else -> fallThrough?.let { link(block, it) }
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
                    handlers.forEach { link(block, it) }
                }
            }
        }
    }
}
