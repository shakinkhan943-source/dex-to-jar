package riad.codes.vortex.emit

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import riad.codes.vortex.ir.AccessFlags
import riad.codes.vortex.ir.ArrayType
import riad.codes.vortex.ir.BinaryOperation
import riad.codes.vortex.ir.CompareOperation
import riad.codes.vortex.ir.Comparison
import riad.codes.vortex.ir.Constant
import riad.codes.vortex.ir.LirInstruction
import riad.codes.vortex.ir.LirMethod
import riad.codes.vortex.ir.NumericKind
import riad.codes.vortex.ir.ObjectType
import riad.codes.vortex.ir.PrimitiveKind
import riad.codes.vortex.ir.PrimitiveType
import riad.codes.vortex.ir.RegisterRef
import riad.codes.vortex.ir.TranslationException
import riad.codes.vortex.ir.UnaryOperation
import riad.codes.vortex.ir.VortexType

/** Knobs for bytecode emission. */
data class EmitOptions(
    /** Class file version; Java 8 by default, which is what most Android toolchain consumers want. */
    val classFileVersion: Int = Opcodes.V1_8,

    /** Emit a `LocalVariableTable` so decompilers can recover names and scopes. */
    val emitLocalVariableTable: Boolean = true,

    /** Wrap the writer in `CheckClassAdapter` to catch invalid visitor call sequences. */
    val checkVisitorCalls: Boolean = false,

    /** Run the type checker over the emitted class before returning it. */
    val verifyBytecode: Boolean = false,
)

/**
 * Emits one analysed method as JVM bytecode.
 *
 * Every LIR instruction is self-contained: operands are loaded from local slots, the operation runs
 * on the operand stack, and the result is stored back into a slot. That is deliberately unoptimised
 * compared to what a JIT would want, and it is what makes the output verifiable: the operand stack is
 * empty at every instruction boundary, so no value ever has to be tracked across a branch, and the
 * frame computation in [DexAwareClassWriter] only ever merges local slots.
 */
class AsmMethodEmitter(private val options: EmitOptions = EmitOptions()) {

    fun emit(cv: ClassVisitor, method: LirMethod, annotations: List<riad.codes.vortex.ir.HirAnnotation> = emptyList()) {
        val access = AccessFlags.dexMethodFlagsToJvm(method.accessFlags)
        val mv = cv.visitMethod(access, method.reference.name, method.reference.descriptor, null, null)
        try {
            AnnotationEmitter.emitAll(mv::visitAnnotation, annotations)
            emitBody(mv, method)
        } finally {
            mv.visitEnd()
        }
    }

    /** Emits an abstract or native method: a declaration with no code attribute. */
    fun emitDeclaration(cv: ClassVisitor, lir: LirMethod) {
        val access = AccessFlags.dexMethodFlagsToJvm(lir.accessFlags)
        cv.visitMethod(access, lir.reference.name, lir.reference.descriptor, null, null).visitEnd()
    }

    /**
     * Labels for one method emission.
     *
     * Keyed by block id so that a method can be emitted concurrently with other methods: the emitter
     * itself stays stateless and never shares mutable state between calls.
     */
    private class LabelSet(val labels: Map<Int, Label>, val endLabels: Map<Int, Label>) {
        fun of(blockId: Int): Label = labels[blockId] ?: throw TranslationException("no label for basic block $blockId")

        fun endOf(blockId: Int): Label = endLabels[blockId] ?: throw TranslationException("no end label for basic block $blockId")
    }

