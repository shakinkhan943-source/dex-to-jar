package riad.codes.vortex.analysis

import riad.codes.vortex.ir.ArrayType
import riad.codes.vortex.ir.HirIfTest
import riad.codes.vortex.ir.HirInstruction
import riad.codes.vortex.ir.ObjectType
import riad.codes.vortex.ir.PrimitiveKind
import riad.codes.vortex.ir.PrimitiveType
import riad.codes.vortex.ir.RegisterRef
import riad.codes.vortex.ir.TranslationException
import riad.codes.vortex.ir.VortexType
import riad.codes.vortex.ir.VortexTypes

/**
 * How a value is stored in a JVM local slot, and how wide it is.
 *
 * Versions are keyed by storage class rather than by exact type, and that is the whole trick: two
 * paths that reach a merge point with `int` and `char`, or with `String` and `Object`, keep the same
 * version and therefore the same slot, and the JVM computes the merged frame itself. Splitting on the
 * exact type would need phi copies at every merge point for no benefit.
 */
enum class StorageClass(val width: Int) {
    INT(1),
    LONG(2),
    FLOAT(1),
    DOUBLE(2),
    REFERENCE(1),
    ;

    companion object {
        /** The storage class of a concrete type, or `null` for `void` and array-of-void oddities. */
        fun ofType(type: VortexType): StorageClass = when (type) {
            is PrimitiveType -> when (type.kind) {
                PrimitiveKind.VOID -> INT
                PrimitiveKind.BOOLEAN, PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.CHAR, PrimitiveKind.INT -> INT
                PrimitiveKind.LONG -> LONG
                PrimitiveKind.FLOAT -> FLOAT
                PrimitiveKind.DOUBLE -> DOUBLE
            }

            else -> REFERENCE
        }

        /** The storage class of an abstract type, or `null` when the first use has to decide. */
        fun ofAbstract(type: AbstractType?): StorageClass? = when (type) {
            null -> null
            is AbstractType.Primitive -> ofType(PrimitiveType(type.kind))
            is AbstractType.Reference, is AbstractType.Array, is AbstractType.Uninitialized -> REFERENCE
            AbstractType.Null, AbstractType.Unknown, AbstractType.Unreachable, AbstractType.WideHigh -> null
            // A raw constant payload has no storage class until a use names one.
            is AbstractType.Bits -> null
        }
    }
}

/** One analysed version of a DEX register: the unit a JVM local slot is allocated for. */
data class RegisterVersionInfo(
    val ref: RegisterRef,
    val storage: StorageClass,
    /** The instruction that defines this version. */
    val definitionAddress: Int?,
    /** True when the version was introduced purely from the first use of an ambiguous value. */
    val resolvedFromUse: Boolean,
)

/** Every register version of a method, plus where each version is read and written. */
class MethodVersions(
    val definitions: Map<Int, Map<Int, RegisterRef>>,
    val reads: Map<Int, Map<Int, RegisterRef>>,
    val versions: List<RegisterVersionInfo>,
    val orderedRefs: List<RegisterRef>,
    val storageOf: Map<RegisterRef, StorageClass>,
    /** Registers whose type had to be guessed because the DEX never determines it. */
    val unresolvedRegisters: Set<Int>,
    val warnings: List<String>,
) {
    fun definitionAt(address: Int, register: Int): RegisterRef? = definitions[address]?.get(register)

    /**
     * The version a register holds on method entry.
     *
     * Parameter versions are created before any instruction is analysed, so the entry version is
     * always the first one — and its slot is the parameter slot the JVM fixes from the signature.
     */
    fun entryVersion(register: Int): RegisterRef? = orderedRefs.firstOrNull { it.register == register && it.version == 0 }

    fun readAt(address: Int, register: Int): RegisterRef =
        reads[address]?.get(register)
            ?: throw TranslationException(
                "register v$register is read at code unit $address but no version was created for it",
            )

    fun readsAt(address: Int): Map<Int, RegisterRef> = reads[address] ?: emptyMap()

    fun definitionsAt(address: Int): Map<Int, RegisterRef> = definitions[address] ?: emptyMap()

    fun storage(ref: RegisterRef): StorageClass = storageOf[ref] ?: StorageClass.ofType(ref.type)
}

