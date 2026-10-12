package riad.codes.vortex.emit

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableArrayPayload
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction12x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21ih
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21lh
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21s
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22b
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction23x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableSparseSwitchPayload
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableSwitchElement
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.LineNumberNode
import riad.codes.vortex.analysis.MethodAnalyzer
import riad.codes.vortex.dex.DexIngestion
import riad.codes.vortex.dex.DexLoader
import riad.codes.vortex.dex.DexToHir
import riad.codes.vortex.dex.testing.DexFixtures
import riad.codes.vortex.ir.AccessFlags
import riad.codes.vortex.ir.HirProgram
import riad.codes.vortex.ir.LirClass
import riad.codes.vortex.ir.ProgramSource
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The pipeline test that matters: build a real DEX, translate it, load the result into the JVM and
 * call it.
 *
 * A successful translation is not evidence by itself — the JVM's own verifier is. These tests define
 * the emitted classes through a `ClassLoader` and invoke them, so a wrong local slot, a missing
 * `StackMapTable` entry or an unbalanced operand stack fails here rather than in a user's
 * decompiler.
 */
class EndToEndTranslationTest {

    private val workDir: Path = Files.createTempDirectory("vortex-e2e")

    @Test
    fun `translates a static method and runs it on the jvm`() {
        val owner = DexFixtures.sampleClass("Math")
        val method = DexFixtures.method(
            owner = owner,
            name = "add",
            parameters = listOf("I", "I"),
            returnType = "I",
            implementation = DexFixtures.implementation(
                registerCount = 2,
                instructions = listOf(
                    ImmutableInstruction23x(Opcode.ADD_INT, 0, 0, 1),
                    ImmutableInstruction11x(Opcode.RETURN, 0),
                ),
            ),
        )

        val classes = translate("add.dex", DexFixtures.classDef(owner, methods = listOf(method)))
        val math = BytecodeVerifier.defineWithClassLoader(classes, "riad.codes.vortex.sample.Math")
        val add = math.getDeclaredMethod("add", INT, INT)

        add.invoke(null, 2, 3) shouldBe 5
        add.invoke(null, -40, 2) shouldBe -38
    }

    @Test
    fun `translates literals, branches and const into working bytecode`() {
        val owner = DexFixtures.sampleClass("Arithmetic")
        val method = DexFixtures.method(
            owner = owner,
            name = "scale",
            parameters = listOf("I", "I"),
            returnType = "I",
            implementation = DexFixtures.implementation(
                // v0 is a temporary, v1/v2 are the parameters (DEX puts arguments in the last registers).
                registerCount = 3,
                // Addresses are code units: 11n is 1 unit, 23x/22b/21t/21s are 2 each, so
                // const/4 is at 0, add-int at 1, mul-int/lit8 at 3, if-gtz at 5, const/16 at 7 and
                // return at 9. `if-gtz` therefore needs an offset of +4 to reach the return.
                instructions = listOf(
                    ImmutableInstruction11n(Opcode.CONST_4, 0, 5),
                    ImmutableInstruction23x(Opcode.ADD_INT, 0, 0, 1),
                    ImmutableInstruction22b(Opcode.MUL_INT_LIT8, 0, 0, 2),
                    ImmutableInstruction21t(Opcode.IF_GTZ, 0, 4),
                    ImmutableInstruction21s(Opcode.CONST_16, 0, 100),
                    ImmutableInstruction11x(Opcode.RETURN, 0),
                ),
            ),
        )

        val classes = translate("scale.dex", DexFixtures.classDef(owner, methods = listOf(method)))
        val type = BytecodeVerifier.defineWithClassLoader(classes, "riad.codes.vortex.sample.Arithmetic")
        val scale = type.getDeclaredMethod("scale", INT, INT)

        // (5 + 10) * 2 = 30, which is > 0, so the branch skips the 100 literal.
        scale.invoke(null, 10, 0) shouldBe 30
        // (5 - 10) * 2 = -10, so the literal overwrites the result.
        scale.invoke(null, -10, 0) shouldBe 100
    }

