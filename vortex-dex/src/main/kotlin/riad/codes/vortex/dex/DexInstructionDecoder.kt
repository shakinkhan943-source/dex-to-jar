package riad.codes.vortex.dex

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.DualReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.formats.ArrayPayload
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference as DexFieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodHandleReference
import com.android.tools.smali.dexlib2.iface.reference.MethodProtoReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference as DexMethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import riad.codes.vortex.ir.BinaryOperation
import riad.codes.vortex.ir.CompareOperation
import riad.codes.vortex.ir.Comparison
import riad.codes.vortex.ir.DexFormatException
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
import riad.codes.vortex.ir.HirFillArrayData
import riad.codes.vortex.ir.HirFilledNewArray
import riad.codes.vortex.ir.HirSwitch
import riad.codes.vortex.ir.HirThrow
import riad.codes.vortex.ir.HirUnaryOp
import riad.codes.vortex.ir.HirUnsupported
import riad.codes.vortex.ir.InvokeKind
import riad.codes.vortex.ir.MethodReference
import riad.codes.vortex.ir.MoveKind
import riad.codes.vortex.ir.NumericKind
import riad.codes.vortex.ir.UnaryOperation
import riad.codes.vortex.ir.VortexTypes

/** Resolves the pseudo-payload instruction (switch tables) that lives at a code address. */
fun interface PayloadResolver {
    fun payloadAt(address: Int): Instruction?

    companion object {
        val NONE = PayloadResolver { null }
    }
}

/**
 * Decodes `dexlib2` instructions into the high-level IR.
 *
 * Two design choices keep this tractable and auditable:
 *
 * 1. **Generic operand interfaces over format classes.** `dexlib2` guarantees that an opcode's
 *    operands implement the interfaces its format implies, so `TwoRegisterInstruction`,
 *    `NarrowLiteralInstruction`, `OffsetInstruction` and friends describe operands without ~40
 *    format imports and without a cast per format.
 * 2. **Mnemonic-driven arithmetic.** The ~110 arithmetic opcodes are classified from their mnemonic
 *    by [DexMnemonics] instead of being listed one by one; the coverage test in
 *    `DexMnemonicsTest` asserts the parser claims every arithmetic opcode `dexlib2` defines.
 *
 * Anything unrecognised becomes [HirUnsupported]. The engine never guesses: an unsupported
 * instruction inside a method makes the whole method a reported fallback stub rather than silently
 * wrong code.
 *
 * All addresses, branch offsets, switch element offsets and payload keys are in **16-bit code
 * units**, matching `dexlib2` and the DEX specification.
 */
class DexInstructionDecoder(private val resolver: DexReferenceResolver = DexReferenceResolver()) {

