package riad.codes.vortex.analysis

import riad.codes.vortex.ir.LiveRange
import riad.codes.vortex.ir.RegisterRef
import riad.codes.vortex.ir.TranslationException

/** The local variable layout of one method. */
data class SlotAllocation(
    val slots: Map<RegisterRef, Int>,
    val locals: Int,
    /** How many versions were placed into a slot another version had already vacated. */
    val reusedSlots: Int,
)

/**
 * Linear-scan allocation of JVM local slots to register versions.
 *
 * Slots are a flat array on the JVM, and a version occupies one slot (two for `long`/`double`). The
 * allocator walks versions in live-range order and gives each one the lowest slot whose existing
 * occupants are either a different point in time or a non-overlapping slice of the array.
 *
 * Two properties make the reuse sound:
 *
 * * A version is written exactly once, so if version A's range ends before version B's begins, every
 *   path reaching B's range writes B after the last write of A. The frame at B's range therefore
 *   carries B's type on all paths — the JVM's *frames* cover all locals, live or not, so "my value is
 *   dead here" is not enough on its own.
 * * Versions already in flight keep their slots, so a value that is live across a branch never moves.
 *
 * Parameter slots are pinned: the method signature fixes them, and nothing else may be allocated
 * into them.
 */
class RegisterAllocator(
    private val pinned: Map<RegisterRef, Int>,
    private val reservedSlots: Int,
) {
    private data class Placement(
        val slot: Int,
        val width: Int,
        val range: LiveRange,
        val storage: StorageClass,
        val ref: RegisterRef,
    )

    fun allocate(
        ranges: Map<RegisterRef, LiveRange>,
        storageOf: (RegisterRef) -> StorageClass,
    ): SlotAllocation {
        val slots = HashMap<RegisterRef, Int>(pinned)
        val placements = ArrayList<Placement>(ranges.size + pinned.size)

        pinned.forEach { (ref, slot) ->
            if (slot >= reservedSlots) {
                throw TranslationException("pinned slot $slot for $ref overlaps the reserved parameter area")
            }
            placements += Placement(slot, storageOf(ref).width, ranges[ref] ?: WHOLE_METHOD, storageOf(ref), ref)
        }

        var reused = 0
        val pending = ranges.keys
            .filter { it !in pinned }
            .sortedWith(compareBy({ ranges.getValue(it).start }, { it.register }, { it.version }))

        pending.forEach { ref ->
            val range = ranges.getValue(ref)
            val width = storageOf(ref).width
            val storage = storageOf(ref)
            var candidate = reservedSlots
            while (true) {
                val conflict = placements.firstOrNull { placement ->
                    placement.range.overlaps(range) && overlaps(placement.slot, placement.width, candidate, width)
                } ?: break
                candidate = conflict.slot + conflict.width
            }
            if (candidate >= MAX_LOCALS) {
                throw TranslationException(
                    "method needs more than $MAX_LOCALS local slots; register versions could not be packed",
                )
            }
            if (placements.any { overlaps(it.slot, it.width, candidate, width) }) reused++
            slots[ref] = candidate
            placements += Placement(candidate, width, range, storage, ref)
        }

        val locals = placements.maxOfOrNull { it.slot + it.width } ?: reservedSlots
        return SlotAllocation(slots = slots, locals = maxOf(locals, reservedSlots), reusedSlots = reused)
    }

    private fun overlaps(firstSlot: Int, firstWidth: Int, secondSlot: Int, secondWidth: Int): Boolean =
        firstSlot < secondSlot + secondWidth && secondSlot < firstSlot + firstWidth

    private companion object {
        const val MAX_LOCALS = 65_535
        val WHOLE_METHOD = LiveRange(0, Int.MAX_VALUE - 1)
    }
}