    @Test
    fun `translates fields, constructors and instance calls`() {
        val owner = DexFixtures.sampleClass("Counter")
        val valueField = DexFixtures.fieldRef(owner, "value", "I")

        val constructor = DexFixtures.method(
            owner = owner,
            name = "<init>",
            parameters = listOf("I"),
            returnType = "V",
            accessFlags = AccessFlags.PUBLIC or AccessFlags.CONSTRUCTOR,
            implementation = DexFixtures.implementation(
                registerCount = 2,
                instructions = listOf(
                    ImmutableInstruction35c(
                        Opcode.INVOKE_DIRECT,
                        1,
                        0,
                        0,
                        0,
                        0,
                        0,
                        DexFixtures.methodRef(DexFixtures.OBJECT, "<init>"),
                    ),
                    ImmutableInstruction22c(Opcode.IPUT, 1, 0, valueField),
                    ImmutableInstruction10x(Opcode.RETURN_VOID),
                ),
            ),
        )

        val doubled = DexFixtures.instanceMethod(
            owner = owner,
            name = "doubled",
            returnType = "I",
            implementation = DexFixtures.implementation(
                registerCount = 2,
                instructions = listOf(
                    ImmutableInstruction22c(Opcode.IGET, 0, 1, valueField),
                    ImmutableInstruction22b(Opcode.MUL_INT_LIT8, 0, 0, 2),
                    ImmutableInstruction11x(Opcode.RETURN, 0),
                ),
                debugItems = listOf(
                    DexFixtures.lineNumber(0, 12),
                    DexFixtures.startLocal(0, 1, "this", owner),
                ),
            ),
        )

        val classes = translate(
            "counter.dex",
            DexFixtures.classDef(
                type = owner,
                methods = listOf(constructor, doubled),
                fields = listOf(DexFixtures.field(owner, "value", "I", AccessFlags.PRIVATE)),
            ),
        )

        val type = BytecodeVerifier.defineWithClassLoader(classes, "riad.codes.vortex.sample.Counter")
        val instance = type.getConstructor(INT).newInstance(21)
        type.getMethod("doubled").invoke(instance) shouldBe 42

        // Debug information survived: line numbers and a named local for the receiver.
        val node = ClassNode().also {
            ClassReader(classes.getValue("riad/codes/vortex/sample/Counter")).accept(it, 0)
        }
        val method = node.methods.first { it.name == "doubled" }
        // ASM's tree API keeps line numbers as pseudo-instructions in the instruction list.
        method.instructions.toArray().filterIsInstance<LineNumberNode>().map { it.line } shouldContain 12
        method.localVariables.map { it.name } shouldContain "this"
    }

    @Test
    fun `reports an untranslatable method instead of emitting wrong code`() {
        val owner = DexFixtures.sampleClass("Broken")
        val classDef = DexFixtures.classDef(owner, methods = listOf(untranslatableMethod(owner, "make")))

        val lirClass = analyseOnly("broken.dex", classDef)
        val lir = lirClass.methods.single()
        lir.summary.unsupportedInstructions shouldBe 1
        lir.summary.isFullyTranslated shouldBe false

        val classes = translate("broken.dex", classDef)
        val type = BytecodeVerifier.defineWithClassLoader(classes, "riad.codes.vortex.sample.Broken")
        val error = assertThrows<InvocationTargetException> { type.getMethod("make").invoke(null) }
        error.targetException.shouldBeInstanceOf<UnsupportedOperationException>()
        error.targetException.message shouldContain "return-void-no-barrier"
    }

    /**
     * The DEX-only idioms a real toolchain emits and that a decompiler-adjacent engine gets wrong if it
     * guesses: array literals in both of their DEX spellings, a `sparse-switch` whose default arm is the
     * fall-through that follows it, and floating-point constants, whose payload is a `float` or a
     * `double` depending on the register it lands in.
     *
     * Every one of these produced bytecode that either failed the JVM's verifier or ran down the wrong
     * branch, and no unit test of a single pass can see that: the question is what the *JVM* does with
     * the class, which is why these are asserted through a real invocation.
     */
    @Test
    fun `translates array literals, switch defaults and floating point constants`() {
        val owner = DexFixtures.sampleClass("Idioms")
        val classes = translate(
            "idioms.dex",
            DexFixtures.classDef(owner, methods = listOf(arrayFromPayload(owner), arrayFromRegisters(owner), sparseSwitch(owner), twiceFloat(owner), plusHalf(owner))),
        )
        val idioms = BytecodeVerifier.defineWithClassLoader(classes, "riad.codes.vortex.sample.Idioms")

        // `new-array` + a `fill-array-data` payload: {10, 20, 30}, element 2.
        idioms.getMethod("fromPayload").invoke(null) shouldBe 30
        // `filled-new-array {7, 8}`: element 1.
        idioms.getMethod("fromRegisters").invoke(null) shouldBe 8

        // The matching arms, and the default arm, which is the instruction after the switch.
        idioms.getMethod("sparseSwitch", INT).invoke(null, 1) shouldBe 10
        idioms.getMethod("sparseSwitch", INT).invoke(null, 2) shouldBe 20
        idioms.getMethod("sparseSwitch", INT).invoke(null, 7) shouldBe 70
        idioms.getMethod("sparseSwitch", INT).invoke(null, 9) shouldBe -1

        // Raw constant payloads, read as the type of the register they were loaded into.
        idioms.getMethod("twice", FLOAT).invoke(null, 4.0f) shouldBe 10.0f
        idioms.getMethod("plusHalf", DOUBLE).invoke(null, 1.0) shouldBe 3.5
    }