/**
 * Assigns a type-stable version to every DEX register.
 *
 * The pass replays the inference results block by block while keeping, for each register, the version
 * currently bound to it. A register gets a new version only when the storage class it holds changes —
 * `int` to reference, say — because that is exactly when the JVM needs a different local slot and a
 * different load/store opcode.
 *
 * The ambiguous cases come from Dalvik itself: `const/4 v0, #0` is `int 0` *and* `null`, and the ART
 * verifier resolves it from the use. This pass does the same: the first use that demands a concrete
 * type (a receiver, a field of known type, an array element, a `move-object`, …) decides what the
 * constant means, and the register gets an `int` version or a reference version accordingly.
 */
class RegisterVersioning(
    private val inference: MethodTypeInference,
    private val cfg: HirControlFlowGraph,
) {
    private val identity = LinkedHashMap<Pair<Int, StorageClass>, RegisterRef>()
    private val ordinals = HashMap<Int, Int>()
    private val infos = LinkedHashMap<RegisterRef, RegisterVersionInfo>()
    private val definitions = HashMap<Int, MutableMap<Int, RegisterRef>>()
    private val reads = HashMap<Int, MutableMap<Int, RegisterRef>>()
    private val unresolved = LinkedHashSet<Int>()
    private val warnings = ArrayList<String>()

    private lateinit var uses: UsesIndex

    fun run(): MethodVersions {
        uses = UsesIndex(cfg, inference)
        preCreateParameterVersions()

        cfg.blocks.sortedBy { it.startAddress }.forEach { block ->
            var state = inference.entryState(block)
            block.instructions.forEach { instruction ->
                recordReads(instruction, state)
                state = inference.transfer(instruction, state)
                recordWrites(instruction, state)
            }
        }

        reportUndefinedReads()

        if (System.getProperty("vortex.debug.versions") != null) {
            System.err.println("DBG versions:")
            infos.values.forEach { info ->
                System.err.println(
                    "  v${info.ref.register}#${info.ref.version} ${info.storage} ${info.ref.type} def@${info.definitionAddress} useResolved=${info.resolvedFromUse}",
                )
            }
            System.err.println("DBG reads:")
            reads.toSortedMap().forEach { (address, map) ->
                System.err.println(
                    "  @$address " + map.entries.joinToString(", ") { (r, ref) -> "v$r->v${ref.register}#${ref.version}:${ref.type}" },
                )
            }
            System.err.println("DBG defs:")
            definitions.toSortedMap().forEach { (address, map) ->
                System.err.println(
                    "  @$address " + map.entries.joinToString(", ") { (r, ref) -> "v$r->v${ref.register}#${ref.version}:${ref.type}" },
                )
            }
            System.err.println("DBG entry states:")
            cfg.blocks.sortedBy { it.startAddress }.forEach { block ->
                val state = inference.entryState(block)
                System.err.println(
                    "  block@${block.startAddress} " + (state?.entries?.joinToString(", ") { (r, t) -> "v$r=$t" } ?: "none"),
                )
            }
        }

        return MethodVersions(
            definitions = definitions,
            reads = reads,
            versions = infos.values.toList(),
            orderedRefs = infos.keys.toList(),
            storageOf = infos.mapValues { it.value.storage },
            unresolvedRegisters = unresolved,
            warnings = warnings,
        )
    }

    /**
     * Parameter versions exist before any instruction runs, because their JVM slots are fixed by the
     * method signature: the emitter must not be able to allocate anything else into them.
     */
    private fun preCreateParameterVersions() {
        inference.parameterRegisters().forEach { (register, type) ->
            val storage = StorageClass.ofType(type)
            identity[register to storage] = RegisterRef(register, 0, type)
            ordinals[register] = 1
            infos[identity[register to storage]!!] = RegisterVersionInfo(
                ref = identity[register to storage]!!,
                storage = storage,
                definitionAddress = null,
                resolvedFromUse = false,
            )
        }
    }

    private fun recordReads(instruction: HirInstruction, state: RegisterState) {
        val readRegisters = RegisterAccess.reads(instruction, inference.moveResultTargets)
        if (readRegisters.isEmpty()) return
        val map = HashMap<Int, RegisterRef>(readRegisters.size * 2)
        // An equality `if-*` test over two registers decides its operands *together*: the JVM has
        // only `if_acmp*` or `if_icmp*` shapes, so a compare whose two reads came out with different
        // storage classes is not merely unsound — it cannot be emitted at all. Per-register state
        // lookup optimistically anchors each side independently, which is exactly how a compare can
        // end up with one operand in a reference epoch and the other in an int epoch. For that
        // two-register shape, the reads take one joint decision computed for the pair instead.
        val joint = ifTestJointStorage(instruction, readRegisters, state)
        readRegisters.forEach { register ->
            map[register] = versionFor(instruction, register, state[register], state, joint)
        }
        reads[instruction.address] = map
    }

    /**
     * The storage class its operand registers share on an equality compare, or `null` when the
     * instruction is not that shape and each read is decided on its own.
     *
     * A decisive reference claim on either side wins (`if_acmp*`); a decisive numeric claim anchors
     * both sides to int (`if_icmp*`); two null claims mean the compare is a null-identity test; and
     * purely ambiguous values take the int default that the old per-register path used.
     */
    private fun ifTestJointStorage(
        instruction: HirInstruction,
        readRegisters: List<Int>,
        state: RegisterState,
    ): StorageClass? {
        if (instruction !is HirIfTest || !instruction.comparison.isEquality) return null
        if (readRegisters.size != 2) return null
        val claims = readRegisters.map { state[it] }
        val decisiveReference = claims.any { it is AbstractType.Reference || it is AbstractType.Array }
        if (decisiveReference) return StorageClass.REFERENCE
        val decisivePrimitive = claims.any { it is AbstractType.Primitive }
        if (decisivePrimitive) return StorageClass.INT
        val nullClaim = claims.any { it == AbstractType.Null }
        if (nullClaim) return StorageClass.REFERENCE
        return StorageClass.INT
    }

    private fun recordWrites(instruction: HirInstruction, state: RegisterState) {
        val written = RegisterAccess.writes(instruction, inference.moveResultTargets)
        if (written.isEmpty()) return
        val map = HashMap<Int, RegisterRef>(written.size * 2)
        written.forEach { register ->
            map[register] = versionFor(instruction, register, state[register], state)
        }
        definitions[instruction.address] = map
    }

    private fun versionFor(
        instruction: HirInstruction,
        register: Int,
        type: AbstractType?,
        state: RegisterState,
        forcedStorage: StorageClass? = null,
    ): RegisterRef {
        val storage = forcedStorage ?: (StorageClass.ofAbstract(type) ?: StorageClass.ofType(
            resolveAmbiguous(instruction, register, type, state),
        ))
        val resolved = StorageClass.ofAbstract(type) == null
        val concrete = when {
            resolved && forcedStorage != null -> defaultType(storage)
            resolved -> concreteType(storage, instruction, register, type, state)
            else -> concreteTypeOf(type!!)
        }
        val key = register to storage
        return identity.getOrPut(key) {
            val ordinal = ordinals.getOrDefault(register, 0)
            ordinals[register] = ordinal + 1
            val ref = RegisterRef(register, ordinal, concrete)
            infos[ref] = RegisterVersionInfo(
                ref = ref,
                storage = storage,
                definitionAddress = if (RegisterAccess.writes(instruction, inference.moveResultTargets).contains(register)) {
                    instruction.address
                } else {
                    null
                },
                resolvedFromUse = resolved,
            )
            ref
        }
    }

    /** The type of an ambiguous value, taken from the instruction that uses it. */
    private fun resolveAmbiguous(
        instruction: HirInstruction,
        register: Int,
        type: AbstractType?,
        state: RegisterState,
    ): VortexType {
        RegisterAccess.expectedType(instruction, register, inference, state)?.let { return it }
        arrayElementType(instruction, register, state)?.let { return it }
        uses.firstExpectedTypeAfter(register, instruction.address)?.let { return it }
        unresolved += register
        // A raw wide payload defaults to the two-slot `long` rather than to an `int`, so a value
        // whose type nothing in the DEX ever states still lands in a local of the right width.
        return if (type is AbstractType.Bits && type.wide) VortexTypes.LONG else VortexTypes.INT
    }

    /**
     * The element type demanded by a type-agnostic `aput`.
     *
     * `aput`/`aput-wide` do not say whether the element is an `int` or a `float`. The array's own
     * inferred element type does, and that is what a `double[]` written from raw constant bits needs in
     * order to land in a `double` slot instead of a `long` one.
     */
    private fun arrayElementType(
        instruction: HirInstruction,
        register: Int,
        state: RegisterState,
    ): VortexType? {
        if (instruction !is riad.codes.vortex.ir.HirArrayStore) return null
        if (register != instruction.value) return null
        instruction.componentType?.let { return it }
        val component = (state[instruction.array] as? AbstractType.Array)?.component ?: return null
        return when (component) {
            is AbstractType.Primitive -> PrimitiveType(component.kind)
            is AbstractType.Reference -> ObjectType(component.internalName)
            is AbstractType.Uninitialized -> ObjectType(component.internalName)
            else -> null
        }
    }

    private fun concreteType(
        storage: StorageClass,
        instruction: HirInstruction,
        register: Int,
        type: AbstractType?,
        state: RegisterState,
    ): VortexType {
        val resolved = resolveAmbiguous(instruction, register, type, state)
        return if (StorageClass.ofType(resolved) == storage) resolved else defaultType(storage)
    }

    private fun concreteTypeOf(type: AbstractType): VortexType = when (type) {
        is AbstractType.Primitive -> PrimitiveType(type.kind)
        is AbstractType.Reference -> ObjectType(type.internalName)
        is AbstractType.Uninitialized -> ObjectType(type.internalName)
        is AbstractType.Array -> ArrayType(
            (type.component as? AbstractType.Primitive)?.let { PrimitiveType(it.kind) }
                ?: (type.component as? AbstractType.Reference)?.let { ObjectType(it.internalName) }
                ?: VortexTypes.OBJECT,
            type.dimensions,
        )

        else -> VortexTypes.INT
    }

    private fun reportUndefinedReads() {
        val undefined = inference.undefinedReads()
        if (undefined.isEmpty()) return
        unresolved += undefined
        warnings +=
            "reads ${undefined.size} register(s) that no instruction on every path defines: " +
            "${undefined.sorted().joinToString { "v$it" }}"
    }

    private companion object {
        fun defaultType(storage: StorageClass): VortexType = when (storage) {
            StorageClass.INT -> VortexTypes.INT
            StorageClass.LONG -> VortexTypes.LONG
            StorageClass.FLOAT -> VortexTypes.FLOAT
            StorageClass.DOUBLE -> VortexTypes.DOUBLE
            StorageClass.REFERENCE -> VortexTypes.OBJECT
        }
    }
}

