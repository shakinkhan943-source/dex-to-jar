package riad.codes.vortex.dex

import com.android.tools.smali.dexlib2.iface.Annotation
import com.android.tools.smali.dexlib2.iface.AnnotationElement
import com.android.tools.smali.dexlib2.iface.BasicAnnotation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.DexFile
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.MethodImplementation
import com.android.tools.smali.dexlib2.iface.TryBlock
import com.android.tools.smali.dexlib2.iface.debug.DebugItem
import com.android.tools.smali.dexlib2.iface.debug.LineNumber
import com.android.tools.smali.dexlib2.iface.debug.StartLocal
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.value.AnnotationEncodedValue
import com.android.tools.smali.dexlib2.iface.value.ArrayEncodedValue
import com.android.tools.smali.dexlib2.iface.value.BooleanEncodedValue
import com.android.tools.smali.dexlib2.iface.value.ByteEncodedValue
import com.android.tools.smali.dexlib2.iface.value.CharEncodedValue
import com.android.tools.smali.dexlib2.iface.value.DoubleEncodedValue
import com.android.tools.smali.dexlib2.iface.value.EncodedValue
import com.android.tools.smali.dexlib2.iface.value.EnumEncodedValue
import com.android.tools.smali.dexlib2.iface.value.FloatEncodedValue
import com.android.tools.smali.dexlib2.iface.value.IntEncodedValue
import com.android.tools.smali.dexlib2.iface.value.LongEncodedValue
import com.android.tools.smali.dexlib2.iface.value.MethodTypeEncodedValue
import com.android.tools.smali.dexlib2.iface.value.NullEncodedValue
import com.android.tools.smali.dexlib2.iface.value.ShortEncodedValue
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue
import com.android.tools.smali.dexlib2.iface.value.TypeEncodedValue
import riad.codes.vortex.ir.AccessFlags
import riad.codes.vortex.ir.AnnotationVisibility
import riad.codes.vortex.ir.DexFormatException
import riad.codes.vortex.ir.HirAnnotation
import riad.codes.vortex.ir.HirAnnotationElement
import riad.codes.vortex.ir.HirClass
import riad.codes.vortex.ir.HirEncodedValue
import riad.codes.vortex.ir.HirExceptionHandler
import riad.codes.vortex.ir.HirField
import riad.codes.vortex.ir.HirInstruction
import riad.codes.vortex.ir.HirMethod
import riad.codes.vortex.ir.HirMethodBody
import riad.codes.vortex.ir.HirParameter
import riad.codes.vortex.ir.HirProgram
import riad.codes.vortex.ir.HirTryBlock
import riad.codes.vortex.ir.HirUnsupported
import riad.codes.vortex.ir.ObjectType
import riad.codes.vortex.ir.ProgramSource
import riad.codes.vortex.ir.TypeDescriptors

/** Knobs for the DEX to HIR conversion. */
data class DexToHirOptions(
    /** Keep line numbers and local variable names from DEX debug info. */
    val includeDebugInfo: Boolean = true,

    /**
     * Cross-check every debug/try/switch address against the instruction address map.
     *
     * DEX addresses are 16-bit code units and payload pseudo-instructions occupy real code units, so
     * a mismatch means the address model is wrong. Failing loudly is the whole point: a wrong
     * address model produces bytecode that runs down the wrong branch.
     */
    val validateAddresses: Boolean = true,
)

/**
 * Converts `dexlib2`'s view of a DEX file into the engine's high-level IR.
 *
 * The converter deliberately consumes the `dexlib2` *interfaces* (`DexFile`, `ClassDef`, `Method`,
 * …) rather than the `DexBacked*` classes: the same code path therefore works for files read from an
 * APK, for memory-mapped raw DEX files, and for the immutable in-memory DEX files the test suite
 * builds to exercise the pipeline end to end.
 */
