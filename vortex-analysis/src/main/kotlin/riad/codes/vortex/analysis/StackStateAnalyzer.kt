package riad.codes.vortex.analysis

import riad.codes.vortex.ir.BasicBlock
import riad.codes.vortex.ir.LirInstruction
import riad.codes.vortex.ir.MethodReference
import riad.codes.vortex.ir.RegisterRef
import riad.codes.vortex.ir.VortexType

/** What one LIR instruction does to the JVM operand stack. */
data class StackEffect(
    /** Slots pushed and popped by the instruction, in total. */
    val netDepth: Int,
    /** Highest depth reached *during* the instruction, above its entry depth. */
    val peakDepth: Int,
)

/** The result of simulating the operand stack over a method. */
data class StackReport(
    val maxDepth: Int,
    val instructions: Int,
    val violations: List<String>,
) {
    val isBalanced: Boolean get() = violations.isEmpty()

    /** A one line summary for the translation report. */
    fun describe(): String = if (isBalanced) {
        "stack balanced, peak depth $maxDepth over $instructions instructions"
    } else {
        "stack imbalanced: ${violations.size} violation(s), first: ${violations.first()}"
    }
}

/**
 * Abstract interpretation of the JVM operand stack over the low-level IR.
 *
 * The lowering promises something strong: every LIR instruction reads its operands from local slots
 * and writes its result to a local slot, so the operand stack is empty before and after *every*
 * instruction. The single exception is an exception handler, whose entry has the pending throwable
 * pushed by the JVM and immediately consumed by `astore`.
 *
 * That invariant is what makes register-based Dalvik code trivially verifiable once it becomes stack
 * based, so it is worth checking rather than assuming: this pass walks the LIR, simulates the depth
 * each instruction reaches, and reports any point where the stack would not be empty at an
 * instruction boundary. [maxDepth] is then the stack size the emitter must reserve.
 */
class StackStateAnalyzer(
    private val blocks: List<BasicBlock>,
    private val reference: MethodReference,
) {
    fun analyze(): StackReport {
        val violations = ArrayList<String>()
        var maxDepth = 0
        var instructions = 0

        blocks.forEach { block ->
            var depth = if (block.kind == BasicBlock.Kind.EXCEPTION_HANDLER) 1 else 0
            block.instructions.forEachIndexed { index, instruction ->
                instructions++
                val effect = effectOf(instruction)
                val expectedEntry = if (block.kind == BasicBlock.Kind.EXCEPTION_HANDLER && index == 0) 1 else 0
                if (depth != expectedEntry) {
                    violations +=
                        "$reference: ${block} instruction $index ($instruction) starts with operand stack depth " +
                            "$depth, expected $expectedEntry"
                }
                maxDepth = maxOf(maxDepth, depth + effect.peakDepth)
                depth += effect.netDepth
                if (depth != 0) {
                    violations += "$reference: ${block} instruction $index ($instruction) leaves operand stack depth $depth"
                }
            }
        }

        return StackReport(maxDepth = maxOf(maxDepth, 1), instructions = instructions, violations = violations.take(16))
    }

    private fun effectOf(instruction: LirInstruction): StackEffect = when (instruction) {
        is LirInstruction.Move -> StackEffect(netDepth = 0, peakDepth = width(instruction.source))
        is LirInstruction.LoadConstant -> StackEffect(0, maxOf(1, constantWidth(instruction.constant)))
        is LirInstruction.BinaryOp -> StackEffect(0, operandWidth(instruction.left) + operandWidth(instruction.right))
        is LirInstruction.UnaryOp -> StackEffect(0, width(instruction.source))
        is LirInstruction.Conversion -> StackEffect(0, width(instruction.source))
        is LirInstruction.Compare -> StackEffect(0, width(instruction.left) + width(instruction.right))
        is LirInstruction.Return -> StackEffect(0, instruction.value?.let { width(it) } ?: 0)
        is LirInstruction.Goto -> StackEffect(0, 0)
        is LirInstruction.IfTest -> StackEffect(0, operandWidth(instruction.left) + operandWidth(instruction.right))
        is LirInstruction.Switch -> StackEffect(0, 1)
        is LirInstruction.Invoke -> StackEffect(0, 1 + invokeArgumentWidth(instruction))
        is LirInstruction.Construct -> StackEffect(0, 2 + instruction.arguments.sumOf { width(it) })
        is LirInstruction.NewArray -> StackEffect(0, 2)
        // The array stays on the stack while each element is pushed and stored into it: the peak is
        // the array, one index, and the widest element.
        is LirInstruction.NewArrayFilled -> StackEffect(
            netDepth = 0,
            peakDepth = 2 + (instruction.values.maxOfOrNull { width(it) } ?: 0),
        )

        is LirInstruction.ArrayFill -> StackEffect(
            netDepth = 0,
            peakDepth = 2 + (instruction.values.maxOfOrNull { constantWidth(it) } ?: 0),
        )

        is LirInstruction.ArrayLoad -> StackEffect(0, 1 + width(instruction.index))
        is LirInstruction.ArrayStore -> StackEffect(0, 1 + width(instruction.index) + width(instruction.value))
        is LirInstruction.ArrayLength -> StackEffect(0, 1)
        is LirInstruction.FieldGet -> StackEffect(0, if (instruction.instance == null) 1 else 2)
        is LirInstruction.FieldPut -> StackEffect(0, (if (instruction.instance == null) 0 else 1) + width(instruction.value))
        is LirInstruction.CheckCast -> StackEffect(0, 1)
        is LirInstruction.InstanceOf -> StackEffect(0, 1)
        is LirInstruction.MonitorEnter -> StackEffect(0, 1)
        is LirInstruction.MonitorExit -> StackEffect(0, 1)
        is LirInstruction.Throw -> StackEffect(0, 1)
        // A handler entry starts with the throwable on the stack; `astore` consumes it.
        is LirInstruction.CatchException -> StackEffect(netDepth = -1, peakDepth = 0)
        LirInstruction.NoOp -> StackEffect(0, 0)
        is LirInstruction.Unsupported -> StackEffect(0, 0)
    }

    private fun invokeArgumentWidth(invoke: LirInstruction.Invoke): Int {
        val instance = if (invoke.receiver == null) 0 else 1
        val arguments = invoke.arguments.sumOf { width(it) }
        val result = if (invoke.result == null || invoke.reference.returnType.isVoid) 0 else 1
        // The callee's arguments are pushed, then the result is stored: peak is the arguments.
        return instance + arguments + result
    }

    private fun operandWidth(operand: LirInstruction.Operand): Int = when (operand) {
        is LirInstruction.Operand.Register -> width(operand.ref)
        is LirInstruction.Operand.Literal -> 1
    }

    private fun constantWidth(constant: riad.codes.vortex.ir.Constant): Int = when (constant) {
        is riad.codes.vortex.ir.Constant.LongValue, is riad.codes.vortex.ir.Constant.DoubleValue,
        is riad.codes.vortex.ir.Constant.WideBits,
        -> 2

        else -> 1
    }

    private fun width(ref: RegisterRef): Int = width(ref.type)

    private fun width(type: VortexType): Int = if (type.isCategory2) 2 else 1
}