    fun decode(instruction: Instruction, address: Int, payloads: PayloadResolver = PayloadResolver.NONE): HirInstruction {
        val opcode = instruction.opcode
        DexMnemonics.classifyArithmetic(opcode)?.let { return decodeArithmetic(instruction, address, opcode, it) }
        return when (opcode) {
            Opcode.NOP -> HirNop(address)

            // ------------------------------------------------------------------ moves
            Opcode.MOVE, Opcode.MOVE_FROM16, Opcode.MOVE_16 ->
                decodeMove(instruction, address, opcode, MoveKind.INT)

            Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16 ->
                decodeMove(instruction, address, opcode, MoveKind.OBJECT)

            Opcode.MOVE_WIDE, Opcode.MOVE_WIDE_FROM16, Opcode.MOVE_WIDE_16 ->
                decodeMove(instruction, address, opcode, MoveKind.WIDE)

            Opcode.MOVE_RESULT, Opcode.MOVE_RESULT_WIDE, Opcode.MOVE_RESULT_OBJECT ->
                HirMoveResult(address, registerOf(instruction, opcode))

            Opcode.MOVE_EXCEPTION -> HirMoveException(address, registerOf(instruction, opcode))

            // ------------------------------------------------------------------ returns
            Opcode.RETURN_VOID -> HirReturn(address, null)
            Opcode.RETURN, Opcode.RETURN_WIDE, Opcode.RETURN_OBJECT ->
                HirReturn(address, registerOf(instruction, opcode))

            // ------------------------------------------------------------------ constants
            Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST, Opcode.CONST_HIGH16 ->
                HirConst(address, registerOf(instruction, opcode), intConstant(narrowOrWide(instruction, opcode)))

            // The bits are kept, not interpreted: see Constant.WideBits.
            Opcode.CONST_WIDE_16, Opcode.CONST_WIDE_32, Opcode.CONST_WIDE, Opcode.CONST_WIDE_HIGH16 ->
                HirConst(address, registerOf(instruction, opcode), Constant.WideBits(narrowOrWide(instruction, opcode)))

            Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO ->
                HirConstString(address, registerOf(instruction, opcode), reference<StringReference>(instruction, opcode).string)

            Opcode.CONST_CLASS ->
                HirConstClass(address, registerOf(instruction, opcode), resolver.type(reference<TypeReference>(instruction, opcode).type))

            Opcode.CONST_METHOD_TYPE ->
                HirConst(
                    address,
                    registerOf(instruction, opcode),
                    Constant.MethodTypeValue(resolver.methodType(reference<MethodProtoReference>(instruction, opcode))),
                )

            Opcode.CONST_METHOD_HANDLE ->
                HirConst(address, registerOf(instruction, opcode), methodHandleConstant(instruction, opcode))

            // ------------------------------------------------------------------ monitors, exceptions
            Opcode.MONITOR_ENTER -> HirMonitorEnter(address, registerOf(instruction, opcode))
            Opcode.MONITOR_EXIT -> HirMonitorExit(address, registerOf(instruction, opcode))
            Opcode.THROW -> HirThrow(address, registerOf(instruction, opcode))

            // ------------------------------------------------------------------ control flow
            Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32 -> HirGoto(address, targetOf(instruction, address, opcode))

            Opcode.IF_EQ, Opcode.IF_NE, Opcode.IF_LT, Opcode.IF_GE, Opcode.IF_GT, Opcode.IF_LE ->
                decodeIfTest(instruction, address, opcode)

            Opcode.IF_EQZ, Opcode.IF_NEZ, Opcode.IF_LTZ, Opcode.IF_GEZ, Opcode.IF_GTZ, Opcode.IF_LEZ ->
                decodeIfZero(instruction, address, opcode)

            Opcode.PACKED_SWITCH, Opcode.SPARSE_SWITCH -> decodeSwitch(instruction, address, opcode, payloads)

            Opcode.PACKED_SWITCH_PAYLOAD, Opcode.SPARSE_SWITCH_PAYLOAD, Opcode.ARRAY_PAYLOAD ->
                HirUnsupported(address, DexMnemonics.mnemonic(opcode), "pseudo-instruction (payload)")

            // ------------------------------------------------------------------ compares
            Opcode.CMPL_FLOAT -> decodeCompare(instruction, address, opcode, CompareOperation.FLOAT_LOW)
            Opcode.CMPG_FLOAT -> decodeCompare(instruction, address, opcode, CompareOperation.FLOAT_HIGH)
            Opcode.CMPL_DOUBLE -> decodeCompare(instruction, address, opcode, CompareOperation.DOUBLE_LOW)
            Opcode.CMPG_DOUBLE -> decodeCompare(instruction, address, opcode, CompareOperation.DOUBLE_HIGH)
            Opcode.CMP_LONG -> decodeCompare(instruction, address, opcode, CompareOperation.LONG)

            // ------------------------------------------------------------------ objects and arrays
            Opcode.CHECK_CAST ->
                HirCheckCast(address, registerOf(instruction, opcode), resolver.type(reference<TypeReference>(instruction, opcode).type))

            Opcode.INSTANCE_OF -> {
                val registers = twoRegisters(instruction, opcode)
                HirInstanceOf(
                    address,
                    registers.registerA,
                    registers.registerB,
                    resolver.type(reference<TypeReference>(instruction, opcode).type),
                )
            }

            Opcode.NEW_INSTANCE ->
                HirNewInstance(address, registerOf(instruction, opcode), resolver.objectType(reference<TypeReference>(instruction, opcode).type))

            Opcode.NEW_ARRAY -> {
                val registers = twoRegisters(instruction, opcode)
                val arrayType = resolver.type(reference<TypeReference>(instruction, opcode).type)
                if (arrayType !is riad.codes.vortex.ir.ArrayType) {
                    throw DexFormatException("new-array must reference an array type but found ${arrayType.descriptor}")
                }
                HirNewArray(address, registers.registerA, arrayType.elementType, registers.registerB)
            }

            Opcode.ARRAY_LENGTH -> {
                val registers = twoRegisters(instruction, opcode)
                HirArrayLength(address, registers.registerA, registers.registerB)
            }

            Opcode.AGET -> decodeArrayLoad(instruction, address, opcode, null, wide = false)
            Opcode.AGET_WIDE -> decodeArrayLoad(instruction, address, opcode, null, wide = true)
            Opcode.AGET_OBJECT -> decodeArrayLoad(instruction, address, opcode, null, wide = false)

            Opcode.AGET_BOOLEAN -> decodeArrayLoad(instruction, address, opcode, VortexTypes.BOOLEAN, wide = false)
            Opcode.AGET_BYTE -> decodeArrayLoad(instruction, address, opcode, VortexTypes.BYTE, wide = false)
            Opcode.AGET_CHAR -> decodeArrayLoad(instruction, address, opcode, VortexTypes.CHAR, wide = false)
            Opcode.AGET_SHORT -> decodeArrayLoad(instruction, address, opcode, VortexTypes.SHORT, wide = false)

            Opcode.APUT -> decodeArrayStore(instruction, address, opcode, null, wide = false)
            Opcode.APUT_WIDE -> decodeArrayStore(instruction, address, opcode, null, wide = true)
            Opcode.APUT_OBJECT -> decodeArrayStore(instruction, address, opcode, null, wide = false)

            Opcode.APUT_BOOLEAN -> decodeArrayStore(instruction, address, opcode, VortexTypes.BOOLEAN, wide = false)
            Opcode.APUT_BYTE -> decodeArrayStore(instruction, address, opcode, VortexTypes.BYTE, wide = false)
            Opcode.APUT_CHAR -> decodeArrayStore(instruction, address, opcode, VortexTypes.CHAR, wide = false)
            Opcode.APUT_SHORT -> decodeArrayStore(instruction, address, opcode, VortexTypes.SHORT, wide = false)

            // ------------------------------------------------------------------ fields
            Opcode.IGET, Opcode.IGET_WIDE, Opcode.IGET_OBJECT, Opcode.IGET_BOOLEAN,
            Opcode.IGET_BYTE, Opcode.IGET_CHAR, Opcode.IGET_SHORT,
            -> {
                val registers = twoRegisters(instruction, opcode)
                HirInstanceFieldGet(
                    address,
                    registers.registerA,
                    resolver.field(reference<DexFieldReference>(instruction, opcode)),
                    registers.registerB,
                )
            }

            Opcode.IPUT, Opcode.IPUT_WIDE, Opcode.IPUT_OBJECT, Opcode.IPUT_BOOLEAN,
            Opcode.IPUT_BYTE, Opcode.IPUT_CHAR, Opcode.IPUT_SHORT,
            -> {
                val registers = twoRegisters(instruction, opcode)
                HirInstanceFieldPut(
                    address,
                    resolver.field(reference<DexFieldReference>(instruction, opcode)),
                    registers.registerB,
                    registers.registerA,
                )
            }

            Opcode.SGET, Opcode.SGET_WIDE, Opcode.SGET_OBJECT, Opcode.SGET_BOOLEAN,
            Opcode.SGET_BYTE, Opcode.SGET_CHAR, Opcode.SGET_SHORT,
            -> HirStaticFieldGet(
                address,
                registerOf(instruction, opcode),
                resolver.field(reference<DexFieldReference>(instruction, opcode)),
            )

            Opcode.SPUT, Opcode.SPUT_WIDE, Opcode.SPUT_OBJECT, Opcode.SPUT_BOOLEAN,
            Opcode.SPUT_BYTE, Opcode.SPUT_CHAR, Opcode.SPUT_SHORT,
            -> HirStaticFieldPut(
                address,
                resolver.field(reference<DexFieldReference>(instruction, opcode)),
                registerOf(instruction, opcode),
            )

            // ------------------------------------------------------------------ invokes
            Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE -> decodeInvoke(instruction, address, opcode, InvokeKind.VIRTUAL)
            Opcode.INVOKE_SUPER, Opcode.INVOKE_SUPER_RANGE -> decodeInvoke(instruction, address, opcode, InvokeKind.SUPER)
            Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE -> decodeInvoke(instruction, address, opcode, InvokeKind.DIRECT)
            Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE -> decodeInvoke(instruction, address, opcode, InvokeKind.STATIC)
            Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE -> decodeInvoke(instruction, address, opcode, InvokeKind.INTERFACE)

            Opcode.INVOKE_POLYMORPHIC, Opcode.INVOKE_POLYMORPHIC_RANGE ->
                decodeInvokePolymorphic(instruction, address, opcode)

            // ------------------------------------------------------------------ unary and conversions
            Opcode.NEG_INT -> decodeUnary(instruction, address, opcode, UnaryOperation.NEGATE, NumericKind.INT)
            Opcode.NEG_LONG -> decodeUnary(instruction, address, opcode, UnaryOperation.NEGATE, NumericKind.LONG)
            Opcode.NEG_FLOAT -> decodeUnary(instruction, address, opcode, UnaryOperation.NEGATE, NumericKind.FLOAT)
            Opcode.NEG_DOUBLE -> decodeUnary(instruction, address, opcode, UnaryOperation.NEGATE, NumericKind.DOUBLE)
            Opcode.NOT_INT -> decodeUnary(instruction, address, opcode, UnaryOperation.NOT, NumericKind.INT)
            Opcode.NOT_LONG -> decodeUnary(instruction, address, opcode, UnaryOperation.NOT, NumericKind.LONG)

            Opcode.INT_TO_LONG -> decodeConversion(instruction, address, opcode, VortexTypes.INT, VortexTypes.LONG)
            Opcode.INT_TO_FLOAT -> decodeConversion(instruction, address, opcode, VortexTypes.INT, VortexTypes.FLOAT)
            Opcode.INT_TO_DOUBLE -> decodeConversion(instruction, address, opcode, VortexTypes.INT, VortexTypes.DOUBLE)
            Opcode.LONG_TO_INT -> decodeConversion(instruction, address, opcode, VortexTypes.LONG, VortexTypes.INT)
            Opcode.LONG_TO_FLOAT -> decodeConversion(instruction, address, opcode, VortexTypes.LONG, VortexTypes.FLOAT)
            Opcode.LONG_TO_DOUBLE -> decodeConversion(instruction, address, opcode, VortexTypes.LONG, VortexTypes.DOUBLE)
            Opcode.FLOAT_TO_INT -> decodeConversion(instruction, address, opcode, VortexTypes.FLOAT, VortexTypes.INT)
            Opcode.FLOAT_TO_LONG -> decodeConversion(instruction, address, opcode, VortexTypes.FLOAT, VortexTypes.LONG)
            Opcode.FLOAT_TO_DOUBLE -> decodeConversion(instruction, address, opcode, VortexTypes.FLOAT, VortexTypes.DOUBLE)
            Opcode.DOUBLE_TO_INT -> decodeConversion(instruction, address, opcode, VortexTypes.DOUBLE, VortexTypes.INT)
            Opcode.DOUBLE_TO_LONG -> decodeConversion(instruction, address, opcode, VortexTypes.DOUBLE, VortexTypes.LONG)
            Opcode.DOUBLE_TO_FLOAT -> decodeConversion(instruction, address, opcode, VortexTypes.DOUBLE, VortexTypes.FLOAT)
            Opcode.INT_TO_BYTE -> decodeConversion(instruction, address, opcode, VortexTypes.INT, VortexTypes.BYTE)
            Opcode.INT_TO_CHAR -> decodeConversion(instruction, address, opcode, VortexTypes.INT, VortexTypes.CHAR)
            Opcode.INT_TO_SHORT -> decodeConversion(instruction, address, opcode, VortexTypes.INT, VortexTypes.SHORT)

            // ------------------------------------------------------------------ deliberately out of scope
            Opcode.FILL_ARRAY_DATA -> decodeFillArrayData(instruction, address, opcode, payloads)

            Opcode.FILLED_NEW_ARRAY, Opcode.FILLED_NEW_ARRAY_RANGE ->
                decodeFilledNewArray(instruction, address, opcode)

            Opcode.INVOKE_CUSTOM, Opcode.INVOKE_CUSTOM_RANGE ->
                HirUnsupported(address, DexMnemonics.mnemonic(opcode), "call sites need bootstrap method rewriting")

            Opcode.THROW_VERIFICATION_ERROR ->
                HirUnsupported(address, DexMnemonics.mnemonic(opcode), "verification-error opcode is not portable")

            else -> HirUnsupported(address, DexMnemonics.mnemonic(opcode), "no mapping for this opcode")
        }
    }