    @Test
    fun `emits a verifiable stub for an untranslatable constructor`() {
        val owner = DexFixtures.sampleClass("BrokenCtor")
        val constructor = DexFixtures.method(
            owner = owner,
            name = "<init>",
            returnType = "V",
            accessFlags = AccessFlags.PUBLIC or AccessFlags.CONSTRUCTOR,
            implementation = DexFixtures.implementation(
                registerCount = 1,
                instructions = listOf(
                    untranslatableInstruction(),
                    ImmutableInstruction10x(Opcode.RETURN_VOID),
                ),
            ),
        )

        // Loading the class runs the JVM verifier over the stub, and instantiating it proves the stub
        // is reachable and throws instead of being rejected as unverifiable bytecode.
        val classes = translate("broken-ctor.dex", DexFixtures.classDef(owner, methods = listOf(constructor)))
        val type = BytecodeVerifier.defineWithClassLoader(classes, "riad.codes.vortex.sample.BrokenCtor")
        val error = assertThrows<InvocationTargetException> { type.getDeclaredConstructor().newInstance() }
        error.targetException.shouldBeInstanceOf<UnsupportedOperationException>()
    }

    // ---------------------------------------------------------------------------------------------

    private fun untranslatableMethod(owner: String, name: String) = DexFixtures.method(
        owner = owner,
        name = name,
        returnType = "V",
        implementation = DexFixtures.implementation(
            registerCount = 1,
            instructions = listOf(
                untranslatableInstruction(),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            ),
        ),
    )

    /**
     * An instruction the decoder recognises but the engine deliberately does not translate.
     *
     * `return-void-no-barrier` is an ART bookkeeping instruction with no JVM counterpart, which makes it
     * a stable choice for exercising the "stub it and report it" path.
     */
    private fun untranslatableInstruction(): Instruction =
        ImmutableInstruction10x(Opcode.RETURN_VOID_NO_BARRIER)

    private fun translate(dexFileName: String, vararg classes: ClassDef): Map<String, ByteArray> {
        val program = loadProgram(dexFileName, *classes)
        val analyzer = MethodAnalyzer(program)
        val emitter = AsmClassEmitter(program.hierarchy, EmitOptions(verifyBytecode = true))
        return program.classes.associate { hirClass ->
            val emitted = emitter.emit(analyzer.analyzeClass(hirClass))
            check(emitted.verificationErrors.isEmpty()) {
                "ASM verification failed for ${hirClass.type.internalName}: ${emitted.verificationErrors}"
            }
            hirClass.type.internalName to emitted.bytes
        }
    }

    private fun analyseOnly(dexFileName: String, vararg classes: ClassDef): LirClass {
        val program = loadProgram(dexFileName, *classes)
        return MethodAnalyzer(program).analyzeClass(program.classes.single())
    }

    private fun loadProgram(dexFileName: String, vararg classes: ClassDef): HirProgram {
        val path = workDir.resolve(dexFileName)
        DexFixtures.write(path, DexFixtures.dex(*classes))
        val input = DexIngestion().discoverBlocking(path)
        val entry = input.entries.first()
        val dexFile = DexLoader().load(input, entry)
        val header = requireNotNull(input.header) { "expected a raw DEX file at $path" }
        val program = DexToHir().convert(
            dexFile,
            ProgramSource(
                archiveName = input.sourceName,
                dexEntryName = entry.name,
                dexVersion = header.version,
                checksum = header.checksum,
                classCount = 0,
            ),
        )
        return program
    }

    // ---------------------------------------------------------------------------------------------
    // fixtures for the DEX-only idioms
    // ---------------------------------------------------------------------------------------------

    /**
     * `int[] a = {10, 20, 30}; return a[2]`.
     *
     * Code addresses are DEX code units, and the payload must start at an even address, so the array
     * data lands at 10 and `fill-array-data` at 3 points 7 units forward.
     */
    private fun arrayFromPayload(owner: String) = DexFixtures.method(
        owner = owner,
        name = "fromPayload",
        returnType = "I",
        implementation = DexFixtures.implementation(
            registerCount = 2,
            instructions = listOf(
                ImmutableInstruction11n(Opcode.CONST_4, 0, 3), // v0 = length
                ImmutableInstruction22c(Opcode.NEW_ARRAY, 1, 0, DexFixtures.typeRef("[I")), // v1 = new int[3]
                ImmutableInstruction31t(Opcode.FILL_ARRAY_DATA, 1, 7), // -> payload at 0x0a
                ImmutableInstruction11n(Opcode.CONST_4, 0, 2), // v0 = index
                ImmutableInstruction23x(Opcode.AGET, 0, 1, 0), // v0 = v1[v0]
                ImmutableInstruction11x(Opcode.RETURN, 0),
                ImmutableArrayPayload(4, listOf<Number>(10, 20, 30)),
            ),
        ),
    )

