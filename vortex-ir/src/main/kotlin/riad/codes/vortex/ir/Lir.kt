package riad.codes.vortex.ir

/**
 * A specific, **type-stable** version of a DEX register.
 *
 * A DEX register is untyped and is routinely reused for values of different types (an `int` result
 * followed by an object reference, say). The JVM verifier types every local slot for the whole
 * method, so each distinct type a register takes must become its own version, and only versions of
 * the same type may share a slot. This type is the join point between inference and allocation:
 * [riad.codes.vortex.analysis.TypeInference] decides the versions and [RegisterAllocator] maps each
 * version to a JVM local slot.
 */
data class RegisterRef(
    val register: Int,
    val version: Int,
    val type: VortexType,
) {
    /** Slots this version occupies in the JVM local array (2 for `long`/`double`). */
    val slotWidth: Int get() = type.width

    val isCategory2: Boolean get() = type.isCategory2

    override fun toString(): String = "v$register#$version:${type.descriptor}"
}

/** A closed linear index range `[start, end]` in the method's emission order. */
data class LiveRange(val start: Int, val end: Int) {
    init {
        require(start <= end) { "live range start ($start) must not exceed end ($end)" }
    }

    fun overlaps(other: LiveRange): Boolean = start <= other.end && other.start <= end

    fun covers(point: Int): Boolean = point in start..end

    fun union(other: LiveRange): LiveRange = LiveRange(minOf(start, other.start), maxOf(end, other.end))

    override fun toString(): String = "[$start,$end]"
}

/** A `LocalVariableTable` row: a debug name bound to a slot for a range of code. */
data class LocalVariableInfo(
    val name: String,
    val descriptor: String,
    val slot: Int,
    val range: LiveRange,
)

/** A basic block of the low-level IR. */
class BasicBlock(
    val id: Int,
    /** DEX code address this block starts at. */
    val startAddress: Int,
    val kind: Kind = Kind.NORMAL,
) {
    enum class Kind { NORMAL, ENTRY, EXCEPTION_HANDLER, EXIT }

    val instructions: MutableList<LirInstruction> = ArrayList()

    val predecessors: MutableSet<BasicBlock> = LinkedHashSet()

    val successors: MutableSet<BasicBlock> = LinkedHashSet()

    /** Half-open range of linear indices occupied by this block, filled in during lowering. */
    var linearStart: Int = -1

    var linearEnd: Int = -1

    val isExceptionHandler: Boolean get() = kind == Kind.EXCEPTION_HANDLER

    fun add(instruction: LirInstruction): BasicBlock {
        instructions += instruction
        return this
    }

    fun addAll(newInstructions: Iterable<LirInstruction>): BasicBlock {
        instructions += newInstructions
        return this
    }

    /** The instruction that transfers control out of this block, if any. */
    val terminator: LirInstruction?
        get() = instructions.lastOrNull()

    fun isTerminated(): Boolean = when (terminator) {
        is LirInstruction.Goto, is LirInstruction.IfTest,
        is LirInstruction.Switch, is LirInstruction.Return, is LirInstruction.Throw,
        -> true

        else -> false
    }

    override fun toString(): String = "B$id@$startAddress"
}

/**
 * The low-level IR: a register-to-register view of a method that a stack machine can consume.
 *
 * Every instruction reads its operands from JVM local slots and writes its result to a slot, so the
 * operand stack is empty on entry to and exit from each instruction. That invariant is exactly what
 * [riad.codes.vortex.analysis.StackStateAnalyzer] checks, and what makes the generated code
 * trivially verifiable even though DEX itself is register based.
 */
sealed interface LirInstruction {

    data class Move(val dest: RegisterRef, val source: RegisterRef) : LirInstruction

    data class LoadConstant(val dest: RegisterRef, val constant: Constant) : LirInstruction

    /**
     * An operand of a LIR instruction: a value in a local slot, or a literal the JVM can materialise
     * directly with a const opcode. Keeping literals inline avoids inventing DEX registers that never
     * existed, which would otherwise need their own versions and slots.
     */
    sealed interface Operand {
        data class Register(val ref: RegisterRef) : Operand
        data class Literal(val constant: Constant) : Operand

