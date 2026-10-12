package riad.codes.vortex.dex.testing

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.DexFile
import com.android.tools.smali.dexlib2.iface.ExceptionHandler
import com.android.tools.smali.dexlib2.iface.TryBlock
import com.android.tools.smali.dexlib2.iface.debug.DebugItem
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.debug.ImmutableLineNumber
import com.android.tools.smali.dexlib2.immutable.debug.ImmutableStartLocal
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import riad.codes.vortex.ir.AccessFlags
import java.nio.file.Path

/**
 * Builds real DEX files in memory for tests.
 *
 * The engine's most valuable tests need actual DEX bytes: a hand-built `dexlib2` model exercises the
 * parser, the CFG builder, the register type inference and the emitter in one go, and the result can
 * then be loaded by the JVM so its own verifier has the last word. This is also why the fixtures use
 * `dexlib2`'s immutable model — the same code path a real `classes.dex` takes.
 *
 * Code addresses in these fixtures are 16-bit **code units**, matching DEX.
 */
object DexFixtures {

    const val OBJECT = "Ljava/lang/Object;"
    const val SAMPLE_PACKAGE = "Lriad/codes/vortex/sample/"

    fun sampleClass(simpleName: String): String = "$SAMPLE_PACKAGE$simpleName;"

    /**
     * A DEX file holding [classes].
     *
     * The opcode table is the same modern one the engine decodes with, so a fixture can carry any
     * instruction a current toolchain emits; `dexlib2`'s own default is API 20 and refuses to *write*
     * opcodes such as `return-void-no-barrier` at all.
     */
    fun dex(vararg classes: ClassDef): DexFile = ImmutableDexFile(OPCODES, classes.toList())

    /** The opcode table fixtures are written with: see [riad.codes.vortex.dex.IngestionOptions.MODERN_OPCODES]. */
    val OPCODES: Opcodes = riad.codes.vortex.dex.IngestionOptions.MODERN_OPCODES

    fun write(path: Path, dexFile: DexFile) {
        DexFileFactory.writeDexFile(path.toString(), dexFile)
    }

    // ---------------------------------------------------------------------------------------------
    // references
    // ---------------------------------------------------------------------------------------------

    fun methodRef(
        owner: String,
        name: String,
        parameters: List<String> = emptyList(),
        returnType: String = "V",
    ) = ImmutableMethodReference(owner, name, parameters, returnType)

    fun fieldRef(owner: String, name: String, type: String) = ImmutableFieldReference(owner, name, type)

    fun typeRef(descriptor: String) = ImmutableTypeReference(descriptor)

    fun stringRef(value: String) = ImmutableStringReference(value)

    // ---------------------------------------------------------------------------------------------
    // declarations
    // ---------------------------------------------------------------------------------------------

    fun classDef(
        type: String,
        methods: List<ImmutableMethod> = emptyList(),
        fields: List<ImmutableField> = emptyList(),
        superType: String = OBJECT,
        accessFlags: Int = AccessFlags.PUBLIC,
        sourceFile: String? = "Fixture.java",
    ) = ImmutableClassDef(type, accessFlags, superType, emptyList(), sourceFile, emptyList(), fields, methods)

    fun method(
        owner: String,
        name: String,
        parameters: List<String> = emptyList(),
        returnType: String = "V",
        accessFlags: Int = AccessFlags.PUBLIC or AccessFlags.STATIC,
        implementation: ImmutableMethodImplementation? = null,
    ) = ImmutableMethod(
        owner,
        name,
        parameters.map { ImmutableMethodParameter(it, emptySet(), null) },
        returnType,
        accessFlags,
        emptySet(),
        emptySet(),
        implementation,
    )

    fun instanceMethod(
        owner: String,
        name: String,
        parameters: List<String> = emptyList(),
        returnType: String = "V",
        accessFlags: Int = AccessFlags.PUBLIC,
        implementation: ImmutableMethodImplementation? = null,
    ) = method(owner, name, parameters, returnType, accessFlags, implementation)

    fun field(
        owner: String,
        name: String,
        type: String,
        accessFlags: Int = AccessFlags.PUBLIC,
    ) = ImmutableField(owner, name, type, accessFlags, null, emptySet(), emptySet())

    fun implementation(
        registerCount: Int,
        instructions: List<Instruction>,
        tryBlocks: List<TryBlock<out ExceptionHandler>> = emptyList(),
        debugItems: List<DebugItem> = emptyList(),
    ): ImmutableMethodImplementation = ImmutableMethodImplementation(registerCount, instructions, tryBlocks, debugItems)

    fun lineNumber(codeAddress: Int, line: Int): DebugItem = ImmutableLineNumber(codeAddress, line)

    fun startLocal(codeAddress: Int, register: Int, name: String, type: String): DebugItem =
        ImmutableStartLocal(codeAddress, register, name, type, null)
}
