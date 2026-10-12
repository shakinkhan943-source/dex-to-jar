package riad.codes.vortex.analysis

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
import riad.codes.vortex.ir.HirMonitorEnter
import riad.codes.vortex.ir.HirMonitorExit
import riad.codes.vortex.ir.HirMove
import riad.codes.vortex.ir.HirMoveException
import riad.codes.vortex.ir.HirMoveResult
import riad.codes.vortex.ir.HirNewArray
import riad.codes.vortex.ir.HirNewInstance
import riad.codes.vortex.ir.HirNop
import riad.codes.vortex.ir.HirOperand
import riad.codes.vortex.ir.HirReturn
import riad.codes.vortex.ir.HirStaticFieldGet
import riad.codes.vortex.ir.HirStaticFieldPut
import riad.codes.vortex.ir.HirSwitch
import riad.codes.vortex.ir.HirThrow
import riad.codes.vortex.ir.HirUnaryOp
import riad.codes.vortex.ir.HirUnsupported
import riad.codes.vortex.ir.InvokeKind
import riad.codes.vortex.ir.MethodReference
import riad.codes.vortex.ir.MoveKind
import riad.codes.vortex.ir.ObjectType
import riad.codes.vortex.ir.VortexType
import riad.codes.vortex.ir.VortexTypes

/**
 * Which DEX registers an instruction reads and writes.
 *
 * Both the inference pass and the register-versioning pass drive themselves from this one model, so
 * a register can never be read without a version having been defined for it.
 */
object RegisterAccess {

    /** Registers consumed by [instruction], in no particular order and without duplicates. */
    fun reads(instruction: HirInstruction, moveResultTargets: Map<Int, Int> = emptyMap()): List<Int> = when (instruction) {
        is HirNop, is HirGoto, is HirUnsupported, is HirConst, is HirConstString, is HirConstClass,
        is HirNewInstance, is HirStaticFieldGet, is HirMoveResult,
        -> emptyList()

        is HirMove -> listOf(instruction.source)
        is HirMoveException -> emptyList()
        is HirUnaryOp -> listOf(instruction.operand)
        is HirConversion -> listOf(instruction.source)
        is HirCompare -> listOf(instruction.left, instruction.right)
        is HirBinaryOp -> operands(instruction.left, instruction.right)
        // `if-*` has a register on the left and a register-or-literal on the right.
        is HirIfTest -> buildList(2) {
            add(instruction.left)
            instruction.right.registerOrNull?.let { add(it) }
        }
        is HirSwitch -> listOf(instruction.value)
        is HirInvoke -> instruction.arguments
        is HirFilledNewArray -> instruction.values
        is HirNewArray -> listOf(instruction.length)
        is HirArrayLoad -> listOf(instruction.array, instruction.index)
        is HirArrayStore -> listOf(instruction.array, instruction.index, instruction.value)
        is HirArrayLength -> listOf(instruction.array)
        is HirInstanceFieldGet -> listOf(instruction.instance)
        is HirInstanceFieldPut -> listOf(instruction.instance, instruction.value)
        is HirStaticFieldPut -> listOf(instruction.value)
        is HirCheckCast -> listOf(instruction.register)
        is HirInstanceOf -> listOf(instruction.source)
        is HirMonitorEnter -> listOf(instruction.register)
        is HirMonitorExit -> listOf(instruction.register)
        is HirThrow -> listOf(instruction.register)
        is HirFillArrayData -> listOf(instruction.array)
        is HirReturn -> instruction.register?.let { listOf(it) } ?: emptyList()
    }

    /**
     * Registers defined by [instruction].
     *
     * An invoke that is followed by `move-result` defines that destination register: the result only
     * exists for one instruction, and attaching it to the invoke keeps the value's definition and its
     * use in the same basic block, which is what the JVM requires.
     */
    fun writes(instruction: HirInstruction, moveResultTargets: Map<Int, Int> = emptyMap()): List<Int> = when (instruction) {
        is HirNop, is HirGoto, is HirIfTest, is HirSwitch, is HirThrow, is HirUnsupported,
        is HirMonitorEnter, is HirMonitorExit, is HirInstanceFieldPut, is HirStaticFieldPut,
        is HirArrayStore, is HirFillArrayData, is HirReturn, is HirMoveResult,
        -> emptyList()

        is HirMove -> listOf(instruction.dest)
        is HirMoveException -> listOf(instruction.dest)
        is HirConst -> listOf(instruction.dest)
        is HirConstString -> listOf(instruction.dest)
        is HirConstClass -> listOf(instruction.dest)
        is HirBinaryOp -> listOf(instruction.dest)
        is HirUnaryOp -> listOf(instruction.dest)
        is HirConversion -> listOf(instruction.dest)
        is HirCompare -> listOf(instruction.dest)
        is HirInvoke -> moveResultTargets[instruction.address]?.let { listOf(it) } ?: emptyList()
        is HirFilledNewArray -> moveResultTargets[instruction.address]?.let { listOf(it) } ?: emptyList()
        is HirNewInstance -> listOf(instruction.dest)
        is HirNewArray -> listOf(instruction.dest)
        is HirArrayLoad -> listOf(instruction.dest)
        is HirArrayLength -> listOf(instruction.dest)
        is HirInstanceFieldGet -> listOf(instruction.dest)
        is HirStaticFieldGet -> listOf(instruction.dest)
        is HirCheckCast -> listOf(instruction.register)
        is HirInstanceOf -> listOf(instruction.dest)
    }

