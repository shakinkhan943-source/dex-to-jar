package riad.codes.vortex.ir

// ---------------------------------------------------------------------------------------------
// Constants and operands
// ---------------------------------------------------------------------------------------------

/** A compile-time constant that can appear on the JVM operand stack via `ldc` or a const opcode. */
sealed interface Constant {
    data class IntValue(val value: Int) : Constant
    data class LongValue(val value: Long) : Constant
    data class FloatValue(val value: Float) : Constant
    data class DoubleValue(val value: Double) : Constant
    data class StringValue(val value: String) : Constant
    data class TypeValue(val type: VortexType) : Constant
    data class MethodTypeValue(val methodType: MethodType) : Constant
    data class MethodHandleValue(val reference: MethodReference, val kind: Int, val isInterface: Boolean) : Constant

    /** `const/4 v0, #0` with no type information yet: the inference pass decides what it means. */
    object Zero : Constant

    /**
     * The raw 32-bit payload of a `const`/`const/high16`, whose Dalvik type the use site decides.
     *
     * Dalvik has no typed constants: `const/high16 v0, #0x40200000` is the `int` 1075838976 *and* the
     * `float` 2.5f, and only the instruction that consumes it says which. Keeping the bits rather
     * than guessing at decode time is what lets a `float` register get a `float` local slot and an
     * `ldc` of the right flavour.
     */
    data class NarrowBits(val bits: Int) : Constant

    /** The raw 64-bit payload of a `const-wide`, resolved to `long` or `double` by the use site. */
    data class WideBits(val bits: Long) : Constant

    /** The type this constant materialises on the JVM operand stack. */
    val stackType: VortexType
        get() = when (this) {
            is IntValue, Zero, is NarrowBits -> VortexTypes.INT
            is LongValue, is WideBits -> VortexTypes.LONG
            is FloatValue -> VortexTypes.FLOAT
            is DoubleValue -> VortexTypes.DOUBLE
            is StringValue -> ObjectType.STRING
            is TypeValue -> ObjectType.CLASS
            is MethodTypeValue -> ObjectType("java/lang/invoke/MethodType")
            is MethodHandleValue -> ObjectType("java/lang/invoke/MethodHandle")
        }
}

/** A register or an inline literal, as used by binary operations and conditional branches. */
sealed interface HirOperand {
    data class Register(val register: Int) : HirOperand
    data class Literal(val value: Long) : HirOperand

    val registerOrNull: Int?
        get() = (this as? Register)?.register
}

// ---------------------------------------------------------------------------------------------
// Operation enums
// ---------------------------------------------------------------------------------------------

/** Binary arithmetic and bitwise operations, shared by every numeric family. */
enum class BinaryOperation { ADD, SUBTRACT, MULTIPLY, DIVIDE, REMAINDER, AND, OR, XOR, SHIFT_LEFT, SHIFT_RIGHT, UNSIGNED_SHIFT_RIGHT }

enum class UnaryOperation { NEGATE, NOT }

/** `cmp-long`, `cmpl-float`, `cmpg-float`, `cmpl-double`, `cmpg-double`. */
enum class CompareOperation { LONG, FLOAT_LOW, FLOAT_HIGH, DOUBLE_LOW, DOUBLE_HIGH }

/** The six comparison relations used by `if-*` instructions. */
enum class Comparison(val dexSuffix: String) {
    EQUAL("eq"),
    NOT_EQUAL("ne"),
    LESS_THAN("lt"),
    GREATER_EQUAL("ge"),
    GREATER_THAN("gt"),
    LESS_EQUAL("le"),
    ;

    /** The relation with the operands exchanged, e.g. `a > b` becomes `b < a`. */
    fun swapped(): Comparison = when (this) {
        EQUAL -> EQUAL
        NOT_EQUAL -> NOT_EQUAL
        LESS_THAN -> GREATER_THAN
        GREATER_EQUAL -> LESS_EQUAL
        GREATER_THAN -> LESS_THAN
        LESS_EQUAL -> GREATER_EQUAL
    }

    val isEquality: Boolean get() = this == EQUAL || this == NOT_EQUAL

    companion object {
        fun fromDexSuffix(suffix: String): Comparison? = entries.firstOrNull { it.dexSuffix == suffix }
    }
}

