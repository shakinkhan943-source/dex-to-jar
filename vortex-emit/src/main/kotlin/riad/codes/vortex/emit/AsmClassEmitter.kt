package riad.codes.vortex.emit

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.util.CheckClassAdapter
import riad.codes.vortex.ir.AccessFlags
import riad.codes.vortex.ir.ClassHierarchy
import riad.codes.vortex.ir.HirClass
import riad.codes.vortex.ir.HirEncodedValue
import riad.codes.vortex.ir.HirField
import riad.codes.vortex.ir.HirMethod
import riad.codes.vortex.ir.LirClass
import riad.codes.vortex.ir.ObjectType

/** The bytes of one translated class, plus what happened to it. */
class EmittedClass(
    val internalName: String,
    val bytes: ByteArray,
    val methodCount: Int,
    /** Methods whose body could not be translated and were replaced by a throwing stub. */
    val stubbedMethods: List<String>,
    val verificationErrors: List<String>,
) {
    val isVerified: Boolean get() = verificationErrors.isEmpty()

    override fun toString(): String =
        "$internalName (${bytes.size} bytes, $methodCount methods, ${stubbedMethods.size} stubbed)"
}

/**
 * Emits a translated class as a JVM class file.
 *
 * Two details are worth calling out.
 *
 * **Fallback stubs.** A method whose body contains an instruction the engine cannot translate is not
 * emitted partially. A half-translated method silently computes the wrong thing, which is far worse
 * than a loud failure, so the body is replaced by one that throws `UnsupportedOperationException`
 * naming the cause, and the method is recorded in [EmittedClass.stubbedMethods] for the report. The
 * class still loads, the types still line up, and the gap is visible.
 *
 * **`ConstantValue`.** Only `static final` fields with a primitive or `String` initialiser get a
 * `ConstantValue` attribute — the class file format cannot store anything else. Everything else is
 * assigned by the DEX `<clinit>` that `dx`/`d8` already generated, which is translated like any other
 * method.
 */
