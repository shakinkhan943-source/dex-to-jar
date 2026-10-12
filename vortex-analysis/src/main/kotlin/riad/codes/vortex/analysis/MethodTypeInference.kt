package riad.codes.vortex.analysis

import riad.codes.vortex.ir.AccessFlags
import riad.codes.vortex.ir.ArrayType
import riad.codes.vortex.ir.Constant
import riad.codes.vortex.ir.HirArrayLength
import riad.codes.vortex.ir.HirArrayLoad
import riad.codes.vortex.ir.HirArrayStore
import riad.codes.vortex.ir.HirBinaryOp
import riad.codes.vortex.ir.HirCheckCast
import riad.codes.vortex.ir.HirCompare
import riad.codes.vortex.ir.HirConst
import riad.codes.vortex.ir.HirConstClass
import riad.codes.vortex.ir.HirConstString
import riad.codes.vortex.ir.HirConversion
import riad.codes.vortex.ir.HirFillArrayData
import riad.codes.vortex.ir.HirFilledNewArray
import riad.codes.vortex.ir.HirGoto
import riad.codes.vortex.ir.HirIfTest
import riad.codes.vortex.ir.HirInstanceFieldGet
import riad.codes.vortex.ir.HirInstanceFieldPut
import riad.codes.vortex.ir.HirInstanceOf
import riad.codes.vortex.ir.HirInstruction
import riad.codes.vortex.ir.HirInvoke
import riad.codes.vortex.ir.HirMethod
import riad.codes.vortex.ir.HirMethodBody
import riad.codes.vortex.ir.HirMonitorEnter
import riad.codes.vortex.ir.HirMonitorExit
import riad.codes.vortex.ir.HirMove
import riad.codes.vortex.ir.HirMoveException
import riad.codes.vortex.ir.HirMoveResult
import riad.codes.vortex.ir.HirNewArray
import riad.codes.vortex.ir.HirNewInstance
import riad.codes.vortex.ir.HirNop
import riad.codes.vortex.ir.HirProgram
import riad.codes.vortex.ir.HirReturn
import riad.codes.vortex.ir.HirStaticFieldGet
import riad.codes.vortex.ir.HirStaticFieldPut
import riad.codes.vortex.ir.HirSwitch
import riad.codes.vortex.ir.HirThrow
import riad.codes.vortex.ir.HirUnaryOp
import riad.codes.vortex.ir.HirUnsupported
import riad.codes.vortex.ir.MethodReference
import riad.codes.vortex.ir.MoveKind
import riad.codes.vortex.ir.ObjectType
import riad.codes.vortex.ir.TranslationException
import riad.codes.vortex.ir.VortexType
import riad.codes.vortex.ir.VortexTypes

/** Register types at one program point: register index to abstract type. */
typealias RegisterState = Map<Int, AbstractType>

/**
 * Abstract interpretation of the Dalvik register file.
 *
 * The interpreter runs the classic worklist fixpoint over the control flow graph, joining register
 * states at every merge point until they stop changing. What comes out is, for every basic block, the
 * type of every register on entry — and from that:
 *
 * * whether `if-eqz v3` is an `int` comparison or a `null` check,
 * * whether `aget` becomes `iaload`, `laload` or `aaload`,
 * * whether `const/4 v0, #0` means `iconst_0` or `aconst_null`,
 * * which registers need separate JVM local slots because they change type.
 *
 * The join at a merge point is [AbstractType.merge]; a register that is never assigned keeps the
 * [AbstractType.Unreachable] bottom value, and a genuinely conflicting join becomes
 * [AbstractType.Unknown], which the versioning pass resolves from the first use and reports.
 */
