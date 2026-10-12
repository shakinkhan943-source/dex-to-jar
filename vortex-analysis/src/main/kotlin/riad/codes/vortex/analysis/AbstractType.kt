package riad.codes.vortex.analysis

import riad.codes.vortex.ir.ArrayType
import riad.codes.vortex.ir.ClassHierarchy
import riad.codes.vortex.ir.ObjectType
import riad.codes.vortex.ir.PrimitiveKind
import riad.codes.vortex.ir.PrimitiveType
import riad.codes.vortex.ir.VortexType
import riad.codes.vortex.ir.VortexTypes

/**
 * The abstract type lattice for Dalvik registers.
 *
 * Dalvik registers are untyped (a register holds "a word"), so the only way to know whether
 * `if-eqz v3` is an `int` test or a `null` check, or whether `aget` should become `iaload` or
 * `aaload`, is to run an abstract interpreter over the control flow graph and see what was actually
 * stored there. The lattice is:
 *
 * ```
 *            Unknown            (top: conflicting or unmodelled information)
 *           /   |    \
 *    Primitive  Reference  Array
 *           \   |    /
 *             Null             (the zero constant: compatible with int *and* with references)
 *           /   |
 *  Uninitialized(x)
 *             |
 *         Unreachable          (bottom: this point was never reached)
 * ```
 *
 * [Null] exists because `const/4 v0, #0` really is ambiguous in Dalvik: the same encoding means
 * `int 0` and `null`, and the verifier resolves it from the use. Modelling it as its own lattice
 * element (rather than guessing at decode time) is what lets the register allocation split a
 * register into an `int` version and a reference version.
 *
 * [WideHigh] marks the second register of a category-2 (`long`/`double`) value. It is a bookkeeping
 * marker, not a type a value can have on its own.
 */
sealed interface AbstractType {

    /** Bottom: control flow never reaches this point. */
    data object Unreachable : AbstractType

    /** Top: information was lost (an unmodelled opcode, or a genuine type conflict). */
    data object Unknown : AbstractType

    /** The zero constant, usable as an `int` or as a reference. */
    data object Null : AbstractType

    /** The high half of a `long`/`double` in a register pair. */
    data object WideHigh : AbstractType

    /**
     * The raw payload of a `const` whose Dalvik type only a use can decide: `int` or `float` for the
     * 32-bit form, `long` or `double` for the wide one.
     *
     * [wide] is carried through joins because it is the one thing the bits always determine, and it is
     * what stops a two-slot value from ending up in a one-slot local when nothing ever demands a type.
     */
    data class Bits(val wide: Boolean) : AbstractType

    data class Primitive(val kind: PrimitiveKind) : AbstractType

    data class Reference(val internalName: String) : AbstractType

    data class Array(val component: AbstractType, val dimensions: Int) : AbstractType

    /** The result of `new-instance`, before `<init>` has run. */
    data class Uninitialized(val internalName: String) : AbstractType