enum class InvokeKind(val dexName: String) {
    VIRTUAL("invoke-virtual"),
    DIRECT("invoke-direct"),
    STATIC("invoke-static"),
    INTERFACE("invoke-interface"),
    SUPER("invoke-super"),
}

enum class MoveKind { INT, OBJECT, WIDE }

// ---------------------------------------------------------------------------------------------
// Encoded values and annotations
// ---------------------------------------------------------------------------------------------

/** Annotation visibilities, matching `dexlib2`'s values. */
object AnnotationVisibility {
    const val BUILD = 0
    const val RUNTIME = 1
    const val SYSTEM = 2
}

/** A `Dalvik` encoded value, restricted to the subset the JVM can represent. */
sealed interface HirEncodedValue {
    data class IntValue(val value: Int) : HirEncodedValue
    data class LongValue(val value: Long) : HirEncodedValue
    data class FloatValue(val value: Float) : HirEncodedValue
    data class DoubleValue(val value: Double) : HirEncodedValue
    data class StringValue(val value: String) : HirEncodedValue
    data class TypeValue(val type: VortexType) : HirEncodedValue
    data class BooleanValue(val value: Boolean) : HirEncodedValue
    data class ByteValue(val value: Byte) : HirEncodedValue
    data class ShortValue(val value: Short) : HirEncodedValue
    data class CharValue(val value: Char) : HirEncodedValue
    object NullValue : HirEncodedValue
    data class EnumValue(val constant: FieldReference) : HirEncodedValue
    data class ArrayValue(val values: List<HirEncodedValue>) : HirEncodedValue
    data class AnnotationValue(val annotation: HirAnnotation) : HirEncodedValue
    data class MethodTypeValue(val methodType: MethodType) : HirEncodedValue
}

data class HirAnnotationElement(val name: String, val value: HirEncodedValue)

data class HirAnnotation(
    val type: VortexType,
    val visibility: Int,
    val elements: List<HirAnnotationElement>,
) {
    val isRuntimeVisible: Boolean get() = visibility == AnnotationVisibility.RUNTIME

    fun element(name: String): HirEncodedValue? = elements.firstOrNull { it.name == name }?.value
}

// ---------------------------------------------------------------------------------------------
// Instructions
// ---------------------------------------------------------------------------------------------

/**
 * A decoded Dalvik instruction in the high-level IR.
 *
 * Every instruction keeps the [address] it was decoded from so branch targets, try ranges and line
 * numbers survive the trip from DEX to the CFG.
 */
sealed interface HirInstruction {
    val address: Int
}

data class HirNop(override val address: Int) : HirInstruction

data class HirMove(override val address: Int, val dest: Int, val source: Int, val kind: MoveKind) : HirInstruction

/** Consumes the result of the immediately preceding invoke. */
data class HirMoveResult(override val address: Int, val dest: Int) : HirInstruction    /** `move-exception`: legal only as the first instruction of an exception handler. */
    data class HirMoveException(override val address: Int, val dest: Int) : HirInstruction

data class HirConst(override val address: Int, val dest: Int, val value: Constant) : HirInstruction

data class HirConstString(override val address: Int, val dest: Int, val value: String) : HirInstruction

data class HirConstClass(override val address: Int, val dest: Int, val type: VortexType) : HirInstruction

data class HirBinaryOp(
    override val address: Int,
    val dest: Int,
    val operation: BinaryOperation,
    val kind: NumericKind,
    val left: HirOperand,
    val right: HirOperand,
) : HirInstruction

data class HirUnaryOp(
    override val address: Int,
    val dest: Int,
    val operation: UnaryOperation,
    val kind: NumericKind,
    val operand: Int,
) : HirInstruction

data class HirConversion(
    override val address: Int,
    val dest: Int,
    val source: Int,
    val from: VortexType,
    val to: VortexType,
) : HirInstruction

data class HirCompare(
    override val address: Int,
    val dest: Int,
    val operation: CompareOperation,
    val left: Int,
    val right: Int,
) : HirInstruction

/** [register] is `null` for `return-void`; the declared return type lives on the enclosing method. */
data class HirReturn(override val address: Int, val register: Int?) : HirInstruction

data class HirGoto(override val address: Int, val target: Int) : HirInstruction

/**
 * A conditional branch.
 *
 * [right] is a register for two-register forms, and a literal for the zero and literal forms, so
 * the emitter never has to re-inspect the original DEX instruction.
 */