class DexToHir(
    private val options: DexToHirOptions = DexToHirOptions(),
) {
    private val resolver = DexReferenceResolver()
    private val decoder = DexInstructionDecoder(resolver)

    fun convert(dexFile: DexFile, source: ProgramSource, dexIndex: Int = source.dexIndex): HirProgram {
        val classes = dexFile.classes.map { convertClass(it, dexIndex) }
        return HirProgram(classes = classes, sources = listOf(source.copy(classCount = classes.size)))
    }

    private fun convertClass(classDef: ClassDef, dexIndex: Int): HirClass {
        val type = resolver.objectType(classDef.type)
        val superType = classDef.superclass?.let { resolver.objectType(it) }
        return HirClass(
            type = type,
            accessFlags = classDef.accessFlags,
            superType = superType,
            interfaces = classDef.interfaces.map { resolver.objectType(it) },
            sourceFile = classDef.sourceFile,
            annotations = convertAnnotations(classDef.annotations),
            fields = classDef.fields.map { convertField(it, type) },
            methods = classDef.methods.map { convertMethod(it, type, dexIndex) },
            dexIndex = dexIndex,
        )
    }

    private fun convertField(field: Field, definingClass: ObjectType): HirField = HirField(
        definingClass = definingClass,
        name = field.name,
        type = resolver.type(field.type),
        accessFlags = field.accessFlags,
        initialValue = field.initialValue?.let { convertEncodedValue(it) },
        annotations = convertAnnotations(field.annotations),
    )

    private fun convertMethod(method: Method, definingClass: ObjectType, dexIndex: Int): HirMethod {
        val returnType = resolver.type(method.returnType)
        val parameters = method.parameterTypes.map { HirParameter(resolver.type(it.toString()), null) }
        val body = method.implementation?.let { convertBody(it, method, dexIndex) }
        return HirMethod(
            definingClass = definingClass,
            name = method.name,
            parameters = if (body == null) parameters else bindParameterNames(method, parameters, body),
            returnType = returnType,
            accessFlags = method.accessFlags,
            annotations = convertAnnotations(method.annotations),
            body = body,
            dexIndex = dexIndex,
        )
    }

    /**
     * Binds DEX debug-info names to parameters.
     *
     * Parameters live in the *last* registers of a method (`registers_size - ins_size` and up, with
     * the receiver first for instance methods), and the debug program names them with `StartLocal`
     * items. Recovering them here is what lets the emitter produce a `LocalVariableTable` that a
     * decompiler can turn back into readable parameter names.
     */
    private fun bindParameterNames(
        method: Method,
        parameters: List<HirParameter>,
        body: HirMethodBody,
    ): List<HirParameter> {
        if (body.registerNames.isEmpty()) return parameters
        var register = body.parameterRegisterBase
        if (!AccessFlags.isStatic(method.accessFlags)) register += 1
        return parameters.map { parameter ->
            val name = body.registerNames[register]
            register += parameter.type.width
            if (name == null) parameter else parameter.copy(name = name)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // method bodies
    // ---------------------------------------------------------------------------------------------

    private fun convertBody(
        implementation: MethodImplementation,
        method: Method,
        dexIndex: Int,
    ): HirMethodBody {
        val instructions = implementation.instructions.toList()
        val addresses = IntArray(instructions.size)
        var cursor = 0
        instructions.forEachIndexed { index, instruction ->
            addresses[index] = cursor
            cursor += instruction.codeUnits
        }
        val codeSize = cursor
        val byAddress = HashMap<Int, Instruction>(instructions.size * 2)
        instructions.forEachIndexed { index, instruction -> byAddress[addresses[index]] = instruction }

        val debugItems: List<DebugItem> = if (options.includeDebugInfo) implementation.debugItems.toList() else emptyList()
        val lineNumbers = LinkedHashMap<Int, Int>()
        val registerNames = LinkedHashMap<Int, String>()
        debugItems.forEach { item ->
            when (item) {
                is LineNumber -> lineNumbers[item.codeAddress] = item.lineNumber
                is StartLocal -> {
                    val name = item.name
                    if (name != null) registerNames.putIfAbsent(item.register, name)
                }

                else -> Unit
            }
        }

        val tryBlocks = implementation.tryBlocks.map { convertTryBlock(it) }
        if (options.validateAddresses) {
            validateAddresses(method, byAddress.keys, codeSize, lineNumbers.keys, tryBlocks)
        }

        val payloads = PayloadResolver { address -> byAddress[address] }
        val decoded = ArrayList<HirInstruction>(instructions.size)
        instructions.forEachIndexed { index, instruction ->
            // Switch/array payloads are data, not code: they occupy code units but have no translation.
            if (instruction is SwitchPayload || isArrayPayload(instruction)) return@forEachIndexed
            try {
                decoded += decoder.decode(instruction, addresses[index], payloads)
            } catch (error: DexFormatException) {
                throw DexFormatException(
                    "while decoding ${method.definingClass}->${method.name}${TypeDescriptors.parseMethod(describe(method)).descriptor} " +
                        "at code unit ${addresses[index]}: ${error.message}",
                    error,
                )
            }
        }

        val parameterRegisterCount = method.parameterTypes.sumOf { resolver.type(it.toString()).width } +
            if (AccessFlags.isStatic(method.accessFlags)) 0 else 1

        val body = HirMethodBody(
            registerCount = implementation.registerCount,
            parameterRegisterCount = parameterRegisterCount,
            instructions = decoded,
            tryBlocks = tryBlocks,
            lineNumbers = lineNumbers,
            registerNames = registerNames,
            codeItemOffset = -1,
        )
        return body
    }

    private fun describe(method: Method): String = buildString {
        append('(')
        method.parameterTypes.forEach { append(it) }
        append(')')
        append(method.returnType)
    }

    private fun isArrayPayload(instruction: Instruction): Boolean =
        instruction.opcode == com.android.tools.smali.dexlib2.Opcode.ARRAY_PAYLOAD

    private fun convertTryBlock(tryBlock: TryBlock<*>): HirTryBlock = HirTryBlock(
        startAddress = tryBlock.startCodeAddress,
        endAddress = tryBlock.startCodeAddress + tryBlock.codeUnitCount,
        handlers = tryBlock.exceptionHandlers.map {
            HirExceptionHandler(
                exceptionType = it.exceptionType?.let { descriptor -> resolver.type(descriptor) },
                handlerAddress = it.handlerCodeAddress,
            )
        },
    )

    /**
     * Verifies that every address that must name an instruction boundary really does.
     *
     * If this trips, the address model (code units, and whether payloads are part of the instruction
     * list) is wrong for this file, and every branch target computed from it would be wrong too.
     */
    private fun validateAddresses(
        method: Method,
        instructionAddresses: Set<Int>,
        codeSize: Int,
        lineNumberAddresses: Set<Int>,
        tryBlocks: List<HirTryBlock>,
    ) {
        val unknown = LinkedHashSet<Int>()
        lineNumberAddresses.filterTo(unknown) { it !in instructionAddresses }
        tryBlocks.forEach { block ->
            if (block.startAddress !in instructionAddresses) unknown += block.startAddress
            if (block.endAddress != codeSize && block.endAddress !in instructionAddresses) unknown += block.endAddress
            block.handlers.forEach { handler ->
                if (handler.handlerAddress !in instructionAddresses) unknown += handler.handlerAddress
            }
        }
        if (unknown.isNotEmpty()) {
            throw DexFormatException(
                "${method.definingClass}->${method.name} references ${unknown.size} code address(es) that are not " +
                    "instruction boundaries: ${unknown.take(8).joinToString()} (code size $codeSize)",
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // annotations and encoded values
    // ---------------------------------------------------------------------------------------------

    private fun convertAnnotations(annotations: Set<out Annotation>): List<HirAnnotation> =
        annotations.map { convertAnnotation(it, it.visibility) }

    /**
     * [BasicAnnotation] is the common supertype of a declaration's annotation and of a nested
     * `AnnotationEncodedValue`; only the former carries a visibility, which is why it is a parameter.
     */
    private fun convertAnnotation(annotation: BasicAnnotation, visibility: Int): HirAnnotation = HirAnnotation(
        type = resolver.type(annotation.type),
        visibility = visibility,
        elements = annotation.elements.map { convertElement(it) },
    )

    private fun convertElement(element: AnnotationElement): HirAnnotationElement =
        HirAnnotationElement(element.name, convertEncodedValue(element.value))

    /**
     * Maps a DEX encoded value onto the subset of the JVM annotation constant pool that can hold it.
     *
     * Field and method constants have no class-file equivalent inside annotations; they are
     * represented as `null` and dropped by the emitter, which is preferable to crashing on an
     * annotation the JVM could never store anyway.
     */
    private fun convertEncodedValue(value: EncodedValue): HirEncodedValue = when (value) {
        is IntEncodedValue -> HirEncodedValue.IntValue(value.value)
        is ByteEncodedValue -> HirEncodedValue.ByteValue(value.value)
        is ShortEncodedValue -> HirEncodedValue.ShortValue(value.value)
        is CharEncodedValue -> HirEncodedValue.CharValue(value.value)
        is LongEncodedValue -> HirEncodedValue.LongValue(value.value)
        is FloatEncodedValue -> HirEncodedValue.FloatValue(value.value)
        is DoubleEncodedValue -> HirEncodedValue.DoubleValue(value.value)
        is BooleanEncodedValue -> HirEncodedValue.BooleanValue(value.value)
        is StringEncodedValue -> HirEncodedValue.StringValue(value.value)
        is TypeEncodedValue -> HirEncodedValue.TypeValue(resolver.type(value.value))
        is NullEncodedValue -> HirEncodedValue.NullValue
        is EnumEncodedValue -> HirEncodedValue.EnumValue(resolver.field(value.value))
        is ArrayEncodedValue -> HirEncodedValue.ArrayValue(value.value.map { convertEncodedValue(it) })
        // A nested annotation has no visibility of its own; the emitter treats it as nested data.
        is AnnotationEncodedValue ->
            HirEncodedValue.AnnotationValue(convertAnnotation(value, AnnotationVisibility.RUNTIME))
        is MethodTypeEncodedValue -> HirEncodedValue.MethodTypeValue(resolver.methodType(value.value))
        else -> throw DexFormatException("unsupported encoded value type 0x${value.valueType.toString(16)}")
    }
}
