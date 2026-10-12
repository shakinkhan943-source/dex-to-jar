package riad.codes.vortex.analysis

import riad.codes.vortex.ir.ArrayType
import riad.codes.vortex.ir.BasicBlock
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
import riad.codes.vortex.ir.HirMonitorEnter
import riad.codes.vortex.ir.HirMonitorExit
import riad.codes.vortex.ir.HirMove
import riad.codes.vortex.ir.HirMoveException
import riad.codes.vortex.ir.HirMoveResult
import riad.codes.vortex.ir.HirNewArray
import riad.codes.vortex.ir.HirNewInstance
import riad.codes.vortex.ir.HirNop
import riad.codes.vortex.ir.HirOperand
import riad.codes.vortex.ir.HirProgram
import riad.codes.vortex.ir.HirReturn
import riad.codes.vortex.ir.HirStaticFieldGet
import riad.codes.vortex.ir.HirStaticFieldPut
import riad.codes.vortex.ir.HirSwitch
import riad.codes.vortex.ir.HirThrow
import riad.codes.vortex.ir.HirUnaryOp
import riad.codes.vortex.ir.HirUnsupported
import riad.codes.vortex.ir.InvokeKind
import riad.codes.vortex.ir.LirInstruction
import riad.codes.vortex.ir.NumericKind
import riad.codes.vortex.ir.PrimitiveType
import riad.codes.vortex.ir.RegisterRef
import riad.codes.vortex.ir.TranslationException
import riad.codes.vortex.ir.UnaryOperation
import riad.codes.vortex.ir.VortexType
import riad.codes.vortex.ir.VortexTypes

/**
 * Lowers the high-level IR of one method into the low-level IR.
 *
 * The pass walks the CFG while replaying the abstract interpreter's state, so every decision that
 * depends on "what is in this register right now" is taken from the same analysis the type inference
 * produced rather than from a second guess:
 *
 * * `new-instance` followed by `<init>` becomes a single [LirInstruction.Construct], because the
 *   abstract state proves the receiver is still uninitialised. This keeps uninitialised references off
 *   the operand stack and out of local slots, where the JVM verifier constrains them heavily.
 * * `aget`/`aput` pick `iaload`/`aaload`/`laload` from the inferred *array* type.
 * * `if-eqz` becomes an `int` comparison or a null check from the inferred type of the tested register.
 * * `not-int`/`not-long` fold into a `xor` with `-1`, since the JVM has no "not" opcode.
 */