    // -------------------------------------------------------------------------------------------
    // families
    // -------------------------------------------------------------------------------------------

    private fun decodeMove(instruction: Instruction, address: Int, opcode: Opcode, kind: MoveKind): HirInstruction {
        val registers = twoRegisters(instruction, opcode)
        return HirMove(address, registers.registerA, registers.registerB, kind)
    }

    private fun decodeArithmetic(
        instruction: Instruction,
        address: Int,
        opcode: Opcode,
        arithmetic: DexMnemonics.Arithmetic,
    ): HirInstruction {
        return when (arithmetic.form) {
            DexMnemonics.ArithmeticForm.REGISTERS_23X -> {
                val registers = threeRegisters(instruction, opcode)
                val operands = orderedOperands(registers.registerB, registers.registerC, arithmetic.reversed)
                HirBinaryOp(address, registers.registerA, arithmetic.operation, arithmetic.kind, operands.first, operands.second)
            }

            DexMnemonics.ArithmeticForm.TWO_ADDRESS_12X -> {
                val registers = twoRegisters(instruction, opcode)
                // `add-int/2addr vA, vB` is `vA = vA + vB`.
                HirBinaryOp(
                    address,
                    registers.registerA,
                    arithmetic.operation,
                    arithmetic.kind,
                    HirOperand.Register(registers.registerA),
                    HirOperand.Register(registers.registerB),
                )
            }

            DexMnemonics.ArithmeticForm.LITERAL_16_22S, DexMnemonics.ArithmeticForm.LITERAL_8_22B -> {
                val registers = twoRegisters(instruction, opcode)
                val literal = HirOperand.Literal(narrowOrWide(instruction, opcode))
                val operands = if (arithmetic.reversed) {
                    literal to HirOperand.Register(registers.registerB)
                } else {
                    HirOperand.Register(registers.registerB) to literal
                }
                HirBinaryOp(address, registers.registerA, arithmetic.operation, arithmetic.kind, operands.first, operands.second)
            }
        }
    }

