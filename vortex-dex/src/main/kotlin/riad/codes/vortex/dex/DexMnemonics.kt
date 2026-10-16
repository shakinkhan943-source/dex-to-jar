package riad.codes.vortex.dex

import com.android.tools.smali.dexlib2.Opcode
import riad.codes.vortex.ir.BinaryOperation
import riad.codes.vortex.ir.NumericKind
import java.util.Locale

/**
 * Dalvik mnemonic handling.
 *
 * `dexlib2` exposes every opcode as an enum constant whose mnemonic carries the operation family,
 * the numeric kind and the operand form: `add-int`, `add-int/2addr`, `add-int/lit8`, `shl-long`,
 * `rsub-int/lit16`. Rather than hand-writing ~110 arithmetic branches, the mnemonic is normalised
 * once (`ADD_INT_2ADDR`, `add-int/2addr` and `add-int-2addr` all become `add-int-2addr`) and parsed
 * into an [Arithmetic] descriptor. [DexMnemonicCoverage] proves the parse table is exhaustive for
 * every arithmetic opcode `dexlib2` knows about.
 */
object DexMnemonics {

    /** Operand form of an arithmetic instruction. */
    enum class ArithmeticForm {
        /** Format 23x: `dest = left op right`. */
        REGISTERS_23X,

        /** Format 12x: `dest op= source`, i.e. `dest = dest op source`. */
        TWO_ADDRESS_12X,

        /** Format 22s: `dest = left op #literal`. */
        LITERAL_16_22S,

        /** Format 22b: `dest = left op #literal`. */
        LITERAL_8_22B,
        ;
    }

    /**
     * A parsed arithmetic instruction.
     *
     * [reversed] marks the `rsub-*` family, whose operands read right-to-left
     * (`rsub-int/lit8 vA, vB, #lit` means `vA = lit - vB`).
     */
    data class Arithmetic(
        val operation: BinaryOperation,
        val kind: NumericKind,
        val form: ArithmeticForm,
        val reversed: Boolean = false,
    )

    /** Operation mnemonics, keyed by the first dash-separated token. */
    private val BINARY: Map<String, BinaryOperation> = mapOf(
        "add" to BinaryOperation.ADD,
        "sub" to BinaryOperation.SUBTRACT,
        "mul" to BinaryOperation.MULTIPLY,
        "div" to BinaryOperation.DIVIDE,
        "rem" to BinaryOperation.REMAINDER,
        "and" to BinaryOperation.AND,
        "or" to BinaryOperation.OR,
        "xor" to BinaryOperation.XOR,
        "shl" to BinaryOperation.SHIFT_LEFT,
        "shr" to BinaryOperation.SHIFT_RIGHT,
        "ushr" to BinaryOperation.UNSIGNED_SHIFT_RIGHT,
    )

    private val KINDS: Map<String, NumericKind> = mapOf(
        "int" to NumericKind.INT,
        "long" to NumericKind.LONG,
        "float" to NumericKind.FLOAT,
        "double" to NumericKind.DOUBLE,
    )

    /** Normalised Dalvik mnemonic, e.g. `add-int/2addr` for `Opcode.ADD_INT_2ADDR`. */
    fun mnemonic(opcode: Opcode): String =
        opcode.name.lowercase(Locale.ROOT).replace('_', '-').replace('/', '-')

    /** Parses an arithmetic opcode, or returns `null` when it is not arithmetic. */
    fun classifyArithmetic(opcode: Opcode): Arithmetic? {
        val parts = mnemonic(opcode).split('-')
        val head = parts.getOrNull(0) ?: return null
        val reversed = head == "rsub"
        val operation = if (reversed) BinaryOperation.SUBTRACT else BINARY[head] ?: return null
        val kind = KINDS[parts.getOrNull(1)] ?: return null
        val form = when (parts.size) {
            // `rsub-int`/`rsub-long` are the suffixless members of the family, but they are not
            // register-register: the opcode's only formats are `22s` (`vA = #lit - vB`).
            2 -> if (reversed) ArithmeticForm.LITERAL_16_22S else ArithmeticForm.REGISTERS_23X
            3 -> when (parts[2]) {
                "2addr" -> ArithmeticForm.TWO_ADDRESS_12X
                "lit16" -> ArithmeticForm.LITERAL_16_22S
                "lit8" -> ArithmeticForm.LITERAL_8_22B
                else -> return null
            }

            else -> return null
        }
        return Arithmetic(operation, kind, form, reversed)
    }

    /** True when the mnemonic names a binary arithmetic instruction. */
    fun isArithmetic(opcode: Opcode): Boolean = classifyArithmetic(opcode) != null
}

/**
 * Coverage bookkeeping for the mnemonic parser.
 *
 * The engine's dispatch is exhaustive by construction (anything unrecognised becomes a reported
 * [riad.codes.vortex.ir.HirUnsupported]), but the arithmetic parser must not silently claim an
 * opcode it cannot describe. [arithmeticOpcodes] is used by tests to assert that every opcode whose
 * mnemonic looks arithmetic is classified.
 */
object DexMnemonicCoverage {

    /** Every opcode `dexlib2` defines whose mnemonic starts with a binary operation token. */
    fun arithmeticLookingOpcodes(): List<Opcode> {
        val heads = setOf("add", "sub", "rsub", "mul", "div", "rem", "and", "or", "xor", "shl", "shr", "ushr")
        return Opcode.values().filter { DexMnemonics.mnemonic(it).substringBefore('-') in heads }
    }
}