class MethodLowering(
    private val program: HirProgram,
    private val method: HirMethod,
    private val cfg: HirControlFlowGraph,
    private val inference: MethodTypeInference,
    private val versions: MethodVersions,
) {
    private val lirBlocks: MutableMap<HirBlock, BasicBlock> = LinkedHashMap()
    private val consumedMoveResults = HashSet<Int>()
    private val instructionAfter: Map<Int, HirInstruction>

    init {
        val next = HashMap<Int, HirInstruction>()
        val instructions = cfg.body.instructions
        instructions.forEachIndexed { index, instruction ->
            instructions.getOrNull(index + 1)?.let { next[instruction.address] = it }
        }
        instructionAfter = next
    }

    fun lower(): List<BasicBlock> {
        cfg.blocks.forEach { hirBlock ->
            lirBlocks[hirBlock] = BasicBlock(hirBlock.id, hirBlock.startAddress, roleOf(hirBlock))
        }
        cfg.blocks.forEach { hirBlock ->
            val block = lirBlocks.getValue(hirBlock)
            hirBlock.successors.forEach { block.successors += lirBlocks.getValue(it) }
            hirBlock.predecessors.forEach { block.predecessors += lirBlocks.getValue(it) }
        }

        cfg.blocks.forEach { hirBlock ->
            val block = lirBlocks.getValue(hirBlock)
            var state = inference.entryState(hirBlock)
            hirBlock.instructions.forEach { instruction ->
                if (instruction.address !in consumedMoveResults) {
                    lowerInstruction(instruction, state, block)
                }
                state = inference.transfer(instruction, state)
            }
        }

        return cfg.blocks.map { lirBlocks.getValue(it) }
    }

    private fun lowerInstruction(instruction: HirInstruction, state: RegisterState, block: BasicBlock) {
        when (instruction) {
            is HirNop -> block.add(LirInstruction.NoOp)

            is HirMove -> block.add(LirInstruction.Move(definition(instruction.dest, instruction.address), read(instruction.source, instruction.address)))

            // Folded into the invoke above it; if it shows up here the invoke had no result register.
            is HirMoveResult -> Unit

            is HirMoveException -> block.add(LirInstruction.CatchException(definition(instruction.dest, instruction.address)))

            is HirConst -> {
                val dest = definition(instruction.dest, instruction.address)
                block.add(LirInstruction.LoadConstant(dest, materialize(instruction.value, dest.type)))
            }

            is HirConstString -> block.add(
                LirInstruction.LoadConstant(definition(instruction.dest, instruction.address), Constant.StringValue(instruction.value)),
            )

            is HirConstClass -> block.add(
                LirInstruction.LoadConstant(definition(instruction.dest, instruction.address), Constant.TypeValue(instruction.type)),
            )

            is HirBinaryOp -> block.add(
                LirInstruction.BinaryOp(
                    dest = definition(instruction.dest, instruction.address),
                    operation = instruction.operation,
                    operandType = primitiveOf(instruction.kind),
                    left = operand(instruction.left, instruction.address),
                    right = operand(instruction.right, instruction.address),
                ),
            )

            is HirUnaryOp -> lowerUnary(instruction, block)

            is HirConversion -> block.add(
                LirInstruction.Conversion(
                    dest = definition(instruction.dest, instruction.address),
                    from = instruction.from,
                    to = instruction.to,
                    source = read(instruction.source, instruction.address),
                ),
            )

            is HirCompare -> block.add(
                LirInstruction.Compare(
                    dest = definition(instruction.dest, instruction.address),
                    operation = instruction.operation,
                    left = read(instruction.left, instruction.address),
                    right = read(instruction.right, instruction.address),
                ),
            )

            is HirReturn -> block.add(
                LirInstruction.Return(instruction.register?.let { read(it, instruction.address) }),
            )

            is HirGoto -> block.add(LirInstruction.Goto(blockFor(instruction.target)))

            is HirIfTest -> block.add(
                LirInstruction.IfTest(
                    comparison = instruction.comparison,
                    left = operand(HirOperand.Register(instruction.left), instruction.address),
                    right = operand(instruction.right, instruction.address),
                    target = blockFor(instruction.target),
                ),
            )

            is HirSwitch -> block.add(
                LirInstruction.Switch(
                    value = read(instruction.value, instruction.address),
                    keys = instruction.keys,
                    targets = instruction.targets.map { blockFor(it) },
                    defaultTarget = blockFor(instruction.defaultTarget),
                ),
            )

            is HirInvoke -> lowerInvoke(instruction, state, block)

            is HirNewInstance -> {
                // The allocation itself happens at the matching `<init>` (see [lowerInvoke]); here the
                // register only gets a defined value, so no uninitialised reference ever reaches a
                // local slot. The constant is a `null` for a reference version.
                block.add(
                    LirInstruction.LoadConstant(definition(instruction.dest, instruction.address), Constant.Zero),
                )
            }

            is HirNewArray -> block.add(
                LirInstruction.NewArray(
                    dest = definition(instruction.dest, instruction.address),
                    componentType = instruction.componentType,
                    length = read(instruction.length, instruction.address),
                ),
            )

            is HirFilledNewArray -> block.add(
                LirInstruction.NewArrayFilled(
                    dest = definition(filledArrayResultRegisterOf(instruction), instruction.address),
                    componentType = instruction.componentType,
                    values = instruction.values.map { read(it, instruction.address) },
                ),
            )

            is HirArrayLoad -> block.add(
                LirInstruction.ArrayLoad(
                    dest = definition(instruction.dest, instruction.address),
                    componentType = elementType(instruction.componentType, instruction.array, state),
                    array = read(instruction.array, instruction.address),
                    index = read(instruction.index, instruction.address),
                ),
            )

            is HirArrayStore -> block.add(
                LirInstruction.ArrayStore(
                    componentType = elementType(instruction.componentType, instruction.array, state),
                    array = read(instruction.array, instruction.address),
                    index = read(instruction.index, instruction.address),
                    value = read(instruction.value, instruction.address),
                ),
            )

            is HirArrayLength -> block.add(
                LirInstruction.ArrayLength(
                    dest = definition(instruction.dest, instruction.address),
                    array = read(instruction.array, instruction.address),
                ),
            )

            is HirInstanceFieldGet -> block.add(
                LirInstruction.FieldGet(
                    dest = definition(instruction.dest, instruction.address),
                    reference = instruction.reference,
                    instance = read(instruction.instance, instruction.address),
                ),
            )

            is HirInstanceFieldPut -> block.add(
                LirInstruction.FieldPut(
                    reference = instruction.reference,
                    instance = read(instruction.instance, instruction.address),
                    value = read(instruction.value, instruction.address),
                ),
            )

            is HirStaticFieldGet -> block.add(
                LirInstruction.FieldGet(
                    dest = definition(instruction.dest, instruction.address),
                    reference = instruction.reference,
                    instance = null,
                ),
            )

            is HirStaticFieldPut -> block.add(
                LirInstruction.FieldPut(
                    reference = instruction.reference,
                    instance = null,
                    value = read(instruction.value, instruction.address),
                ),
            )

            is HirCheckCast -> block.add(
                LirInstruction.CheckCast(read(instruction.register, instruction.address), instruction.type),
            )

            is HirInstanceOf -> block.add(
                LirInstruction.InstanceOf(
                    dest = definition(instruction.dest, instruction.address),
                    source = read(instruction.source, instruction.address),
                    type = instruction.type,
                ),
            )

            is HirMonitorEnter -> block.add(LirInstruction.MonitorEnter(read(instruction.register, instruction.address)))
            is HirMonitorExit -> block.add(LirInstruction.MonitorExit(read(instruction.register, instruction.address)))
            is HirThrow -> block.add(LirInstruction.Throw(read(instruction.register, instruction.address)))

            is HirFillArrayData -> {
                val componentType = elementType(payloadHint(instruction.elementWidth), instruction.array, state)
                block.add(
                    LirInstruction.ArrayFill(
                        array = read(instruction.array, instruction.address),
                        componentType = componentType,
                        values = instruction.elements.map { payloadConstant(it, componentType) },
                    ),
                )
            }

            is HirUnsupported -> block.add(LirInstruction.Unsupported(instruction.opcodeName, instruction.address))
        }
    }

    private fun lowerUnary(instruction: HirUnaryOp, block: BasicBlock) {
        when (instruction.operation) {
            // The JVM has no bitwise-not opcode; `not-int` is `x ^ -1`.
            UnaryOperation.NOT -> block.add(
                LirInstruction.BinaryOp(
                    dest = definition(instruction.dest, instruction.address),
                    operation = riad.codes.vortex.ir.BinaryOperation.XOR,
                    operandType = primitiveOf(instruction.kind),
                    left = LirInstruction.Operand.Register(read(instruction.operand, instruction.address)),
                    right = LirInstruction.Operand.Literal(minusOne(instruction.kind)),
                ),
            )

            UnaryOperation.NEGATE -> block.add(
                LirInstruction.UnaryOp(
                    dest = definition(instruction.dest, instruction.address),
                    operation = UnaryOperation.NEGATE,
                    operandType = primitiveOf(instruction.kind),
                    source = read(instruction.operand, instruction.address),
                ),
            )
        }
    }

    private fun lowerInvoke(instruction: HirInvoke, state: RegisterState, block: BasicBlock) {
        val resultRegister = resultRegisterOf(instruction)
        val result = resultRegister?.let { (_, register) -> definition(register, instruction.address) }

        if (instruction.kind == InvokeKind.DIRECT && instruction.reference.isConstructor) {
            val receiverRegister = instruction.arguments.firstOrNull()
            val receiverState = receiverRegister?.let { state[it] }
            if (receiverRegister != null && receiverState is AbstractType.Uninitialized) {
                // `new-instance` + `<init>`: allocate, initialise and store in one go.
                block.add(
                    LirInstruction.Construct(
                        reference = instruction.reference,
                        receiver = read(receiverRegister, instruction.address),
                        arguments = instruction.arguments.drop(1).map { read(it, instruction.address) },
                    ),
                )
                return
            }
        }

        val isStatic = instruction.kind == InvokeKind.STATIC
        val receiver = if (isStatic) null else instruction.arguments.firstOrNull()?.let { read(it, instruction.address) }
        val arguments = (if (isStatic) instruction.arguments else instruction.arguments.drop(1))
            .map { read(it, instruction.address) }

        val invokeAddress = resultRegister?.first
        if (invokeAddress != null) consumedMoveResults += invokeAddress

        block.add(
            LirInstruction.Invoke(
                kind = instruction.kind,
                reference = instruction.reference,
                receiver = receiver,
                arguments = arguments,
                result = result,
                isInterface = program.hierarchy.isInterface(instruction.reference.owner.internalName),
            ),
        )
    }

    /**
     * The `move-result` that follows [invoke], if any: `(move-result address, destination register)`.
     */
    private fun resultRegisterOf(invoke: HirInvoke): Pair<Int, Int>? {
        if (invoke.reference.returnType.isVoid) return null
        val next = instructionAfter[invoke.address] as? HirMoveResult ?: return null
        return next.address to next.dest
    }

    /**
     * The register a `filled-new-array` puts its array in: the destination of the `move-result-object`
     * that DEX requires it to be paired with.
     */
    private fun filledArrayResultRegisterOf(instruction: HirFilledNewArray): Int =
        (instructionAfter[instruction.address] as? HirMoveResult)?.dest
            ?: throw TranslationException(
                "filled-new-array at code unit ${instruction.address} has no move-result-object to receive the array",
            )

    /** The element type a payload width implies when the array's own type says nothing. */
    private fun payloadHint(elementWidth: Int): VortexType? = when (elementWidth) {
        1 -> VortexTypes.BYTE
        2 -> VortexTypes.SHORT
        4 -> VortexTypes.INT
        8 -> VortexTypes.LONG
        else -> null
    }

    /**
     * One element of an `array-data` payload as a value the JVM can push.
     *
     * The payload carries raw bits, so a 4-byte element of a `float[]` is a `float` and an 8-byte
     * element of a `double[]` is a `double`; the array's element type is what says which reading
     * applies, and the `int`/`long` reading covers every other element family.
     */
    private fun payloadConstant(bits: Long, target: VortexType): Constant {
        val kind = (target as? PrimitiveType)?.kind?.numeric
        return when (kind) {
            NumericKind.DOUBLE -> Constant.DoubleValue(Double.fromBits(bits))
            NumericKind.LONG -> Constant.LongValue(bits)
            NumericKind.FLOAT -> Constant.FloatValue(Float.fromBits(bits.toInt()))
            else -> Constant.IntValue(bits.toInt())
        }
    }

    /**
     * Turns a raw constant payload into a value the JVM constant pool can hold.
     *
     * `const/high16 v0, #0x40200000` is the `int` 1075838976 *and* the `float` 2.5f, and a wide payload
     * is a `long` or a `double`; by this point the inference has already decided which, from the use
     * that consumes the register. The bits are *reinterpreted* rather than converted, because that is
     * what the DEX meant: `2.5` and `1075838976` have the same payload on purpose.
     */
    private fun materialize(constant: Constant, target: VortexType): Constant {
        val kind = (target as? PrimitiveType)?.kind?.numeric
        return when {
            constant is Constant.NarrowBits && kind == NumericKind.FLOAT -> Constant.FloatValue(Float.fromBits(constant.bits))
            constant is Constant.NarrowBits -> Constant.IntValue(constant.bits)
            constant is Constant.WideBits && kind == NumericKind.DOUBLE -> Constant.DoubleValue(Double.fromBits(constant.bits))
            constant is Constant.WideBits -> Constant.LongValue(constant.bits)
            else -> constant
        }
    }

    private fun operand(operand: HirOperand, address: Int): LirInstruction.Operand = when (operand) {
        is HirOperand.Register -> LirInstruction.Operand.Register(read(operand.register, address))
        is HirOperand.Literal -> LirInstruction.Operand.Literal(constantOf(operand.value))
    }

    /**
     * The element type of an array access.
     *
     * The array's own inferred type wins: the JVM picks `baload`/`caload`/`saload`/`iaload` from the
     * *array* type, and an untyped `aget` gives no information at all. The opcode's built-in hint is
     * the fallback for arrays whose type the inference could not determine.
     */
    private fun elementType(hint: VortexType?, arrayRegister: Int, state: RegisterState): VortexType {
        val arrayType = state[arrayRegister]
        val derived = when (arrayType) {
            is AbstractType.Array -> when (val component = arrayType.component) {
                is AbstractType.Primitive -> PrimitiveType(component.kind)
                is AbstractType.Reference -> riad.codes.vortex.ir.ObjectType(component.internalName)
                is AbstractType.Uninitialized -> riad.codes.vortex.ir.ObjectType(component.internalName)
                is AbstractType.Array -> ArrayType(VortexTypes.OBJECT, component.dimensions)
                else -> null
            }

            else -> null
        }
        return derived ?: hint ?: VortexTypes.OBJECT
    }

    private fun definition(register: Int, address: Int): RegisterRef =
        versions.definitionAt(address, register)
            ?: throw TranslationException("no register version was created for v$register defined at code unit $address")

    private fun read(register: Int, address: Int): RegisterRef = versions.readAt(address, register)

    private fun blockFor(address: Int): BasicBlock {
        val hirBlock = cfg.blockAt(address)
            ?: throw TranslationException("no basic block starts at code unit $address")
        return lirBlocks.getValue(hirBlock)
    }

    private fun roleOf(block: HirBlock): BasicBlock.Kind = when {
        block.isExceptionHandler -> BasicBlock.Kind.EXCEPTION_HANDLER
        block.role == BlockRole.ENTRY -> BasicBlock.Kind.ENTRY
        else -> BasicBlock.Kind.NORMAL
    }

    private fun constantOf(value: Long): Constant = if (value == 0L) Constant.Zero else Constant.IntValue(value.toInt())

    private fun minusOne(kind: NumericKind): Constant = when (kind) {
        NumericKind.INT -> Constant.IntValue(-1)
        NumericKind.LONG -> Constant.LongValue(-1L)
        NumericKind.FLOAT -> Constant.FloatValue(-1f)
        NumericKind.DOUBLE -> Constant.DoubleValue(-1.0)
    }

    private fun primitiveOf(kind: NumericKind): VortexType = when (kind) {
        NumericKind.INT -> VortexTypes.INT
        NumericKind.LONG -> VortexTypes.LONG
        NumericKind.FLOAT -> VortexTypes.FLOAT
        NumericKind.DOUBLE -> VortexTypes.DOUBLE
    }
}