    companion object {
        val INT: AbstractType = Primitive(PrimitiveKind.INT)
        val LONG: AbstractType = Primitive(PrimitiveKind.LONG)
        val FLOAT: AbstractType = Primitive(PrimitiveKind.FLOAT)
        val DOUBLE: AbstractType = Primitive(PrimitiveKind.DOUBLE)

        /**
         * The join of two abstract types.
         *
         * [Unreachable] is the identity; [Unknown] is absorbing. Primitives widen to `int` only
         * within the JVM `int` family (`boolean`/`byte`/`short`/`char`/`int`), because the JVM uses
         * one storage class for all of them. Everything else that disagrees becomes [Unknown].
         */
        fun merge(first: AbstractType, second: AbstractType, hierarchy: ClassHierarchy): AbstractType {
            if (first == second) return first
            if (first == Unreachable) return second
            if (second == Unreachable) return first
            if (first == Unknown || second == Unknown) return Unknown
            if (first == WideHigh || second == WideHigh) return if (first == second) first else Unknown

            // Raw constant payloads join to `Unknown` rather than to a guess: the bits are still
            // `int`-or-`float`, and only the use site can pick, so the versioning pass resolves it
            // from the first use exactly as it does for the zero constant.
            if (first is Bits || second is Bits) return Unknown

            if (first == Null) return if (second.isReferenceLike) second else Unknown
            if (second == Null) return if (first.isReferenceLike) first else Unknown

            return when {
                first is Primitive && second is Primitive -> mergePrimitives(first, second)

                first is Uninitialized && second is Uninitialized ->
                    if (first.internalName == second.internalName) first else Unknown

                first is Reference && second is Reference ->
                    Reference(hierarchy.commonSuperClass(first.internalName, second.internalName))

                first is Array && second is Array -> mergeArrays(first, second, hierarchy)

                first is Array && second is Reference -> mergeArrayWithReference(first, second, hierarchy)
                first is Reference && second is Array -> mergeArrayWithReference(second, first, hierarchy)

                first is Uninitialized && second is Reference ->
                    if (hierarchy.isAssignable(ObjectType(first.internalName), ObjectType(second.internalName))) {
                        second
                    } else {
                        Unknown
                    }

                first is Reference && second is Uninitialized ->
                    if (hierarchy.isAssignable(ObjectType(second.internalName), ObjectType(first.internalName))) {
                        first
                    } else {
                        Unknown
                    }

                else -> Unknown
            }
        }

        /** True for types that a `null` can merge into. */
        private val AbstractType.isReferenceLike: Boolean
            get() = this is Reference || this is Array || this is Uninitialized

        private fun mergePrimitives(first: Primitive, second: Primitive): AbstractType {
            if (first.kind == second.kind) return first
            val firstIntLike = first.kind.numeric == riad.codes.vortex.ir.NumericKind.INT
            val secondIntLike = second.kind.numeric == riad.codes.vortex.ir.NumericKind.INT
            return if (firstIntLike && secondIntLike) INT else Unknown
        }

        private fun mergeArrays(first: Array, second: Array, hierarchy: ClassHierarchy): AbstractType =
            when {
                first.component == second.component -> first
                first.dimensions != second.dimensions -> Reference(ObjectType.OBJECT.internalName)
                else -> {
                    val merged = merge(first.component, second.component, hierarchy)
                    if (merged == Unknown) Unknown else Array(merged, first.dimensions)
                }
            }

        private fun mergeArrayWithReference(
            array: Array,
            reference: Reference,
            hierarchy: ClassHierarchy,
        ): AbstractType {
            val name = reference.internalName
            return if (name == ObjectType.OBJECT.internalName || name == "java/lang/Cloneable" ||
                name == "java/io/Serializable"
            ) {
                Reference(ObjectType.OBJECT.internalName)
            } else {
                Unknown
            }
        }
    }
}

/** The concrete JVM type a register version will occupy. */
fun AbstractType.toVortexType(fallback: VortexType = VortexTypes.INT): VortexType = when (this) {
    is AbstractType.Primitive -> PrimitiveType(kind)
    is AbstractType.Reference -> ObjectType(internalName)
    is AbstractType.Array -> toVortexArray()
    is AbstractType.Uninitialized -> ObjectType(internalName)
    AbstractType.Null, AbstractType.Unknown, AbstractType.Unreachable, AbstractType.WideHigh -> fallback
    is AbstractType.Bits -> fallback
}

private fun AbstractType.Array.toVortexArray(): VortexType {
    val base = component.toVortexType()
    if (base == VortexTypes.INT && component is AbstractType.Unknown) {
        // An unknown element type is still a reference-shaped array: `[Ljava/lang/Object;`.
        return ArrayType(ObjectType.OBJECT, dimensions)
    }
    return ArrayType(if (base.isVoid) VortexTypes.INT else base, dimensions)
}

/** True when this type occupies a JVM local slot of its own (everything except the high half). */
val AbstractType.isAllocatable: Boolean
    get() = this != AbstractType.WideHigh
