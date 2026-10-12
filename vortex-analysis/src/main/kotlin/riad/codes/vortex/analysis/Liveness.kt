package riad.codes.vortex.analysis

import riad.codes.vortex.ir.HirInstruction
import riad.codes.vortex.ir.LiveRange
import riad.codes.vortex.ir.RegisterRef

/** One instruction in the method's linear emission order. */
data class LinearInstruction(
    val linearIndex: Int,
    val block: HirBlock,
    val indexInBlock: Int,
    val instruction: HirInstruction,
)

/**
 * Live range analysis over register versions.
 *
 * Two things depend on this pass. The register allocator uses the ranges to prove that two versions
 * can share a JVM local slot, and the emitter uses them to generate a `LocalVariableTable` with the
 * scope of every variable — which is what lets a decompiler print real names and overlapping locals
 * instead of one flat blob of slots.
 *
 * Because versions are type-stable by construction, a version is defined exactly once, so its live
 * range is simply the span from its definition to its last use.
 */
class LivenessAnalysis(
    private val cfg: HirControlFlowGraph,
    private val versions: MethodVersions,
) {

    /** Linear order of every instruction: the coordinate space ranges are expressed in. */
    val linearInstructions: List<LinearInstruction>

    private val blockOffset: Map<Int, Int>

    /** Block id at each linear index, so a range can be mapped back to a label. */
    val blockByLinearIndex: IntArray

    private val liveIn: Map<HirBlock, Set<RegisterRef>>

    init {
        val linear = ArrayList<LinearInstruction>()
        val offsets = HashMap<Int, Int>()
        val owners = ArrayList<Int>()
        cfg.blocks.sortedBy { it.startAddress }.forEach { block ->
            offsets[block.id] = linear.size
            block.instructions.forEachIndexed { index, instruction ->
                linear += LinearInstruction(linear.size, block, index, instruction)
                owners += block.id
            }
        }
        linearInstructions = linear
        blockOffset = offsets
        blockByLinearIndex = owners.toIntArray()
        liveIn = solve()
    }

    fun linearIndex(block: HirBlock, indexInBlock: Int): Int =
        (blockOffset[block.id] ?: 0) + indexInBlock

    /** Live registers at the entry of [block]. */
    fun liveOnEntry(block: HirBlock): Set<RegisterRef> = liveIn[block] ?: emptySet()

    /** The machine-level live range of every version that is used by the method. */
    fun liveRanges(): Map<RegisterRef, LiveRange> {
        val starts = HashMap<RegisterRef, Int>()
        val ends = HashMap<RegisterRef, Int>()

        fun record(ref: RegisterRef, index: Int) {
            starts.merge(ref, index, ::minOf)
            ends.merge(ref, index, ::maxOf)
        }

        cfg.blocks.sortedBy { it.startAddress }.forEach { block ->
            var live: MutableSet<RegisterRef> = HashSet(unionOfSuccessorEntries(block))
            for (index in block.instructions.indices.reversed()) {
                val instruction = block.instructions[index]
                val linear = linearIndex(block, index)
                val defined = versions.definitionsAt(instruction.address).values
                val read = versions.readsAt(instruction.address).values

                // `live` currently describes the point immediately *after* this instruction.
                live.forEach { record(it, linear) }
                defined.forEach { record(it, linear) }
                live = (live - defined.toSet() + read).toMutableSet()
                // ... and now it describes the point immediately before it.
                live.forEach { record(it, linear) }
            }
        }

        return starts.mapValues { (ref, start) -> LiveRange(start, ends[ref] ?: start) }
    }

    /** Uses of each version, keyed by linear index; the emitter reads this for the variable table. */
    fun firstAndLastUse(ref: RegisterRef): Pair<Int, Int>? {
        val range = liveRanges()[ref] ?: return null
        return range.start to range.end
    }

    private fun unionOfSuccessorEntries(block: HirBlock): Set<RegisterRef> {
        if (block.successors.isEmpty()) return emptySet()
        val union = HashSet<RegisterRef>()
        block.successors.forEach { union += liveIn[it] ?: emptySet() }
        return union
    }

    private fun solve(): Map<HirBlock, Set<RegisterRef>> {
        val defs = HashMap<Int, Set<RegisterRef>>()
        val uses = HashMap<Int, Set<RegisterRef>>()
        cfg.blocks.forEach { block ->
            val defined = LinkedHashSet<RegisterRef>()
            val used = LinkedHashSet<RegisterRef>()
            block.instructions.forEach { instruction ->
                versions.readsAt(instruction.address).values.forEach { used += it }
                versions.definitionsAt(instruction.address).values.forEach { defined += it }
            }
            defs[block.id] = defined
            uses[block.id] = used
        }

        val live = HashMap<HirBlock, Set<RegisterRef>>()
        var changed = true
        var rounds = 0
        while (changed) {
            changed = false
            if (rounds++ > MAX_ROUNDS) break
            cfg.blocks.sortedByDescending { it.startAddress }.forEach { block ->
                val out = HashSet<RegisterRef>()
                block.successors.forEach { out += live[it] ?: emptySet() }
                val killed = defs[block.id] ?: emptySet()
                val next = HashSet<RegisterRef>(uses[block.id] ?: emptySet())
                out.forEach { ref -> if (ref !in killed) next += ref }
                if (live[block] != next) {
                    live[block] = next
                    changed = true
                }
            }
        }
        return live
    }

    private companion object {
        const val MAX_ROUNDS = 10_000
    }
}