    private fun emitBody(mv: MethodVisitor, method: LirMethod) {
        val emitted = method.blocks.sortedBy { it.startAddress }
        val labels: Map<Int, Label> = emitted.associate { it.id to Label() }
        val endLabel = Label()
        val endLabels: Map<Int, Label> = emitted.withIndex().associate { (index, block) ->
            block.id to (emitted.getOrNull(index + 1)?.let { labels.getValue(it.id) } ?: endLabel)
        }
        val labelSet = LabelSet(labels, endLabels)

        mv.visitCode()

        method.tryBlocks.forEach { tryBlock ->
            val start = labels[tryBlock.start.id] ?: return@forEach
            val end = tryBlock.end?.let { labels[it.id] } ?: endLabel
            tryBlock.handlers.forEach { handler ->
                val handlerLabel = labels[handler.handler.id] ?: return@forEach
                mv.visitTryCatchBlock(start, end, handlerLabel, handler.exceptionType?.let { typeName(it) })
            }
        }


        emitted.forEachIndexed { index, block ->
            val label = labels.getValue(block.id)
            mv.visitLabel(label)
            method.lineNumbers[block.startAddress]?.let { line -> mv.visitLineNumber(line, label) }
            val nextBlock = emitted.getOrNull(index + 1)
            block.instructions.forEachIndexed { instructionIndex, instruction ->
                val redundantGoto = instruction is LirInstruction.Goto &&
                    instructionIndex == block.instructions.lastIndex &&
                    nextBlock?.id == instruction.target.id
                if (!redundantGoto) emitInstruction(mv, method, instruction, labelSet)
            }
        }

        mv.visitLabel(endLabel)
        if (options.emitLocalVariableTable) emitLocalVariables(mv, method, labelSet)
        mv.visitMaxs(maxOf(method.summary.estimatedMaxStack, 1), maxOf(method.locals, minimumLocals(method)))
    }

    private fun emitLocalVariables(mv: MethodVisitor, method: LirMethod, labelSet: LabelSet) {
        method.localVariables.forEach { variable ->
            if (variable.slot < 0) return@forEach
            val start = labelSet.labels[method.blockIdAt(variable.range.start)] ?: return@forEach
            val end = labelSet.endLabels[method.blockIdAt(variable.range.end)] ?: return@forEach
            mv.visitLocalVariable(variable.name, variable.descriptor, null, start, end, variable.slot)
        }
    }