class MethodTypeInference(
    private val program: HirProgram,
    private val method: HirMethod,
    private val cfg: HirControlFlowGraph,
) : MethodTypeContext {

    override val returnType: VortexType get() = method.returnType

    override val parameterTypes: List<VortexType> get() = method.parameterTypes

    override val isStatic: Boolean get() = AccessFlags.isStatic(method.accessFlags)

    override val definingClass: ObjectType get() = method.definingClass

    val body: HirMethodBody get() = cfg.body

    private val hierarchy = program.hierarchy

    /** Invoke address to the register that receives its `move-result`. */
    val moveResultTargets: Map<Int, Int>

    /** `move-result` address to the type of the value it consumes. */
    private val moveResultTypes: Map<Int, AbstractType>

    /** Handler address to the type of the pending throwable. */
    private val handlerTypes: Map<Int, AbstractType>

    private val entryStates: MutableMap<HirBlock, RegisterState> = HashMap()

    private var iterations = 0

    init {
        val targets = HashMap<Int, Int>()
        val resultTypes = HashMap<Int, AbstractType>()
        val instructions = body.instructions
        instructions.forEachIndexed { index, instruction ->
            val resultType = when (instruction) {
                is HirInvoke -> abstractTypeOf(instruction.reference.returnType)
                // `filled-new-array` produces an array in the very same result register.
                is HirFilledNewArray -> AbstractType.Array(abstractTypeOf(instruction.componentType), 1)
                else -> return@forEachIndexed
            }
            val next = instructions.getOrNull(index + 1) as? HirMoveResult ?: return@forEachIndexed
            targets[instruction.address] = next.dest
            resultTypes[next.address] = resultType
        }
        moveResultTargets = targets
        moveResultTypes = resultTypes
        handlerTypes = buildHandlerTypes()
    }

    /** Performs the fixpoint, then exposes [entryState]. */
    fun run(): MethodTypeInference {
        val worklist = ArrayDeque<HirBlock>()
        val queued = HashSet<HirBlock>()
        entryStates[cfg.entry] = initialState()
        worklist += cfg.entry
        queued += cfg.entry

        while (worklist.isNotEmpty()) {
            if (iterations++ > MAX_ITERATIONS) {
                throw TranslationException(
                    "type inference of ${method.reference} did not converge after $MAX_ITERATIONS iterations",
                )
            }
            val block = worklist.removeFirst()
            queued -= block
            val entry = entryStates[block] ?: continue

            val exit = simulate(block, entry)
            block.successors.forEach { successor ->
                val incoming = if (successor.isExceptionHandler) joinStates(entry, exit) else exit
                val merged = mergeInto(entryStates[successor], incoming)
                if (merged != null) {
                    entryStates[successor] = merged
                    if (queued.add(successor)) worklist += successor
                }
            }
        }
        return this
    }

    /** Runs [transfer] across a block, starting from [entry]. */
    fun simulate(block: HirBlock, entry: RegisterState): RegisterState {
        var state = entry
        block.instructions.forEach { instruction -> state = transfer(instruction, state) }
        return state
    }

    fun entryState(block: HirBlock): RegisterState = entryStates[block] ?: UNREACHABLE_STATE

    /** Register types after [instruction] executes given the state before it. */
    fun transfer(instruction: HirInstruction, state: RegisterState): RegisterState = when (instruction) {
        is HirNop, is HirGoto, is HirIfTest, is HirSwitch, is HirThrow,
        is HirMonitorEnter, is HirMonitorExit, is HirArrayStore, is HirFillArrayData,
        is HirInstanceFieldPut, is HirStaticFieldPut, is HirMoveResult,
        -> state

        // An unsupported instruction poisons the state: nothing after it can be trusted, and the
        // method will be emitted as a reported fallback stub rather than as silently wrong code.
        is HirUnsupported -> poison(state)

        is HirMove -> assign(state, instruction.dest, state[instruction.source] ?: AbstractType.Unreachable)
        is HirMoveException -> assign(state, instruction.dest, handlerTypes[instruction.address] ?: THROWABLE)

        is HirConst -> assign(state, instruction.dest, typeOfConstant(instruction.value))
        is HirConstString -> assign(state, instruction.dest, STRING)
        is HirConstClass -> assign(state, instruction.dest, CLASS_TYPE)

        is HirBinaryOp -> assign(state, instruction.dest, primitiveOf(instruction.kind))
        is HirUnaryOp -> assign(state, instruction.dest, primitiveOf(instruction.kind))
        is HirConversion -> assign(state, instruction.dest, abstractTypeOf(instruction.to))
        is HirCompare -> assign(state, instruction.dest, AbstractType.INT)

        is HirInvoke -> {
            val target = moveResultTargets[instruction.address]
            if (target == null) {
                state
            } else {
                assign(state, target, abstractTypeOf(instruction.reference.returnType))
            }
        }

        is HirNewInstance -> assign(state, instruction.dest, AbstractType.Uninitialized(instruction.type.internalName))

        is HirNewArray -> assign(
            state,
            instruction.dest,
            AbstractType.Array(abstractTypeOf(instruction.componentType), 1),
        )

        is HirArrayLoad -> {
            // The array's own type is authoritative (`baload`/`iaload` follow the array, not the
            // opcode mnemonic); the opcode's hint only covers arrays whose type is not known.
            val fromArray = (state[instruction.array] as? AbstractType.Array)?.component
            val element = fromArray ?: instruction.componentType?.let { abstractTypeOf(it) } ?: AbstractType.Unknown
            assign(state, instruction.dest, element)
        }

        is HirFilledNewArray -> {
            val target = moveResultTargets[instruction.address]
            // Verifiable DEX always pairs the allocation with a `move-result-object`; without one the
            // array would be unreachable, so the state is simply left alone.
            if (target == null) {
                state
            } else {
                assign(state, target, AbstractType.Array(abstractTypeOf(instruction.componentType), 1))
            }
        }

        is HirArrayLength -> assign(state, instruction.dest, AbstractType.INT)
        is HirInstanceFieldGet -> assign(state, instruction.dest, abstractTypeOf(instruction.reference.type))
        is HirStaticFieldGet -> assign(state, instruction.dest, abstractTypeOf(instruction.reference.type))
        is HirCheckCast -> assign(state, instruction.register, abstractTypeOf(instruction.type))
        is HirInstanceOf -> assign(state, instruction.dest, AbstractType.INT)
        is HirReturn -> state
    }

    /** The type of the value an invoke hands to its `move-result`. */
    fun resultTypeOf(invoke: HirInvoke): AbstractType = abstractTypeOf(invoke.reference.returnType)

    fun resultTypeAt(address: Int): AbstractType? = moveResultTypes[address]

    /** The declared parameter area of the frame, used by allocation to pin parameter slots. */
    fun parameterRegisters(): List<Pair<Int, VortexType>> {
        val registers = ArrayList<Pair<Int, VortexType>>(method.parameters.size + 1)
        var register = body.parameterRegisterBase
        if (!isStatic) {
            registers += register to method.definingClass
            register += 1
        }
        method.parameters.forEach { parameter ->
            registers += register to parameter.type
            register += parameter.type.width
        }
        return registers
    }

    /** Registers the code reads before anything has written them. */
    fun undefinedReads(): Set<Int> {
        val undefined = LinkedHashSet<Int>()
        cfg.blocks.forEach { block ->
            var state = entryState(block)
            block.instructions.forEach { instruction ->
                RegisterAccess.reads(instruction, moveResultTargets).forEach { register ->
                    if (state[register] == null || state[register] == AbstractType.Unreachable) undefined += register
                }
                state = transfer(instruction, state)
            }
        }
        return undefined
    }

    private fun initialState(): RegisterState {
        val state = HashMap<Int, AbstractType>(body.registerCount * 2)
        for (register in 0 until body.registerCount) state[register] = AbstractType.Unreachable
        parameterRegisters().forEach { (register, type) ->
            state[register] = abstractTypeOf(type)
            if (type.isCategory2) state[register + 1] = AbstractType.WideHigh
        }
        return state
    }

    private fun buildHandlerTypes(): Map<Int, AbstractType> {
        val types = HashMap<Int, AbstractType>()
        body.tryBlocks.forEach { tryBlock ->
            tryBlock.handlers.forEach { handler ->
                val type = handler.exceptionType?.let { abstractTypeOf(it) } ?: THROWABLE
                val existing = types[handler.handlerAddress]
                if (existing == null || (existing == THROWABLE && type != THROWABLE)) {
                    types[handler.handlerAddress] = type
                }
            }
        }
        return types
    }

    /** Marks every known register as [AbstractType.Unknown] after an untranslatable instruction. */
    private fun poison(state: RegisterState): RegisterState {
        val copy = HashMap<Int, AbstractType>(state.size * 2)
        state.keys.forEach { register -> copy[register] = AbstractType.Unknown }
        return copy
    }

    private fun assign(state: RegisterState, register: Int, type: AbstractType): RegisterState {
        if (state[register] == type && (state[register + 1] != AbstractType.WideHigh || type.isWide)) return state
        val copy = HashMap(state)
        copy.remove(register + 1)
        copy[register] = type
        if (type.isWide) copy[register + 1] = AbstractType.WideHigh
        return copy
    }

    private fun typeOfConstant(constant: Constant): AbstractType = when (constant) {
        is Constant.IntValue -> AbstractType.INT
        is Constant.LongValue -> AbstractType.LONG
        is Constant.FloatValue -> AbstractType.FLOAT
        is Constant.DoubleValue -> AbstractType.DOUBLE
        is Constant.StringValue -> STRING
        is Constant.TypeValue -> CLASS_TYPE
        is Constant.MethodTypeValue -> METHOD_TYPE
        is Constant.MethodHandleValue -> METHOD_HANDLE
        Constant.Zero -> AbstractType.Null
        // `int` versus `float`, `long` versus `double`: only a use can tell.
        is Constant.NarrowBits -> AbstractType.Bits(wide = false)
        is Constant.WideBits -> AbstractType.Bits(wide = true)
    }

    private fun mergeInto(target: RegisterState?, incoming: RegisterState): RegisterState? {
        if (target == null) return incoming
        var changed = false
        val merged = HashMap<Int, AbstractType>(maxOf(target.size, incoming.size) * 2)
        val registers = HashSet<Int>(target.keys)
        registers += incoming.keys
        registers.forEach { register ->
            val before = target[register] ?: AbstractType.Unreachable
            val after = incoming[register] ?: AbstractType.Unreachable
            val joined = AbstractType.merge(before, after, hierarchy)
            merged[register] = joined
            if (joined != target[register]) changed = true
        }
        return if (changed) merged else null
    }

    private fun joinStates(first: RegisterState, second: RegisterState): RegisterState = mergeInto(first, second) ?: first

    companion object {
        private const val MAX_ITERATIONS = 100_000

        private val STRING = AbstractType.Reference(ObjectType.STRING.internalName)
        private val CLASS_TYPE = AbstractType.Reference(ObjectType.CLASS.internalName)
        private val THROWABLE = AbstractType.Reference(ObjectType.THROWABLE.internalName)
        private val METHOD_TYPE = AbstractType.Reference("java/lang/invoke/MethodType")
        private val METHOD_HANDLE = AbstractType.Reference("java/lang/invoke/MethodHandle")

        private val UNREACHABLE_STATE: RegisterState = emptyMap()

        /** The abstract type of a concrete JVM type. */
        fun abstractTypeOf(type: VortexType): AbstractType = when (type) {
            is riad.codes.vortex.ir.PrimitiveType -> AbstractType.Primitive(type.kind)
            is ObjectType -> AbstractType.Reference(type.internalName)
            is ArrayType -> AbstractType.Array(abstractTypeOf(type.component), type.dimensions)
        }

        private fun primitiveOf(kind: riad.codes.vortex.ir.NumericKind): AbstractType = when (kind) {
            riad.codes.vortex.ir.NumericKind.INT -> AbstractType.INT
            riad.codes.vortex.ir.NumericKind.LONG -> AbstractType.LONG
            riad.codes.vortex.ir.NumericKind.FLOAT -> AbstractType.FLOAT
            riad.codes.vortex.ir.NumericKind.DOUBLE -> AbstractType.DOUBLE
        }

        private val AbstractType.isWide: Boolean
            get() = this is AbstractType.Primitive && kind.wide
    }
}