    private fun orderedOperands(left: Int, right: Int, reversed: Boolean): Pair<HirOperand, HirOperand> =
        if (reversed) {
            HirOperand.Register(right) to HirOperand.Register(left)
        } else {
            HirOperand.Register(left) to HirOperand.Register(right)
        }

    private fun decodeUnary(
        instruction: Instruction,
        address: Int,
        opcode: Opcode,
        operation: UnaryOperation,
        kind: NumericKind,
    ): HirInstruction {
        val registers = twoRegisters(instruction, opcode)
        return HirUnaryOp(address, registers.registerA, operation, kind, registers.registerB)
    }

    private fun decodeConversion(
        instruction: Instruction,
        address: Int,
        opcode: Opcode,
        from: riad.codes.vortex.ir.VortexType,
        to: riad.codes.vortex.ir.VortexType,
    ): HirInstruction {
        val registers = twoRegisters(instruction, opcode)
        return HirConversion(address, registers.registerA, registers.registerB, from, to)
    }

    private fun decodeCompare(
        instruction: Instruction,
        address: Int,
        opcode: Opcode,
        operation: CompareOperation,
    ): HirInstruction {
        val registers = threeRegisters(instruction, opcode)
        return HirCompare(address, registers.registerA, operation, registers.registerB, registers.registerC)
    }

