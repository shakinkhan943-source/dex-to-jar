package riad.codes.vortex.analysis

import riad.codes.vortex.ir.LiveRange
import riad.codes.vortex.ir.RegisterRef
import riad.codes.vortex.ir.TranslationException

/** The local variable layout of one method. */
data class SlotAllocation(
    val slots: Map<RegisterRef, Int>,
    val locals: Int,
    /**
     * Kept for compatibility with earlier layouts: [RegisterAllocator] no longer shares slots
     * between versions, so this is always 0.
     */
    val reusedSlots: Int = 0,
)

/**
 * Linear-scan allocation of JVM local slots to register versions.
 *
 * Slots are a flat array on the JVM, and a version occupies one slot (two for `long`/`double`).
 * Allocating a version means giving it a region of that array; parameter versions are pinned first,
 * because the method signature fixes their slots.
 *
 * **No slot is shared between two versions.** Each version's region starts past the last one placed.
 * Reuse is only sound when a version stands for exactly one write epoch — then a slot whose range
 * has ended is genuinely dead on every path reaching the next range. The current versioning does not
 * give that guarantee: its per-(`register`, storage-class) identity model lets one version be written
 * on several control-flow paths and reused across epochs — under that model, slot sharing
 * produced methods where a category-2 slot was written as an `int` on one path while another path
 * loaded it as a `long` — a state whose stack map frames cannot merge, and `COMPUTE_FRAMES` died in
 * `Frame.merge`. Grow a disjoint layout and let the JVM worry about slots; re-enable reuse only once
 * a version reliably means one write epoch.
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

        val pending = ranges.keys
            .filter { it !in pinned }
            .sortedWith(compareBy({ ranges.getValue(it).start }, { it.register }, { it.version }))

        pending.forEach { ref ->
            val range = ranges.getValue(ref)
            val width = storageOf(ref).width
            val storage = storageOf(ref)
            var candidate = placements.maxOfOrNull { it.slot + it.width } ?: reservedSlots
            // A category-2 region is 2-aligned so that a `long`/`double` never starts in the upper
            // half of the previous region's slots.
            if (width == 2 && candidate % 2 != 0) candidate++
            if (candidate >= MAX_LOCALS) {
                throw TranslationException(
                    "method needs more than $MAX_LOCALS local slots; register versions could not be packed",
                )
            }
            slots[ref] = candidate
            placements += Placement(candidate, width, range, storage, ref)
        }

        val locals = placements.maxOfOrNull { it.slot + it.width } ?: reservedSlots
        return SlotAllocation(slots = slots, locals = maxOf(locals, reservedSlots), reusedSlots = 0)
    }

    private companion object {
        const val MAX_LOCALS = 65_535
        val WHOLE_METHOD = LiveRange(0, Int.MAX_VALUE - 1)
    }
}