class AsmClassEmitter(
    private val hierarchy: ClassHierarchy,
    private val options: EmitOptions = EmitOptions(),
    private val methodEmitter: AsmMethodEmitter = AsmMethodEmitter(options),
) {

    fun emit(lirClass: LirClass): EmittedClass {
        val declaration = lirClass.declaration
        val writer = DexAwareClassWriter(hierarchy)
        val cv: ClassVisitor = if (options.checkVisitorCalls || options.verifyBytecode) {
            CheckClassAdapter(writer, false)
        } else {
            writer
        }

        val access = AccessFlags.dexClassFlagsToJvm(declaration.accessFlags)
        val superName = (declaration.superType ?: ObjectType.OBJECT).internalName
        cv.visit(
            options.classFileVersion,
            access,
            declaration.type.internalName,
            null,
            superName,
            declaration.interfaces.map { it.internalName }.toTypedArray(),
        )
        declaration.sourceFile?.let { cv.visitSource(it, null) }
        AnnotationEmitter.emitAll(cv::visitAnnotation, declaration.annotations)

        declaration.fields.forEach { field -> emitField(cv, field) }

        val stubbed = ArrayList<String>()
        declaration.methods.forEach { method ->
            val analysed = lirClass.method(method.reference)
            when {
                analysed == null && hasNoBody(method) -> emitDeclarationPlusAnnotations(cv, method)

                analysed == null -> {
                    stubbed += "${method.reference} (no code item)"
                    emitStub(cv, declaration, method, "method body was not analysed")
                }

                analysed.summary.isFullyTranslated -> methodEmitter.emit(cv, analysed, method.annotations)

                else -> {
                    stubbed += method.reference.toString()
                    emitStub(cv, declaration, method, stubReason(analysed))
                }
            }
        }

        cv.visitEnd()
        val bytes = writer.toByteArray()
        val errors = if (options.verifyBytecode) {
            BytecodeVerifier.verify(bytes, declaration.type.internalName, hierarchy)
        } else {
            emptyList()
        }
        return EmittedClass(
            internalName = declaration.type.internalName,
            bytes = bytes,
            methodCount = declaration.methods.size,
            stubbedMethods = stubbed,
            verificationErrors = errors,
        )
    }

    /**
     * The reason a method could not be translated, naming the offending opcodes.
     *
     * The message ends up in the exception the stub throws, so it has to say *what* is missing: a
     * user reading a stack trace should not have to re-run the tool with a debug flag.
     */
    private fun stubReason(analysed: riad.codes.vortex.ir.LirMethod): String {
        val unsupported = analysed.instructions().filterIsInstance<riad.codes.vortex.ir.LirInstruction.Unsupported>().toList()
        val warnings = analysed.summary.warnings.joinToString("; ")
        if (unsupported.isEmpty()) {
            return warnings.ifEmpty { "method body was not analysed" }
        }
        val names = unsupported.map { it.opcodeName }.distinct().sorted().joinToString(", ")
        val at = unsupported.joinToString(", ") { "0x${it.address.toString(16)}" }
        return buildString {
            append("unsupported DEX instruction(s): ")
            append(names)
            append(" (at ")
            append(at)
            append(')')
            if (warnings.isNotEmpty()) append("; ").append(warnings)
        }
    }

    private fun hasNoBody(method: HirMethod): Boolean =
        method.body == null || AccessFlags.isAbstractOrNative(method.accessFlags)

    private fun emitDeclarationPlusAnnotations(cv: ClassVisitor, method: HirMethod) {
        val access = AccessFlags.dexMethodFlagsToJvm(method.accessFlags)
        val mv = cv.visitMethod(access, method.name, method.reference.descriptor, null, null)
        AnnotationEmitter.emitAll(mv::visitAnnotation, method.annotations)
        mv.visitEnd()
    }

    private fun emitStub(cv: ClassVisitor, declaration: HirClass, method: HirMethod, reason: String) {
        val access = AccessFlags.dexMethodFlagsToJvm(method.accessFlags)
        val mv = cv.visitMethod(access, method.name, method.reference.descriptor, null, null)
        AnnotationEmitter.emitAll(mv::visitAnnotation, method.annotations)

        if (AccessFlags.isAbstractOrNative(access)) {
            mv.visitEnd()
            return
        }

        mv.visitCode()
        val throwable = "java/lang/UnsupportedOperationException"
        mv.visitTypeInsn(Opcodes.NEW, throwable)
        mv.visitInsn(Opcodes.DUP)
        mv.visitLdcInsn("vortex-dex: untranslated ${declaration.type.internalName}.${method.name}: $reason")
        mv.visitMethodInsn(
            Opcodes.INVOKESPECIAL,
            throwable,
            "<init>",
            "(Ljava/lang/String;)V",
            false,
        )
        mv.visitInsn(Opcodes.ATHROW)
        val receiver = if (AccessFlags.isStatic(access)) 0 else 1
        mv.visitMaxs(3, receiver + method.reference.parameterSlots)
        mv.visitEnd()
    }

    private fun emitField(cv: ClassVisitor, field: HirField) {
        val access = AccessFlags.dexFieldFlagsToJvm(field.accessFlags)
        val fv = cv.visitField(access, field.name, field.type.descriptor, null, constantValue(field))
        AnnotationEmitter.emitAll(fv::visitAnnotation, field.annotations)
        fv.visitEnd()
    }

    private fun constantValue(field: HirField): Any? {
        if (!AccessFlags.isStatic(field.accessFlags) || !AccessFlags.isFinal(field.accessFlags)) return null
        return when (val value = field.initialValue) {
            is HirEncodedValue.IntValue -> value.value
            is HirEncodedValue.ByteValue -> value.value.toInt()
            is HirEncodedValue.ShortValue -> value.value.toInt()
            is HirEncodedValue.CharValue -> value.value.code
            is HirEncodedValue.BooleanValue -> if (value.value) 1 else 0
            is HirEncodedValue.LongValue -> value.value
            is HirEncodedValue.FloatValue -> value.value
            is HirEncodedValue.DoubleValue -> value.value
            is HirEncodedValue.StringValue -> value.value
            else -> null
        }
    }

}