/**
 * An index of "which instruction reads register v?", used to resolve a value whose Dalvik type is
 * genuinely ambiguous.
 */
class UsesIndex(
    cfg: HirControlFlowGraph,
    private val inference: MethodTypeInference,
) {
    private val instructionByAddress: Map<Int, HirInstruction>
    private val useAddressesByRegister: Map<Int, List<Int>>

    init {
        val byAddress = HashMap<Int, HirInstruction>()
        val uses = HashMap<Int, MutableList<Int>>()
        cfg.blocks.sortedBy { it.startAddress }.forEach { block ->
            block.instructions.forEach { instruction ->
                byAddress[instruction.address] = instruction
                RegisterAccess.reads(instruction, inference.moveResultTargets).forEach { register ->
                    uses.getOrPut(register) { ArrayList(2) } += instruction.address
                }
            }
        }
        instructionByAddress = byAddress
        useAddressesByRegister = uses.mapValues { (_, addresses) -> addresses.sorted() }
    }

    /** The first unambiguous expected type demanded by a later use of [register]. */
    fun firstExpectedTypeAfter(register: Int, address: Int): VortexType? {
        val addresses = useAddressesByRegister[register] ?: return null
        var index = addresses.binarySearch(address)
        if (index < 0) index = -(index + 1) else index += 1
        while (index < addresses.size) {
            val candidate = instructionByAddress[addresses[index]] ?: return null
            RegisterAccess.expectedType(candidate, register, inference)?.let { return it }
            index++
        }
        return null
    }
}