    private fun decodeIfTest(instruction: Instruction, address: Int, opcode: Opcode): HirInstruction {
        val registers = twoRegisters(instruction, opcode)
        val comparison = comparisonOf(opcode)
        return HirIfTest(
            address,
            comparison,
            registers.registerA,
            HirOperand.Register(registers.registerB),
            targetOf(instruction, address, opcode),
        )
    }

    private fun decodeIfZero(instruction: Instruction, address: Int, opcode: Opcode): HirInstruction = HirIfTest(
        address,
        comparisonOf(opcode),
        registerOf(instruction, opcode),
        HirOperand.Literal(0L),
        targetOf(instruction, address, opcode),
    )

    private fun decodeSwitch(
        instruction: Instruction,
        address: Int,
        opcode: Opcode,
        payloads: PayloadResolver,
    ): HirInstruction {
        val offset = (instruction as? OffsetInstruction)?.codeOffset
            ?: throw formatError(opcode, "OffsetInstruction", instruction)
        val payload = payloads.payloadAt(address + offset)
            ?: throw DexFormatException("${DexMnemonics.mnemonic(opcode)} at $address has no payload at ${address + offset}")
        val switchPayload = payload as? SwitchPayload
            ?: throw DexFormatException("payload at ${address + offset} is not a switch payload")
        val elements = switchPayload.switchElements
        return HirSwitch(
            address = address,
            value = registerOf(instruction, opcode),
            keys = elements.map { it.key },
            // DEX switch element offsets are relative to the address of the switch instruction.
            targets = elements.map { address + it.offset },
            // The offset in the switch instruction points at the *payload table*, not at the default
            // arm: an unmatched value falls through to the instruction after the switch.
            defaultTarget = address + instruction.codeUnits,
        )
    }