    /** `int[] a = {7, 8}; return a[1]`, allocated from registers instead of from a payload. */
    private fun arrayFromRegisters(owner: String) = DexFixtures.method(
        owner = owner,
        name = "fromRegisters",
        returnType = "I",
        implementation = DexFixtures.implementation(
            registerCount = 3,
            instructions = listOf(
                ImmutableInstruction11n(Opcode.CONST_4, 0, 7),
                // `const/4` only reaches 7, so the second element needs the 16-bit form.
                ImmutableInstruction21s(Opcode.CONST_16, 1, 8),
                ImmutableInstruction11n(Opcode.CONST_4, 2, 1), // index
                ImmutableInstruction35c(Opcode.FILLED_NEW_ARRAY, 2, 0, 1, 0, 0, 0, DexFixtures.typeRef("[I")),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
                ImmutableInstruction23x(Opcode.AGET, 0, 0, 2),
                ImmutableInstruction11x(Opcode.RETURN, 0),
            ),
        ),
    )

    /**
     * `switch (i) { case 1: 10; case 2: 20; case 7: 70; default: -1; }`.
     *
     * The switch sits at address 0, so the payload's per-case offsets are also absolute addresses: case
     * 1 jumps to 0x0b, case 2 to 0x08, case 7 to 0x05, and an unmatched value falls through to the
     * `const/4 v0, #-1` at 0x03.
     */
    private fun sparseSwitch(owner: String) = DexFixtures.method(
        owner = owner,
        name = "sparseSwitch",
        parameters = listOf("I"),
        returnType = "I",
        implementation = DexFixtures.implementation(
            registerCount = 2,
            instructions = listOf(
                ImmutableInstruction31t(Opcode.SPARSE_SWITCH, 1, 14),
                ImmutableInstruction11n(Opcode.CONST_4, 0, -1),
                ImmutableInstruction11x(Opcode.RETURN, 0),
                ImmutableInstruction21s(Opcode.CONST_16, 0, 70),
                ImmutableInstruction11x(Opcode.RETURN, 0),
                ImmutableInstruction21s(Opcode.CONST_16, 0, 20),
                ImmutableInstruction11x(Opcode.RETURN, 0),
                ImmutableInstruction21s(Opcode.CONST_16, 0, 10),
                ImmutableInstruction11x(Opcode.RETURN, 0),
                ImmutableSparseSwitchPayload(
                    listOf(
                        ImmutableSwitchElement(1, 11),
                        ImmutableSwitchElement(2, 8),
                        ImmutableSwitchElement(7, 5),
                    ),
                ),
            ),
        ),
    )

    /** `float twice(float x) { return x * 2.5f; }` — `0x40200000` is the `float` 2.5f. */
    private fun twiceFloat(owner: String) = DexFixtures.method(
        owner = owner,
        name = "twice",
        parameters = listOf("F"),
        returnType = "F",
        implementation = DexFixtures.implementation(
            registerCount = 2,
            instructions = listOf(
                // `const/high16` is the 32-bit `21ih` form; the literal is the full value whose low 16
                // bits are zero.
                ImmutableInstruction21ih(Opcode.CONST_HIGH16, 0, 0x40200000),
                ImmutableInstruction12x(Opcode.MUL_FLOAT_2ADDR, 1, 0),
                ImmutableInstruction11x(Opcode.RETURN, 1),
            ),
        ),
    )

    /** `double plusHalf(double x) { return x + 2.5d; }` — the same payload, read as a `double`. */
    private fun plusHalf(owner: String) = DexFixtures.method(
        owner = owner,
        name = "plusHalf",
        parameters = listOf("D"),
        returnType = "D",
        implementation = DexFixtures.implementation(
            registerCount = 3,
            instructions = listOf(
                ImmutableInstruction21lh(Opcode.CONST_WIDE_HIGH16, 0, 0x4004000000000000L),
                ImmutableInstruction12x(Opcode.ADD_DOUBLE_2ADDR, 1, 0),
                ImmutableInstruction11x(Opcode.RETURN_WIDE, 1),
            ),
        ),
    )

    private companion object {
        val INT: Class<*> = Int::class.javaPrimitiveType!!
        val FLOAT: Class<*> = Float::class.javaPrimitiveType!!
        val DOUBLE: Class<*> = Double::class.javaPrimitiveType!!
    }
}