data class HirIfTest(
    override val address: Int,
    val comparison: Comparison,
    val left: Int,
    val right: HirOperand,
    val target: Int,
) : HirInstruction

data class HirSwitch(
    override val address: Int,
    val value: Int,
    val keys: List<Int>,
    val targets: List<Int>,
    val defaultTarget: Int,
) : HirInstruction

data class HirInvoke(
    override val address: Int,
    val kind: InvokeKind,
    val reference: MethodReference,
    val arguments: List<Int>,
    val isRange: Boolean,
) : HirInstruction

data class HirNewInstance(override val address: Int, val dest: Int, val type: ObjectType) : HirInstruction

data class HirNewArray(
    override val address: Int,
    val dest: Int,
    val componentType: VortexType,
    val length: Int,
) : HirInstruction

/**
 * `filled-new-array v0, v1, ..` — an array allocated from a fixed set of registers.
 *
 * The register that receives the array is the one named by the `move-result-object` that follows,
 * exactly as for an invoke; [riad.codes.vortex.analysis.RegisterAccess] resolves it through
 * `moveResultTargets`, so no destination is stored on the instruction itself.
 */
data class HirFilledNewArray(
    override val address: Int,
    val componentType: VortexType,
    val values: List<Int>,
    val isRange: Boolean,
) : HirInstruction

/**
 * `[componentType]` is a hint from the opcode (`aget-byte` knows it is a `byte`). It is `null` for
 * the type-agnostic forms (`aget`, `aget-wide`, `aget-object`) where only the inferred array type
 * can say whether the element is an `int`, a `long`, a `double` or a reference.
 */
data class HirArrayLoad(
    override val address: Int,
    val dest: Int,
    val componentType: VortexType?,
    val array: Int,
    val index: Int,
) : HirInstruction

data class HirArrayStore(
    override val address: Int,
    val componentType: VortexType?,
    val array: Int,
    val index: Int,
    val value: Int,
) : HirInstruction

data class HirArrayLength(override val address: Int, val dest: Int, val array: Int) : HirInstruction

data class HirInstanceFieldGet(
    override val address: Int,
    val dest: Int,
    val reference: FieldReference,
    val instance: Int,
) : HirInstruction

data class HirInstanceFieldPut(
    override val address: Int,
    val reference: FieldReference,
    val instance: Int,
    val value: Int,
) : HirInstruction

data class HirStaticFieldGet(override val address: Int, val dest: Int, val reference: FieldReference) : HirInstruction

data class HirStaticFieldPut(override val address: Int, val reference: FieldReference, val value: Int) : HirInstruction

data class HirCheckCast(override val address: Int, val register: Int, val type: VortexType) : HirInstruction

data class HirInstanceOf(
    override val address: Int,
    val dest: Int,
    val source: Int,
    val type: VortexType,
) : HirInstruction

data class HirMonitorEnter(override val address: Int, val register: Int) : HirInstruction

data class HirMonitorExit(override val address: Int, val register: Int) : HirInstruction

data class HirThrow(override val address: Int, val register: Int) : HirInstruction

/**
 * `fill-array-data vAA, +BBBBBBBB`: the elements of an `array-data` payload written into an array.
 *
 * [elements] holds each element as the raw bits the payload carried, widened to `long`, and
 * [elementWidth] (1, 2, 4 or 8) says how many of those bits are significant. The widths that can mean
 * two things at once — 4 is `int` or `float`, 8 is `long` or `double` — are resolved from the array's
 * own element type, exactly like a raw `const`.
 */
data class HirFillArrayData(
    override val address: Int,
    val array: Int,
    val elementWidth: Int,
    val elements: List<Long>,
) : HirInstruction

/** An opcode the decoder recognised but the engine cannot translate yet. */
data class HirUnsupported(
    override val address: Int,
    val opcodeName: String,
    val format: String,
) : HirInstruction

// ---------------------------------------------------------------------------------------------
// Method bodies, classes and programs
// ---------------------------------------------------------------------------------------------

data class HirExceptionHandler(val exceptionType: VortexType?, val handlerAddress: Int) {
    val isCatchAll: Boolean get() = exceptionType == null
}

data class HirTryBlock(
    val startAddress: Int,
    val endAddress: Int,
    val handlers: List<HirExceptionHandler>,
)