    /**
     * `filled-new-array vC, vD, .., <type>` and its range form.
     *
     * Unlike an invoke, every register is an element of the array and there is no receiver, so the
     * register words map straight onto the elements.
     */
    private fun decodeFilledNewArray(instruction: Instruction, address: Int, opcode: Opcode): HirInstruction {
        val arrayType = resolver.type(reference<TypeReference>(instruction, opcode).type)
        if (arrayType !is riad.codes.vortex.ir.ArrayType) {
            throw DexFormatException("filled-new-array must reference an array type but found ${arrayType.descriptor}")
        }
        return HirFilledNewArray(
            address = address,
            componentType = arrayType.elementType,
            values = invokeRegisterWords(instruction, opcode),
            isRange = isRange(opcode),
        )
    }

    /**
     * `fill-array-data vAA, +BBBBBBBB`: the instruction names the array, the payload holds the
     * elements.
     */
    private fun decodeFillArrayData(
        instruction: Instruction,
        address: Int,
        opcode: Opcode,
        payloads: PayloadResolver,
    ): HirInstruction {
        val offset = (instruction as? OffsetInstruction)?.codeOffset
            ?: throw formatError(opcode, "OffsetInstruction", instruction)
        val payload = payloads.payloadAt(address + offset) as? ArrayPayload
            ?: throw DexFormatException(
                "${DexMnemonics.mnemonic(opcode)} at $address has no array-data payload at ${address + offset}",
            )
        return HirFillArrayData(
            address = address,
            array = registerOf(instruction, opcode),
            elementWidth = payload.elementWidth,
            elements = payload.arrayElements.map { it.toLong() },
        )
    }