    /** Concatenates the register operands of a binary/conditional instruction. */
    private fun operands(left: HirOperand, right: HirOperand): List<Int> = buildList(2) {
        left.registerOrNull?.let { add(it) }
        right.registerOrNull?.let { add(it) }
    }

    /**
     * The concrete type an instruction demands from a register it reads, when that demand is
     * unambiguous from the instruction alone.
     *
     * This is what resolves `const/4 v0, #0`: the zero constant can be an `int` or `null`, and the
     * first use of the value decides. Uses whose expected type depends on other values (a comparison
     * against a register) return `null` and let the default apply.
     */
    fun expectedType(instruction: HirInstruction, register: Int, method: MethodTypeContext): VortexType? =
        when (instruction) {
            is HirMove -> when (instruction.kind) {
                MoveKind.OBJECT -> instruction.let { VortexTypes.OBJECT }
                MoveKind.WIDE -> VortexTypes.LONG
                MoveKind.INT -> VortexTypes.INT
            }

            // Every register of a `filled-new-array` is an element of the array being built, so the
            // array type states exactly what the register must hold.
            is HirFilledNewArray -> instruction.componentType

            is HirBinaryOp -> primitiveFor(instruction.kind)
            is HirUnaryOp -> primitiveFor(instruction.kind)
            is HirConversion -> if (register == instruction.source) instruction.from else instruction.to
            is HirCompare -> when (instruction.operation) {
                riad.codes.vortex.ir.CompareOperation.LONG -> VortexTypes.LONG
                riad.codes.vortex.ir.CompareOperation.FLOAT_LOW, riad.codes.vortex.ir.CompareOperation.FLOAT_HIGH -> VortexTypes.FLOAT
                riad.codes.vortex.ir.CompareOperation.DOUBLE_LOW, riad.codes.vortex.ir.CompareOperation.DOUBLE_HIGH -> VortexTypes.DOUBLE
            }

            is HirReturn -> method.returnType
            is HirInvoke -> invokeExpectedType(instruction, register)
            is HirInstanceFieldGet -> if (register == instruction.instance) instruction.reference.owner else null
            is HirInstanceFieldPut -> when (register) {
                instruction.instance -> instruction.reference.owner
                instruction.value -> instruction.reference.type
                else -> null
            }

            is HirStaticFieldPut -> instruction.reference.type
            is HirArrayLoad -> if (register == instruction.array) instruction.componentType ?: VortexTypes.OBJECT else VortexTypes.INT
            is HirArrayStore -> when (register) {
                instruction.array -> VortexTypes.OBJECT
                instruction.index -> VortexTypes.INT
                else -> instruction.componentType
            }

            is HirArrayLength -> VortexTypes.OBJECT
            is HirCheckCast -> instruction.type
            is HirInstanceOf -> instruction.type
            is HirMonitorEnter, is HirMonitorExit -> VortexTypes.OBJECT
            is HirThrow -> VortexTypes.THROWABLE
            is HirNewArray -> VortexTypes.INT
            is HirSwitch -> VortexTypes.INT
            else -> null
        }

    private fun invokeExpectedType(invoke: HirInvoke, register: Int): VortexType? {
        val argumentIndex = invoke.arguments.indexOf(register)
        if (argumentIndex < 0) return null
        val parameterOffset = argumentIndex - invoke.receiverCount
        return if (argumentIndex < invoke.receiverCount) {
            invoke.reference.owner
        } else {
            invoke.reference.parameters.getOrNull(parameterOffset)
        }
    }

    private fun primitiveFor(kind: riad.codes.vortex.ir.NumericKind): VortexType = when (kind) {
        riad.codes.vortex.ir.NumericKind.INT -> VortexTypes.INT
        riad.codes.vortex.ir.NumericKind.LONG -> VortexTypes.LONG
        riad.codes.vortex.ir.NumericKind.FLOAT -> VortexTypes.FLOAT
        riad.codes.vortex.ir.NumericKind.DOUBLE -> VortexTypes.DOUBLE
    }
}

/** The parts of a method's signature that register typing needs. */
interface MethodTypeContext {
    val returnType: VortexType
    val parameterTypes: List<VortexType>
    val isStatic: Boolean
    val definingClass: ObjectType
}

/** Number of leading arguments of an invoke that are the receiver. */
val HirInvoke.receiverCount: Int
    get() = if (kind == InvokeKind.STATIC) 0 else 1

/** Convenience: the receiver register of an invoke, if it has one. */
val HirInvoke.receiver: Int?
    get() = if (receiverCount == 1) arguments.firstOrNull() else null

/** Convenience: the declared signature of an invoke, used for diagnostics and slot widths. */
val HirInvoke.methodReference: MethodReference get() = reference