data class HirMethodBody(
    /** Total registers declared by the code item (`registers_size`). */
    val registerCount: Int,
    /** Registers consumed by the parameters, receiver included. */
    val parameterRegisterCount: Int,
    val instructions: List<HirInstruction>,
    val tryBlocks: List<HirTryBlock>,
    /** Code address to source line, harvested from DEX debug info. */
    val lineNumbers: Map<Int, Int>,
    /** DEX register to debug name, harvested from `StartLocal` items. */
    val registerNames: Map<Int, String>,
    /** Byte offset of the `code_item` inside the DEX, for diagnostics. */
    val codeItemOffset: Int,
) {
    val parameterRegisterBase: Int get() = registerCount - parameterRegisterCount
}

data class HirParameter(val type: VortexType, val name: String?)

data class HirField(
    val definingClass: ObjectType,
    val name: String,
    val type: VortexType,
    val accessFlags: Int,
    val initialValue: HirEncodedValue?,
    val annotations: List<HirAnnotation>,
)

data class HirMethod(
    val definingClass: ObjectType,
    val name: String,
    val parameters: List<HirParameter>,
    val returnType: VortexType,
    val accessFlags: Int,
    val annotations: List<HirAnnotation>,
    val body: HirMethodBody?,
    /** Index of the DEX file this method was read from, for multi-dex diagnostics. */
    val dexIndex: Int = 0,
) {
    val parameterTypes: List<VortexType> get() = parameters.map { it.type }

    val reference: MethodReference
        get() = MethodReference(definingClass, name, parameterTypes, returnType)

    val hasCode: Boolean get() = body != null

    val isStatic: Boolean get() = AccessFlags.isStatic(accessFlags)

    val isConstructor: Boolean get() = name == "<init>"

    val isStaticInitializer: Boolean get() = name == "<clinit>"
}

data class HirClass(
    val type: ObjectType,
    val accessFlags: Int,
    val superType: ObjectType?,
    val interfaces: List<ObjectType>,
    val sourceFile: String?,
    val annotations: List<HirAnnotation>,
    val fields: List<HirField>,
    val methods: List<HirMethod>,
    val dexIndex: Int = 0,
) {
    val isInterface: Boolean get() = AccessFlags.isInterface(accessFlags)
    val isAbstract: Boolean get() = AccessFlags.isAbstract(accessFlags)
    val isEnum: Boolean get() = AccessFlags.isEnum(accessFlags)
    val isAnnotation: Boolean get() = AccessFlags.isAnnotation(accessFlags)
    val isModuleInfo: Boolean get() = type.internalName == "module-info"
}

/** Where a program came from, kept for reports and reproducible builds. */
data class ProgramSource(
    val archiveName: String,
    val dexEntryName: String,
    val dexVersion: Int,
    val checksum: Long,
    val classCount: Int,
    val dexIndex: Int = 0,
)

/**
 * A set of classes and the DEX files they were read from. Multi-dex inputs produce one
 * [HirProgram] per DEX entry; [riad.codes.vortex.engine.TranslationEngine] merges them.
 */
data class HirProgram(
    val classes: List<HirClass>,
    val sources: List<ProgramSource>,
) {
    val hierarchy: ClassHierarchy by lazy { ClassHierarchy.of(classes) }

    private val classIndex: Map<String, HirClass> by lazy { classes.associateBy { it.type.internalName } }

    private val methodIndex: Map<String, HirMethod> by lazy {
        buildMap {
            classes.forEach { hirClass ->
                hirClass.methods.forEach { method ->
                    put(key(method.reference), method)
                }
            }
        }
    }

    fun findClass(internalName: String): HirClass? = classIndex[internalName]

    fun findMethod(reference: MethodReference): HirMethod? = methodIndex[key(reference)]

    fun methodCount(): Int = classes.sumOf { it.methods.size }

    fun fieldCount(): Int = classes.sumOf { it.fields.size }

    fun withClasses(newClasses: List<HirClass>): HirProgram = copy(classes = newClasses)

    companion object {
        val EMPTY = HirProgram(emptyList(), emptyList())

        private fun key(reference: MethodReference): String =
            "${reference.owner.internalName}#${reference.name}${reference.descriptor}"

        /** Merges several per-DEX programs into one, preserving DEX order. */
        fun merge(programs: List<HirProgram>): HirProgram =
            if (programs.size == 1) programs.first()
            else HirProgram(programs.flatMap { it.classes }, programs.flatMap { it.sources })
    }
}