    private fun decodeArrayLoad(
        instruction: Instruction,
        address: Int,
        opcode: Opcode,
        componentType: riad.codes.vortex.ir.VortexType?,
        wide: Boolean,
    ): HirInstruction {
        val registers = threeRegisters(instruction, opcode)
        return HirArrayLoad(address, registers.registerA, componentType, registers.registerB, registers.registerC, wide)
    }

    private fun decodeArrayStore(
        instruction: Instruction,
        address: Int,
        opcode: Opcode,
        componentType: riad.codes.vortex.ir.VortexType?,
        wide: Boolean,
    ): HirInstruction {
        val registers = threeRegisters(instruction, opcode)
        return HirArrayStore(address, componentType, registers.registerB, registers.registerC, registers.registerA, wide)
    }

    private fun decodeInvoke(instruction: Instruction, address: Int, opcode: Opcode, kind: InvokeKind): HirInstruction {
        val reference = resolver.method(reference<DexMethodReference>(instruction, opcode))
        val arguments = invokeArguments(instruction, opcode, reference, isStatic = kind == InvokeKind.STATIC)
        return HirInvoke(address, kind, reference, arguments, isRange(opcode))
    }

    private fun decodeInvokePolymorphic(instruction: Instruction, address: Int, opcode: Opcode): HirInstruction {
        val dual = instruction as? DualReferenceInstruction
            ?: throw formatError(opcode, "DualReferenceInstruction", instruction)
        val handle = dual.reference as? DexMethodReference
            ?: throw DexFormatException("invoke-polymorphic must reference a method handle signature")
        val proto = dual.reference2 as? MethodProtoReference
            ?: throw DexFormatException("invoke-polymorphic must carry a prototype")
        val reference = resolver.method(handle, proto)
        // invoke-polymorphic lowers to a virtual call on java.lang.invoke.MethodHandle: the receiver
        // is the handle itself, so the receiver word is always present.
        val arguments = invokeArguments(instruction, opcode, reference, isStatic = false)
        return HirInvoke(address, InvokeKind.VIRTUAL, reference, arguments, isRange(opcode))
    }

    /**
     * Expands an invoke's register words into one base register per declared argument.
     *
     * `registerCount` counts 32-bit words, so a `long` argument occupies two registers while its
     * value lives in the lower one; the receiver of an instance call is the first word and is
     * reported as `arguments[0]`.
     */
    private fun invokeArguments(
        instruction: Instruction,
        opcode: Opcode,
        reference: MethodReference,
        isStatic: Boolean,
    ): List<Int> {
        val words = invokeRegisterWords(instruction, opcode)
        val arguments = ArrayList<Int>(reference.parameters.size + 1)
        var cursor = 0
        val receiverSlots = if (isStatic) 0 else 1
        if (receiverSlots == 1) {
            if (words.isEmpty()) throw DexFormatException("instance invoke at ${reference.name} has no receiver register")
            arguments += words[cursor]
            cursor += 1
        }
        for (parameter in reference.parameters) {
            if (cursor >= words.size) {
                throw DexFormatException("invoke of ${reference.name}${reference.descriptor} lists fewer registers than its parameters")
            }
            arguments += words[cursor]
            cursor += parameter.width
        }
        return arguments
    }