        companion object {
            fun constant(value: Long): Literal = Literal(Constant.IntValue(value.toInt()))
        }
    }

    data class BinaryOp(
        val dest: RegisterRef,
        val operation: BinaryOperation,
        val operandType: VortexType,
        val left: Operand,
        val right: Operand,
    ) : LirInstruction

    data class UnaryOp(
        val dest: RegisterRef,
        val operation: UnaryOperation,
        val operandType: VortexType,
        val source: RegisterRef,
    ) : LirInstruction

    data class Conversion(
        val dest: RegisterRef,
        val from: VortexType,
        val to: VortexType,
        val source: RegisterRef,
    ) : LirInstruction

    data class Compare(
        val dest: RegisterRef,
        val operation: CompareOperation,
        val left: RegisterRef,
        val right: RegisterRef,
    ) : LirInstruction

    data class Return(val value: RegisterRef?) : LirInstruction

    data class Goto(val target: BasicBlock) : LirInstruction

    /**
     * A conditional branch. A `0` literal against a reference-typed left operand is the `null` test
     * (`ifnull`/`ifnonnull`); the emitter picks the opcode, because the type of [left] is what decides
     * between an `int` comparison and a null check.
     */
    data class IfTest(
        val comparison: Comparison,
        val left: Operand,
        val right: Operand,
        val target: BasicBlock,
    ) : LirInstruction

    data class Switch(
        val value: RegisterRef,
        val keys: List<Int>,
        val targets: List<BasicBlock>,
        val defaultTarget: BasicBlock,
    ) : LirInstruction

    data class Invoke(
        val kind: InvokeKind,
        val reference: MethodReference,
        val receiver: RegisterRef?,
        val arguments: List<RegisterRef>,
        val result: RegisterRef?,
        val isInterface: Boolean,
    ) : LirInstruction

    /**
     * A constructor invocation whose receiver was produced by `new-instance`.
     *
     * The JVM tracks freshly allocated objects as *uninitialised* until `<init>` returns, so the
     * emitter folds `new`, `dup` and `invokespecial` into one instruction instead of letting an
     * uninitialised reference live in a local slot across block boundaries.
     */
    data class Construct(
        val reference: MethodReference,
        val receiver: RegisterRef,
        val arguments: List<RegisterRef>,
    ) : LirInstruction

    data class NewArray(
        val dest: RegisterRef,
        val componentType: VortexType,
        val length: RegisterRef,
    ) : LirInstruction

    /**
     * `new <type>[n]` filled from existing slots: the lowering of `filled-new-array`.
     *
     * The count and every index are constants of the instruction rather than registers, and that is
     * why this is its own node instead of a [NewArray] followed by stores: a stack machine wants a
     * literal length and a literal index, and inventing DEX registers to hold them would put values
     * into the register file that the DEX never had — extra versions, extra slots and extra live
     * ranges, all of them noise.
     */
    data class NewArrayFilled(
        val dest: RegisterRef,
        val componentType: VortexType,
        val values: List<RegisterRef>,
    ) : LirInstruction

    /**
     * A `fill-array-data` payload written into an existing array.
     *
     * A class file has no blob-of-elements constant, so the payload becomes the stores it always
     * meant, with constant indices for the same reason as [NewArrayFilled].
     */
    data class ArrayFill(
        val array: RegisterRef,
        val componentType: VortexType,
        val values: List<Constant>,
    ) : LirInstruction

    data class ArrayLoad(
        val dest: RegisterRef,
        val componentType: VortexType,
        val array: RegisterRef,
        val index: RegisterRef,
    ) : LirInstruction

    data class ArrayStore(
        val componentType: VortexType,
        val array: RegisterRef,
        val index: RegisterRef,
        val value: RegisterRef,
    ) : LirInstruction

    data class ArrayLength(val dest: RegisterRef, val array: RegisterRef) : LirInstruction

    /** [instance] is `null` for static reads. */
    data class FieldGet(
        val dest: RegisterRef,
        val reference: FieldReference,
        val instance: RegisterRef?,
    ) : LirInstruction