    private fun emitInstruction(
        mv: MethodVisitor,
        method: LirMethod,
        instruction: LirInstruction,
        labelSet: LabelSet,
    ) {
        when (instruction) {
            is LirInstruction.Move -> {
                val destSlot = method.slotOf(instruction.dest)
                val sourceSlot = method.slotOf(instruction.source)
                if (destSlot == sourceSlot && instruction.dest.type == instruction.source.type) return
                load(mv, instruction.source, sourceSlot)
                store(mv, instruction.dest, destSlot)
            }

            is LirInstruction.LoadConstant -> {
                pushConstant(mv, instruction.constant, instruction.dest.type)
                store(mv, instruction.dest, method.slotOf(instruction.dest))
            }

            is LirInstruction.BinaryOp -> {
                val opcode = binaryOpcode(instruction.operation, instruction.operandType)
                pushOperand(mv, method, instruction.left)
                pushOperand(mv, method, instruction.right)
                mv.visitInsn(opcode)
                store(mv, instruction.dest, method.slotOf(instruction.dest))
            }

            is LirInstruction.UnaryOp -> {
                load(mv, instruction.source, method.slotOf(instruction.source))
                mv.visitInsn(
                    when (instruction.operation) {
                        UnaryOperation.NEGATE -> negateOpcode(instruction.operandType)
                        UnaryOperation.NOT -> throw TranslationException("bitwise not is lowered to xor by the analyser")
                    },
                )
                store(mv, instruction.dest, method.slotOf(instruction.dest))
            }

            is LirInstruction.Conversion -> {
                load(mv, instruction.source, method.slotOf(instruction.source))
                mv.visitInsn(conversionOpcode(instruction.from, instruction.to))
                store(mv, instruction.dest, method.slotOf(instruction.dest))
            }

            is LirInstruction.Compare -> {
                load(mv, instruction.left, method.slotOf(instruction.left))
                load(mv, instruction.right, method.slotOf(instruction.right))
                mv.visitInsn(compareOpcode(instruction.operation))
                store(mv, instruction.dest, method.slotOf(instruction.dest))
            }

            is LirInstruction.Return -> {
                val value = instruction.value
                if (value == null) {
                    mv.visitInsn(Opcodes.RETURN)
                } else {
                    load(mv, value, method.slotOf(value))
                    mv.visitInsn(returnOpcode(value.type))
                }
            }

            is LirInstruction.Goto -> mv.visitJumpInsn(Opcodes.GOTO, labelSet.of(instruction.target.id))

            is LirInstruction.IfTest -> emitIf(mv, method, instruction, labelSet)

            is LirInstruction.Switch -> emitSwitch(mv, method, instruction, labelSet)

            is LirInstruction.Invoke -> {
                val opcode = invokeOpcode(instruction)
                instruction.receiver?.let { load(mv, it, method.slotOf(it)) }
                instruction.arguments.forEach { load(mv, it, method.slotOf(it)) }
                mv.visitMethodInsn(
                    opcode,
                    instruction.reference.owner.internalName,
                    instruction.reference.name,
                    instruction.reference.descriptor,
                    instruction.isInterface,
                )
                val result = instruction.result
                when {
                    result != null -> store(mv, result, method.slotOf(result))
                    // A discarded non-void result still has to leave the stack.
                    !instruction.reference.returnType.isVoid ->
                        mv.visitInsn(if (instruction.reference.returnType.isCategory2) Opcodes.POP2 else Opcodes.POP)

                    else -> Unit
                }
            }

            is LirInstruction.Construct -> {
                val owner = instruction.reference.owner.internalName
                mv.visitTypeInsn(Opcodes.NEW, owner)
                mv.visitInsn(Opcodes.DUP)
                instruction.arguments.forEach { load(mv, it, method.slotOf(it)) }
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, "<init>", instruction.reference.descriptor, false)
                store(mv, instruction.receiver, method.slotOf(instruction.receiver))
            }

            is LirInstruction.NewArray -> {
                load(mv, instruction.length, method.slotOf(instruction.length))
                emitNewArray(mv, instruction.componentType)
                store(mv, instruction.dest, method.slotOf(instruction.dest))
            }

            is LirInstruction.NewArrayFilled -> {
                pushInt(mv, instruction.values.size)
                emitNewArray(mv, instruction.componentType)
                val storeOpcode = arrayStoreOpcode(instruction.componentType)
                instruction.values.forEachIndexed { index, value ->
                    // The array stays on the stack while every element is written into it, so the store
                    // into the destination slot happens once, at the end.
                    mv.visitInsn(Opcodes.DUP)
                    pushInt(mv, index)
                    load(mv, value, method.slotOf(value))
                    mv.visitInsn(storeOpcode)
                }
                store(mv, instruction.dest, method.slotOf(instruction.dest))
            }

            is LirInstruction.ArrayFill -> {
                val storeOpcode = arrayStoreOpcode(instruction.componentType)
                instruction.values.forEachIndexed { index, value ->
                    load(mv, instruction.array, method.slotOf(instruction.array))
                    pushInt(mv, index)
                    pushConstant(mv, value, instruction.componentType)
                    mv.visitInsn(storeOpcode)
                }
            }

            is LirInstruction.ArrayLoad -> {
                load(mv, instruction.array, method.slotOf(instruction.array))
                load(mv, instruction.index, method.slotOf(instruction.index))
                mv.visitInsn(arrayLoadOpcode(instruction.componentType))
                store(mv, instruction.dest, method.slotOf(instruction.dest))
            }

            is LirInstruction.ArrayStore -> {
                load(mv, instruction.array, method.slotOf(instruction.array))
                load(mv, instruction.index, method.slotOf(instruction.index))
                load(mv, instruction.value, method.slotOf(instruction.value))
                mv.visitInsn(arrayStoreOpcode(instruction.componentType))
            }

            is LirInstruction.ArrayLength -> {
                load(mv, instruction.array, method.slotOf(instruction.array))
                mv.visitInsn(Opcodes.ARRAYLENGTH)
                store(mv, instruction.dest, method.slotOf(instruction.dest))
            }

            is LirInstruction.FieldGet -> {
                val field = instruction.reference
                instruction.instance?.let { load(mv, it, method.slotOf(it)) }
                mv.visitFieldInsn(
                    if (instruction.instance == null) Opcodes.GETSTATIC else Opcodes.GETFIELD,
                    field.owner.internalName,
                    field.name,
                    field.descriptor,
                )
                store(mv, instruction.dest, method.slotOf(instruction.dest))
            }

            is LirInstruction.FieldPut -> {
                val field = instruction.reference
                instruction.instance?.let { load(mv, it, method.slotOf(it)) }
                load(mv, instruction.value, method.slotOf(instruction.value))
                mv.visitFieldInsn(
                    if (instruction.instance == null) Opcodes.PUTSTATIC else Opcodes.PUTFIELD,
                    field.owner.internalName,
                    field.name,
                    field.descriptor,
                )
            }

            is LirInstruction.CheckCast -> {
                load(mv, instruction.register, method.slotOf(instruction.register))
                mv.visitTypeInsn(Opcodes.CHECKCAST, typeName(instruction.type))
                store(mv, instruction.register, method.slotOf(instruction.register))
            }

            is LirInstruction.InstanceOf -> {
                load(mv, instruction.source, method.slotOf(instruction.source))
                mv.visitTypeInsn(Opcodes.INSTANCEOF, typeName(instruction.type))
                store(mv, instruction.dest, method.slotOf(instruction.dest))
            }

            is LirInstruction.MonitorEnter -> {
                load(mv, instruction.register, method.slotOf(instruction.register))
                mv.visitInsn(Opcodes.MONITORENTER)
            }

            is LirInstruction.MonitorExit -> {
                load(mv, instruction.register, method.slotOf(instruction.register))
                mv.visitInsn(Opcodes.MONITOREXIT)
            }

            is LirInstruction.Throw -> {
                load(mv, instruction.register, method.slotOf(instruction.register))
                mv.visitInsn(Opcodes.ATHROW)
            }

            // A handler entry has the throwable pushed by the JVM; storing it empties the stack.
            is LirInstruction.CatchException -> mv.visitVarInsn(Opcodes.ASTORE, method.slotOf(instruction.dest))

            LirInstruction.NoOp -> Unit

            // Unsupported instructions never reach the emitter: the class emitter replaces the whole
            // body with a reported stub, because a partially translated method could be silently wrong.
            is LirInstruction.Unsupported -> Unit
        }
    }

    private fun emitIf(
        mv: MethodVisitor,
        method: LirMethod,
        instruction: LirInstruction.IfTest,
        labelSet: LabelSet,
    ) {
        val target = labelSet.of(instruction.target.id)
        val left = instruction.left
        val right = instruction.right
        val leftRef = (left as? LirInstruction.Operand.Register)?.ref
        val referenceComparison = leftRef?.type?.isReference == true

        if (referenceComparison) {
            val nullCheck = right as? LirInstruction.Operand.Literal
            val isZero = nullCheck?.constant?.let { it is Constant.Zero || it == Constant.IntValue(0) } == true
            if (!isZero) {
                throw TranslationException(
                    "reference comparison with a non-null literal in ${method.reference}: $instruction",
                )
            }
            if (!instruction.comparison.isEquality) {
                throw TranslationException(
                    "references can only be tested for equality in ${method.reference}: $instruction",
                )
            }
            load(mv, leftRef!!, method.slotOf(leftRef))
            mv.visitJumpInsn(
                if (instruction.comparison == Comparison.EQUAL) Opcodes.IFNULL else Opcodes.IFNONNULL,
                target,
            )
            return
        }

        pushOperand(mv, method, left)
        pushOperand(mv, method, right)
        mv.visitJumpInsn(integerJumpOpcode(instruction.comparison), target)
    }

    private fun emitSwitch(
        mv: MethodVisitor,
        method: LirMethod,
        instruction: LirInstruction.Switch,
        labelSet: LabelSet,
    ) {
        val default = labelSet.of(instruction.defaultTarget.id)
        val labels = instruction.targets.map { labelSet.of(it.id) }
        val keys = instruction.keys
        load(mv, instruction.value, method.slotOf(instruction.value))

        if (keys.size == labels.size && keys.size > 1 && isConsecutive(keys)) {
            mv.visitTableSwitchInsn(keys.first(), keys.last(), default, *labels.toTypedArray())
            return
        }
        // `lookupswitch` requires ascending keys.
        val ordered = keys.zip(labels).sortedBy { it.first }
        mv.visitLookupSwitchInsn(
            default,
            ordered.map { it.first }.toIntArray(),
            ordered.map { it.second }.toTypedArray(),
        )
    }

    private fun isConsecutive(keys: List<Int>): Boolean =
        keys.indices.drop(1).all { keys[it] == keys[it - 1] + 1 }

    private fun pushOperand(mv: MethodVisitor, method: LirMethod, operand: LirInstruction.Operand) {
        when (operand) {
            is LirInstruction.Operand.Register -> load(mv, operand.ref, method.slotOf(operand.ref))
            is LirInstruction.Operand.Literal -> pushConstant(mv, operand.constant, operand.constant.stackType)
        }
    }

    private fun pushConstant(mv: MethodVisitor, constant: Constant, expected: VortexType) {
        when (constant) {
            // `const/4 v0, #0`: the analysis decided whether this register holds an `int` or `null`.
            Constant.Zero -> when {
                expected.isReference -> mv.visitInsn(Opcodes.ACONST_NULL)
                expected.isFloat() -> mv.visitInsn(Opcodes.FCONST_0)
                else -> mv.visitInsn(Opcodes.ICONST_0)
            }

            // The lowering rewrites raw payloads into a typed constant once the inference has decided
            // what the register holds; these branches only run if that decision was impossible, in
            // which case the register's own type is still the best answer available.
            is Constant.NarrowBits -> if (expected.isFloat()) {
                mv.visitLdcInsn(Float.fromBits(constant.bits))
            } else {
                pushInt(mv, constant.bits)
            }

            is Constant.WideBits -> if (expected.isDouble()) {
                mv.visitLdcInsn(Double.fromBits(constant.bits))
            } else {
                mv.visitLdcInsn(constant.bits)
            }

            is Constant.IntValue -> pushInt(mv, constant.value)
            is Constant.LongValue -> when (constant.value) {
                0L -> mv.visitInsn(Opcodes.LCONST_0)
                1L -> mv.visitInsn(Opcodes.LCONST_1)
                else -> mv.visitLdcInsn(constant.value)
            }

            is Constant.FloatValue -> when (constant.value) {
                0f -> mv.visitInsn(Opcodes.FCONST_0)
                1f -> mv.visitInsn(Opcodes.FCONST_1)
                2f -> mv.visitInsn(Opcodes.FCONST_2)
                else -> mv.visitLdcInsn(constant.value)
            }

            is Constant.DoubleValue -> when (constant.value) {
                0.0 -> mv.visitInsn(Opcodes.DCONST_0)
                1.0 -> mv.visitInsn(Opcodes.DCONST_1)
                else -> mv.visitLdcInsn(constant.value)
            }

            is Constant.StringValue -> mv.visitLdcInsn(constant.value)
            is Constant.TypeValue -> mv.visitLdcInsn(Type.getType(constant.type.descriptor))
            is Constant.MethodTypeValue -> mv.visitLdcInsn(Type.getMethodType(constant.methodType.descriptor))
            is Constant.MethodHandleValue -> mv.visitLdcInsn(
                org.objectweb.asm.Handle(
                    constant.kind,
                    constant.reference.owner.internalName,
                    constant.reference.name,
                    constant.reference.descriptor,
                    constant.isInterface,
                ),
            )
        }
    }

    /** True when a constant landing in this register has to be materialised as a `float`. */
    private fun VortexType.isFloat(): Boolean = this is PrimitiveType && kind.numeric == NumericKind.FLOAT

    /** True when a constant landing in this register has to be materialised as a `double`. */
    private fun VortexType.isDouble(): Boolean = this is PrimitiveType && kind.numeric == NumericKind.DOUBLE

    private fun pushInt(mv: MethodVisitor, value: Int) {
        when (value) {
            -1 -> mv.visitInsn(Opcodes.ICONST_M1)
            0 -> mv.visitInsn(Opcodes.ICONST_0)
            1 -> mv.visitInsn(Opcodes.ICONST_1)
            2 -> mv.visitInsn(Opcodes.ICONST_2)
            3 -> mv.visitInsn(Opcodes.ICONST_3)
            4 -> mv.visitInsn(Opcodes.ICONST_4)
            5 -> mv.visitInsn(Opcodes.ICONST_5)
            in Byte.MIN_VALUE..Byte.MAX_VALUE -> mv.visitIntInsn(Opcodes.BIPUSH, value)
            in Short.MIN_VALUE..Short.MAX_VALUE -> mv.visitIntInsn(Opcodes.SIPUSH, value)
            else -> mv.visitLdcInsn(value)
        }
    }

    private fun load(mv: MethodVisitor, ref: RegisterRef, slot: Int) = mv.visitVarInsn(loadOpcode(ref.type), slot)

    private fun store(mv: MethodVisitor, ref: RegisterRef, slot: Int) = mv.visitVarInsn(storeOpcode(ref.type), slot)

    private fun emitNewArray(mv: MethodVisitor, componentType: VortexType) {
        if (componentType is PrimitiveType) {
            mv.visitIntInsn(Opcodes.NEWARRAY, arrayTypeCode(componentType.kind))
        } else {
            mv.visitTypeInsn(Opcodes.ANEWARRAY, typeName(componentType))
        }
    }

    private fun arrayTypeCode(kind: PrimitiveKind): Int = when (kind) {
        PrimitiveKind.BOOLEAN -> Opcodes.T_BOOLEAN
        PrimitiveKind.CHAR -> Opcodes.T_CHAR
        PrimitiveKind.FLOAT -> Opcodes.T_FLOAT
        PrimitiveKind.DOUBLE -> Opcodes.T_DOUBLE
        PrimitiveKind.BYTE -> Opcodes.T_BYTE
        PrimitiveKind.SHORT -> Opcodes.T_SHORT
        PrimitiveKind.INT -> Opcodes.T_INT
        PrimitiveKind.LONG -> Opcodes.T_LONG
        PrimitiveKind.VOID -> throw TranslationException("cannot create an array of void")
    }

    /** The operand of `anewarray`/`checkcast`/`instanceof`: internal name, or an array descriptor. */
    private fun typeName(type: VortexType): String = when (type) {
        is ObjectType -> type.internalName
        is ArrayType -> type.descriptor
        is PrimitiveType -> type.descriptor
    }

    private fun loadOpcode(type: VortexType): Int = when {
        type.isReference -> Opcodes.ALOAD
        type is PrimitiveType -> when (type.kind.numeric) {
            NumericKind.INT -> Opcodes.ILOAD
            NumericKind.LONG -> Opcodes.LLOAD
            NumericKind.FLOAT -> Opcodes.FLOAD
            NumericKind.DOUBLE -> Opcodes.DLOAD
            null -> throw TranslationException("cannot load a value of type ${type.descriptor}")
        }

        else -> Opcodes.ALOAD
    }

    private fun storeOpcode(type: VortexType): Int = when {
        type.isReference -> Opcodes.ASTORE
        type is PrimitiveType -> when (type.kind.numeric) {
            NumericKind.INT -> Opcodes.ISTORE
            NumericKind.LONG -> Opcodes.LSTORE
            NumericKind.FLOAT -> Opcodes.FSTORE
            NumericKind.DOUBLE -> Opcodes.DSTORE
            null -> throw TranslationException("cannot store a value of type ${type.descriptor}")
        }

        else -> Opcodes.ASTORE
    }

    private fun returnOpcode(type: VortexType): Int = when {
        type.isReference -> Opcodes.ARETURN
        type is PrimitiveType -> when (type.kind.numeric) {
            NumericKind.INT -> Opcodes.IRETURN
            NumericKind.LONG -> Opcodes.LRETURN
            NumericKind.FLOAT -> Opcodes.FRETURN
            NumericKind.DOUBLE -> Opcodes.DRETURN
            null -> Opcodes.RETURN
        }

        else -> Opcodes.ARETURN
    }

    private fun binaryOpcode(operation: BinaryOperation, operandType: VortexType): Int {
        val numeric = (operandType as? PrimitiveType)?.numeric
            ?: throw TranslationException("arithmetic on ${operandType.descriptor} is not a JVM operation")
        return when (numeric) {
            NumericKind.INT -> when (operation) {
                BinaryOperation.ADD -> Opcodes.IADD
                BinaryOperation.SUBTRACT -> Opcodes.ISUB
                BinaryOperation.MULTIPLY -> Opcodes.IMUL
                BinaryOperation.DIVIDE -> Opcodes.IDIV
                BinaryOperation.REMAINDER -> Opcodes.IREM
                BinaryOperation.AND -> Opcodes.IAND
                BinaryOperation.OR -> Opcodes.IOR
                BinaryOperation.XOR -> Opcodes.IXOR
                BinaryOperation.SHIFT_LEFT -> Opcodes.ISHL
                BinaryOperation.SHIFT_RIGHT -> Opcodes.ISHR
                BinaryOperation.UNSIGNED_SHIFT_RIGHT -> Opcodes.IUSHR
            }

            NumericKind.LONG -> when (operation) {
                BinaryOperation.ADD -> Opcodes.LADD
                BinaryOperation.SUBTRACT -> Opcodes.LSUB
                BinaryOperation.MULTIPLY -> Opcodes.LMUL
                BinaryOperation.DIVIDE -> Opcodes.LDIV
                BinaryOperation.REMAINDER -> Opcodes.LREM
                BinaryOperation.AND -> Opcodes.LAND
                BinaryOperation.OR -> Opcodes.LOR
                BinaryOperation.XOR -> Opcodes.LXOR
                BinaryOperation.SHIFT_LEFT -> Opcodes.LSHL
                BinaryOperation.SHIFT_RIGHT -> Opcodes.LSHR
                BinaryOperation.UNSIGNED_SHIFT_RIGHT -> Opcodes.LUSHR
            }

            NumericKind.FLOAT -> when (operation) {
                BinaryOperation.ADD -> Opcodes.FADD
                BinaryOperation.SUBTRACT -> Opcodes.FSUB
                BinaryOperation.MULTIPLY -> Opcodes.FMUL
                BinaryOperation.DIVIDE -> Opcodes.FDIV
                BinaryOperation.REMAINDER -> Opcodes.FREM
                else -> throw TranslationException("$operation is not defined for float")
            }

            NumericKind.DOUBLE -> when (operation) {
                BinaryOperation.ADD -> Opcodes.DADD
                BinaryOperation.SUBTRACT -> Opcodes.DSUB
                BinaryOperation.MULTIPLY -> Opcodes.DMUL
                BinaryOperation.DIVIDE -> Opcodes.DDIV
                BinaryOperation.REMAINDER -> Opcodes.DREM
                else -> throw TranslationException("$operation is not defined for double")
            }
        }
    }

    private fun negateOpcode(operandType: VortexType): Int {
        val numeric = (operandType as? PrimitiveType)?.numeric
            ?: throw TranslationException("negation of ${operandType.descriptor} is not a JVM operation")
        return when (numeric) {
            NumericKind.INT -> Opcodes.INEG
            NumericKind.LONG -> Opcodes.LNEG
            NumericKind.FLOAT -> Opcodes.FNEG
            NumericKind.DOUBLE -> Opcodes.DNEG
        }
    }

    private fun conversionOpcode(from: VortexType, to: VortexType): Int {
        val fromKind = (from as? PrimitiveType)?.kind ?: throw TranslationException("cannot convert ${from.descriptor}")
        val toKind = (to as? PrimitiveType)?.kind ?: throw TranslationException("cannot convert to ${to.descriptor}")
        // `boolean`/`byte`/`short`/`char` share the JVM's `int` family, so they convert as `int`.
        val source = if (fromKind.numeric == NumericKind.INT) PrimitiveKind.INT else fromKind
        return when (toKind) {
            PrimitiveKind.BYTE -> Opcodes.I2B
            PrimitiveKind.CHAR -> Opcodes.I2C
            PrimitiveKind.SHORT -> Opcodes.I2S
            else -> when (source) {
                PrimitiveKind.INT -> when (toKind) {
                    PrimitiveKind.LONG -> Opcodes.I2L
                    PrimitiveKind.FLOAT -> Opcodes.I2F
                    PrimitiveKind.DOUBLE -> Opcodes.I2D
                    else -> throw TranslationException("no conversion from int to ${to.descriptor}")
                }

                PrimitiveKind.LONG -> when (toKind) {
                    PrimitiveKind.INT -> Opcodes.L2I
                    PrimitiveKind.FLOAT -> Opcodes.L2F
                    PrimitiveKind.DOUBLE -> Opcodes.L2D
                    else -> throw TranslationException("no conversion from long to ${to.descriptor}")
                }

                PrimitiveKind.FLOAT -> when (toKind) {
                    PrimitiveKind.INT -> Opcodes.F2I
                    PrimitiveKind.LONG -> Opcodes.F2L
                    PrimitiveKind.DOUBLE -> Opcodes.F2D
                    else -> throw TranslationException("no conversion from float to ${to.descriptor}")
                }

                PrimitiveKind.DOUBLE -> when (toKind) {
                    PrimitiveKind.INT -> Opcodes.D2I
                    PrimitiveKind.LONG -> Opcodes.D2L
                    PrimitiveKind.FLOAT -> Opcodes.D2F
                    else -> throw TranslationException("no conversion from double to ${to.descriptor}")
                }

                else -> throw TranslationException("no conversion from ${from.descriptor} to ${to.descriptor}")
            }
        }
    }

    private fun compareOpcode(operation: CompareOperation): Int = when (operation) {
        CompareOperation.LONG -> Opcodes.LCMP
        CompareOperation.FLOAT_LOW -> Opcodes.FCMPL
        CompareOperation.FLOAT_HIGH -> Opcodes.FCMPG
        CompareOperation.DOUBLE_LOW -> Opcodes.DCMPL
        CompareOperation.DOUBLE_HIGH -> Opcodes.DCMPG
    }

    private fun integerJumpOpcode(comparison: Comparison): Int = when (comparison) {
        Comparison.EQUAL -> Opcodes.IF_ICMPEQ
        Comparison.NOT_EQUAL -> Opcodes.IF_ICMPNE
        Comparison.LESS_THAN -> Opcodes.IF_ICMPLT
        Comparison.GREATER_EQUAL -> Opcodes.IF_ICMPGE
        Comparison.GREATER_THAN -> Opcodes.IF_ICMPGT
        Comparison.LESS_EQUAL -> Opcodes.IF_ICMPLE
    }

    private fun arrayLoadOpcode(componentType: VortexType): Int = when (componentType) {
        is PrimitiveType -> when (componentType.kind) {
            PrimitiveKind.BOOLEAN, PrimitiveKind.BYTE -> Opcodes.BALOAD
            PrimitiveKind.CHAR -> Opcodes.CALOAD
            PrimitiveKind.SHORT -> Opcodes.SALOAD
            PrimitiveKind.INT -> Opcodes.IALOAD
            PrimitiveKind.LONG -> Opcodes.LALOAD
            PrimitiveKind.FLOAT -> Opcodes.FALOAD
            PrimitiveKind.DOUBLE -> Opcodes.DALOAD
            PrimitiveKind.VOID -> throw TranslationException("arrays of void do not exist")
        }

        else -> Opcodes.AALOAD
    }

    private fun arrayStoreOpcode(componentType: VortexType): Int = when (componentType) {
        is PrimitiveType -> when (componentType.kind) {
            PrimitiveKind.BOOLEAN, PrimitiveKind.BYTE -> Opcodes.BASTORE
            PrimitiveKind.CHAR -> Opcodes.CASTORE
            PrimitiveKind.SHORT -> Opcodes.SASTORE
            PrimitiveKind.INT -> Opcodes.IASTORE
            PrimitiveKind.LONG -> Opcodes.LASTORE
            PrimitiveKind.FLOAT -> Opcodes.FASTORE
            PrimitiveKind.DOUBLE -> Opcodes.DASTORE
            PrimitiveKind.VOID -> throw TranslationException("arrays of void do not exist")
        }

        else -> Opcodes.AASTORE
    }

    private fun invokeOpcode(invoke: LirInstruction.Invoke): Int = when (invoke.kind) {
        riad.codes.vortex.ir.InvokeKind.VIRTUAL -> Opcodes.INVOKEVIRTUAL
        riad.codes.vortex.ir.InvokeKind.INTERFACE -> Opcodes.INVOKEINTERFACE
        // `invoke-super` and `invoke-direct` both map to `invokespecial`: the JVM picks the
        // implementation, and the reference's owner already says whether it is a super call.
        riad.codes.vortex.ir.InvokeKind.SUPER, riad.codes.vortex.ir.InvokeKind.DIRECT -> Opcodes.INVOKESPECIAL
        riad.codes.vortex.ir.InvokeKind.STATIC -> Opcodes.INVOKESTATIC
    }

    private fun minimumLocals(method: LirMethod): Int {
        val receiver = if (AccessFlags.isStatic(method.accessFlags)) 0 else 1
        return receiver + method.reference.parameterSlots
    }
}