    private fun invokeRegisterWords(instruction: Instruction, opcode: Opcode): List<Int> {
        (instruction as? FiveRegisterInstruction)?.let { five ->
            val slots = intArrayOf(five.registerC, five.registerD, five.registerE, five.registerF, five.registerG)
            return slots.take(five.registerCount)
        }
        (instruction as? RegisterRangeInstruction)?.let { range ->
            return (range.startRegister until range.startRegister + range.registerCount).toList()
        }
        throw formatError(opcode, "FiveRegisterInstruction or RegisterRangeInstruction", instruction)
    }

    private fun isRange(opcode: Opcode): Boolean = DexMnemonics.mnemonic(opcode).endsWith("-range")

    private fun methodHandleConstant(instruction: Instruction, opcode: Opcode): Constant {
        val handle = reference<MethodHandleReference>(instruction, opcode)
        val member = handle.memberReference
        return when (member) {
            is DexMethodReference -> Constant.MethodHandleValue(resolver.method(member), handle.methodHandleType, false)
            is DexFieldReference -> {
                val field = resolver.field(member)
                Constant.MethodHandleValue(
                    MethodReference(field.owner, field.name, emptyList(), field.type),
                    handle.methodHandleType,
                    false,
                )
            }

            else -> throw DexFormatException("unsupported method handle member: ${member.javaClass.name}")
        }
    }

    private fun comparisonOf(opcode: Opcode): Comparison {
        val mnemonic = DexMnemonics.mnemonic(opcode)
        val suffix = mnemonic.removePrefix("if-").removeSuffix("z")
        return Comparison.fromDexSuffix(suffix)
            ?: throw DexFormatException("'$mnemonic' is not a conditional branch mnemonic")
    }

    private fun targetOf(instruction: Instruction, address: Int, opcode: Opcode): Int {
        val offset = (instruction as? OffsetInstruction)?.codeOffset ?: throw formatError(opcode, "OffsetInstruction", instruction)
        return address + offset
    }

    private fun intConstant(value: Long): Constant = when (value) {
        // Zero stays its own case: `const/4 v0, #0` is an `int` *and* a `null`, which is a wider
        // ambiguity than `int` versus `float`.
        0L -> Constant.Zero
        else -> Constant.NarrowBits(value.toInt())
    }

    private fun narrowOrWide(instruction: Instruction, opcode: Opcode): Long {
        (instruction as? WideLiteralInstruction)?.let { return it.wideLiteral }
        (instruction as? NarrowLiteralInstruction)?.let { return it.narrowLiteral.toLong() }
        throw formatError(opcode, "NarrowLiteralInstruction or WideLiteralInstruction", instruction)
    }

    private fun registerOf(instruction: Instruction, opcode: Opcode): Int =
        (instruction as? OneRegisterInstruction)?.registerA ?: throw formatError(opcode, "OneRegisterInstruction", instruction)

    private fun twoRegisters(instruction: Instruction, opcode: Opcode): TwoRegisterInstruction =
        instruction as? TwoRegisterInstruction ?: throw formatError(opcode, "TwoRegisterInstruction", instruction)

    private fun threeRegisters(instruction: Instruction, opcode: Opcode): ThreeRegisterInstruction =
        instruction as? ThreeRegisterInstruction ?: throw formatError(opcode, "ThreeRegisterInstruction", instruction)

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> reference(instruction: Instruction, opcode: Opcode): T {
        val referenceInstruction = instruction as? ReferenceInstruction
            ?: throw formatError(opcode, "ReferenceInstruction", instruction)
        val reference = referenceInstruction.reference
        return reference as? T
            ?: throw DexFormatException(
                "${DexMnemonics.mnemonic(opcode)} expected a ${T::class.java.simpleName} but referenced ${reference.javaClass.name}",
            )
    }

    private fun formatError(opcode: Opcode, expected: String, instruction: Instruction): DexFormatException =
        DexFormatException(
            "opcode ${DexMnemonics.mnemonic(opcode)} should implement $expected but is ${instruction.javaClass.simpleName}",
        )
}