    data class FieldPut(
        val reference: FieldReference,
        val instance: RegisterRef?,
        val value: RegisterRef,
    ) : LirInstruction

    data class CheckCast(val register: RegisterRef, val type: VortexType) : LirInstruction

    data class InstanceOf(val dest: RegisterRef, val source: RegisterRef, val type: VortexType) : LirInstruction

    data class MonitorEnter(val register: RegisterRef) : LirInstruction

    data class MonitorExit(val register: RegisterRef) : LirInstruction

    data class Throw(val register: RegisterRef) : LirInstruction

    /** `move-exception`: stores the pending throwable held on the operand stack into a slot. */
    data class CatchException(val dest: RegisterRef) : LirInstruction

    /**
     * Discards the pending throwable a catch entry pushes, without storing it anywhere.
     *
     * DEX may omit `move-exception` when the handler never reads the caught value; the split catch
     * trampoline relies on this to throw the pending throwable away before jumping into code that
     * normal control flow also reaches, so the two entries frame-merge with an empty stack.
     */
    object DiscardThrowable : LirInstruction

    /** A translated `nop`, or padding introduced by the lowering pass. */
    object NoOp : LirInstruction

    /** An instruction the engine could not translate; the emitter records it and emits nothing. */
    data class Unsupported(val opcodeName: String, val address: Int) : LirInstruction
}

data class LirExceptionHandler(val exceptionType: VortexType?, val handler: BasicBlock)

/** [end] is `null` when the protected range runs to the end of the method's code. */
data class LirTryBlock(val start: BasicBlock, val end: BasicBlock?, val handlers: List<LirExceptionHandler>)

/** Aggregate figures about one analysed method, carried into the translation report. */
data class MethodAnalysisSummary(
    val registerVersions: Int,
    val basicBlocks: Int,
    val estimatedMaxStack: Int,
    val unsupportedInstructions: Int,
    /** Non-fatal findings: unresolved register types, unverifiable handler layouts, and so on. */
    val warnings: List<String> = emptyList(),
) {
    val isFullyTranslated: Boolean get() = unsupportedInstructions == 0
}

/**
 * A fully analysed, ready-to-emit method: basic blocks of LIR, a slot for every register version and
 * the live ranges that back the generated `LocalVariableTable`.
 */
data class LirMethod(
    val reference: MethodReference,
    val accessFlags: Int,
    val blocks: List<BasicBlock>,
    val entry: BasicBlock,
    /** `maxLocals` for the generated method. */
    val locals: Int,
    val slotAssignment: Map<RegisterRef, Int>,
    val liveRanges: Map<RegisterRef, LiveRange>,
    val localVariables: List<LocalVariableInfo>,
    val lineNumbers: Map<Int, Int>,
    val tryBlocks: List<LirTryBlock>,
    /** Block id at each position of the linear order that live ranges and the variable table use. */
    val blockByLinearIndex: List<Int>,
    val summary: MethodAnalysisSummary,
) {
    val isStatic: Boolean get() = AccessFlags.isStatic(accessFlags)

    val isConstructor: Boolean get() = reference.isConstructor

    fun slotOf(register: RegisterRef): Int =
        slotAssignment[register]
            ?: throw TranslationException("no local slot assigned to $register in ${reference.name}")

    fun blockOf(id: Int): BasicBlock = blocks.first { it.id == id }

    /** The block that owns a linear index, used to place `LocalVariableTable` labels. */
    fun blockIdAt(linearIndex: Int): Int = blockByLinearIndex.getOrElse(linearIndex) { blockByLinearIndex.lastOrNull() ?: 0 }

    fun instructions(): Sequence<LirInstruction> = blocks.asSequence().flatMap { it.instructions.asSequence() }
}

/** A translated class: the declaration plus every analysed method body. */
data class LirClass(
    val declaration: HirClass,
    val methods: List<LirMethod>,
) {
    val type: ObjectType get() = declaration.type

    val fields: List<HirField> get() = declaration.fields

    val annotations: List<HirAnnotation> get() = declaration.annotations

    fun method(reference: MethodReference): LirMethod? = methods.firstOrNull { it.reference == reference }
}
