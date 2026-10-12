package riad.codes.vortex.engine

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22b
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction23x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import riad.codes.vortex.dex.testing.DexFixtures
import riad.codes.vortex.ir.AccessFlags
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Tests the engine the way a user meets it: a file in, a jar out, loaded back through a class loader.
 *
 * The unit tests in the other modules pin the pieces; these pin the contract that matters, which is
 * that the jar on disk is a superset of the input that the JVM accepts and runs — including when a
 * plugin rewrote every name in it.
 */
class TranslationEngineTest {

    private val workDir: Path = Files.createTempDirectory("vortex-engine")

    @Test
    fun `writes a runnable jar and reports a clean translation`() {
        val owner = DexFixtures.sampleClass("Math")
        val dex = dexFile(
            "simple.dex",
            mathClass(owner),
        )
        val output = workDir.resolve("simple.jar")

        val report = TranslationEngine().translateBlocking(TranslationRequest(dex, output))

        report.isFullyTranslated shouldBe true
        report.isVerified shouldBe true
        report.emittedClassCount shouldBe 1
        report.methodCount shouldBe 1
        report.jarSizeBytes shouldBe Files.size(output)

        JarFile(output.toFile()).use { jar ->
            requireNotNull(jar.getEntry("riad/codes/vortex/sample/Math.class")) { "class entry missing from the jar" }
            requireNotNull(jar.getEntry(JarFile.MANIFEST_NAME)) { "manifest missing from the jar" }
        }

        withLoader(output) { loader ->
            val math = loader.loadClass("riad.codes.vortex.sample.Math")
            math.getMethod("add", INT, INT).invoke(null, 20, 22) shouldBe 42
        }
    }

    @Test
    fun `applies the obfuscation stripper and the renamed jar still runs`() {
        val dex = dexFile(
            "obfuscated.dex",
            obfuscatedHolder(),
            obfuscatedData(),
        )
        val output = workDir.resolve("obfuscated.jar")

        val report = TranslationEngine().translateBlocking(
            TranslationRequest(
                input = dex,
                output = output,
                options = TranslationOptions(plugins = listOf("obfuscation-stripper")),
            ),
        )

        report.isFullyTranslated shouldBe true
        report.classes.map { it.name }.sorted() shouldBe listOf("VortexClass1", "VortexClass2")
        report.classes.mapNotNull { it.originalName }.sorted() shouldBe listOf("a", "b")
        report.pluginNotes.single() shouldContain "obfuscation-stripper"
        report.pluginNotes.single() shouldContain "VortexClass"

        // The originals must be gone from the jar, and the jar must hold the renamed classes only.
        JarFile(output.toFile()).use { jar ->
            jar.entries().asSequence().map { it.name }.filter { it.endsWith(".class") }.toList().sorted() shouldBe
                listOf("VortexClass1.class", "VortexClass2.class")
        }

        withLoader(output) { loader ->
            // `a()`, a private obfuscated static method, was renamed; its caller was rewritten with it.
            loader.loadClass("VortexClass1").getMethod("compute").invoke(null) shouldBe 8

            // The private obfuscated field `c` was renamed and both `iput` and `iget` followed.
            val data = loader.loadClass("VortexClass2").getDeclaredConstructor().newInstance()
            loader.loadClass("VortexClass2").getMethod("getC").invoke(data) shouldBe 5
        }

        // Names are stable across runs: the same input must produce the same plan.
        val second = workDir.resolve("obfuscated-again.jar")
        TranslationEngine().translateBlocking(
            TranslationRequest(dex, second, TranslationOptions(plugins = listOf("obfuscation-stripper"))),
        )
        Files.readAllBytes(second).toList() shouldBe Files.readAllBytes(output).toList()
    }

    @Test
    fun `merges a multi-dex archive and reports duplicated classes`() {
        val first = DexFixtures.sampleClass("First")
        val second = DexFixtures.sampleClass("Second")
        val archive = workDir.resolve("multi.zip")

        val classesDex = workDir.resolve("classes.dex")
        val classes2Dex = workDir.resolve("classes2.dex")
        DexFixtures.write(classesDex, DexFixtures.dex(mathClass(first, methodName = "one", result = 1)))
        DexFixtures.write(
            classes2Dex,
            DexFixtures.dex(
                mathClass(second, methodName = "two", result = 2),
                // The same class in two DEX files is a real repackaging pattern; the first wins.
                mathClass(first, methodName = "one", result = 1),
            ),
        )
        zip(archive, "classes.dex" to classesDex, "classes2.dex" to classes2Dex)

        val output = workDir.resolve("multi.jar")
        val report = TranslationEngine().translateBlocking(TranslationRequest(archive, output))

        report.dexEntries shouldBe listOf("classes.dex", "classes2.dex")
        report.emittedClassCount shouldBe 2
        report.duplicates.shouldHaveSize(1)
        report.duplicates.single() shouldContain "First"
        report.warnings.any { it.contains("more than one DEX entry") } shouldBe true
        report.isFullyTranslated shouldBe true

        withLoader(output) { loader ->
            loader.loadClass("riad.codes.vortex.sample.First").getMethod("one").invoke(null) shouldBe 1
            loader.loadClass("riad.codes.vortex.sample.Second").getMethod("two").invoke(null) shouldBe 2
        }
    }

    @Test
    fun `keeps an untranslatable method as a reported stub`() {
        val owner = DexFixtures.sampleClass("Broken")
        val dex = dexFile("broken.dex", DexFixtures.classDef(owner, methods = listOf(untranslatable(owner))))
        val output = workDir.resolve("broken.jar")

        val report = TranslationEngine().translateBlocking(TranslationRequest(dex, output))

        report.stubbedMethods shouldHaveSize 1
        report.isFullyTranslated shouldBe false
        report.failedClasses.shouldHaveSize(0)

        withLoader(output) { loader ->
            val type = loader.loadClass("riad.codes.vortex.sample.Broken")
            val error = assertThrows<InvocationTargetException> { type.getMethod("make").invoke(null) }
            error.targetException.shouldBeInstanceOf<UnsupportedOperationException>()
            error.targetException.message shouldContain "return-void-no-barrier"
        }
    }

    @Test
    fun `fails on an unknown plugin instead of ignoring it`() {
        val dex = dexFile("simple.dex", mathClass(DexFixtures.sampleClass("Math")))
        val error = assertThrows<IllegalArgumentException> {
            TranslationEngine().translateBlocking(
                TranslationRequest(
                    dex,
                    workDir.resolve("unknown-plugin.jar"),
                    TranslationOptions(plugins = listOf("no-such-plugin")),
                ),
            )
        }
        error.message shouldContain "unknown plugin 'no-such-plugin'"
        error.message shouldContain "obfuscation-stripper"
    }

    @Test
    fun `rejects a file that is not a dex container`() {
        val junk = workDir.resolve("junk.bin")
        Files.writeString(junk, "not a dex file at all")
        val error = assertThrows<riad.codes.vortex.ir.DexFormatException> {
            TranslationEngine().translateBlocking(TranslationRequest(junk, workDir.resolve("junk.jar")))
        }
        error.message shouldContain "not a DEX"
    }

    @Test
    fun `reads every entry of a multi-dex container`() {
        val first = DexFixtures.sampleClass("First")
        val second = DexFixtures.sampleClass("Second")
        val archive = workDir.resolve("multi.zip")
        val a = workDir.resolve("classes.dex")
        val b = workDir.resolve("classes2.dex")
        DexFixtures.write(a, DexFixtures.dex(mathClass(first, methodName = "one", result = 1)))
        DexFixtures.write(
            b,
            DexFixtures.dex(mathClass(second, methodName = "two", result = 2), mathClass(first, methodName = "one", result = 1)),
        )
        zip(archive, "classes.dex" to a, "classes2.dex" to b)
        val container = com.android.tools.smali.dexlib2.DexFileFactory.loadDexContainer(
            archive.toFile(),
            com.android.tools.smali.dexlib2.Opcodes.getDefault(),
        )
        // dexlib2 must expose both entries through the same container the engine reads, otherwise a
        // multi-dex archive would silently translate as if it had a single DEX file.
        container.dexEntryNames.sorted() shouldBe listOf("classes.dex", "classes2.dex")
        val secondEntry = requireNotNull(container.getEntry("classes2.dex")) { "classes2.dex missing from the container" }
        secondEntry.dexFile.classes.map { it.type }.sorted() shouldBe
            listOf("Lriad/codes/vortex/sample/First;", "Lriad/codes/vortex/sample/Second;")
    }

    // ---------------------------------------------------------------------------------------------
    // fixtures
    // ---------------------------------------------------------------------------------------------

    private fun mathClass(
        owner: String,
        methodName: String = "add",
        result: Int? = null,
    ): ClassDef {
        val implementation = if (result == null) {
            DexFixtures.implementation(
                registerCount = 2,
                instructions = listOf(
                    ImmutableInstruction23x(Opcode.ADD_INT, 0, 0, 1),
                    ImmutableInstruction11x(Opcode.RETURN, 0),
                ),
            )
        } else {
            DexFixtures.implementation(
                registerCount = 1,
                instructions = listOf(
                    ImmutableInstruction11n(Opcode.CONST_4, 0, result),
                    ImmutableInstruction11x(Opcode.RETURN, 0),
                ),
            )
        }
        return DexFixtures.classDef(
            type = owner,
            methods = listOf(
                DexFixtures.method(owner, methodName, parameters = if (result == null) listOf("I", "I") else emptyList(), returnType = "I", implementation = implementation),
            ),
        )
    }

    /** `La;` — an obfuscated class with a private static method and a public entry point. */
    private fun obfuscatedHolder(): ClassDef {
        val privateStatic = DexFixtures.method(
            owner = "La;",
            name = "a",
            returnType = "I",
            accessFlags = AccessFlags.PRIVATE or AccessFlags.STATIC,
            implementation = DexFixtures.implementation(
                registerCount = 1,
                instructions = listOf(
                    ImmutableInstruction11n(Opcode.CONST_4, 0, 7),
                    ImmutableInstruction11x(Opcode.RETURN, 0),
                ),
            ),
        )
        val entryPoint = DexFixtures.method(
            owner = "La;",
            name = "compute",
            returnType = "I",
            implementation = DexFixtures.implementation(
                registerCount = 2,
                instructions = listOf(
                    ImmutableInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0, DexFixtures.methodRef("La;", "a", returnType = "I")),
                    ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
                    ImmutableInstruction22b(Opcode.ADD_INT_LIT8, 0, 0, 1),
                    ImmutableInstruction11x(Opcode.RETURN, 0),
                ),
            ),
        )
        return DexFixtures.classDef("La;", methods = listOf(privateStatic, entryPoint))
    }

    /** `Lb;` — an obfuscated class with a private obfuscated field used by two methods. */
    private fun obfuscatedData(): ClassDef {
        val field = DexFixtures.fieldRef("Lb;", "c", "I")
        val constructor = DexFixtures.method(
            owner = "Lb;",
            name = "<init>",
            returnType = "V",
            accessFlags = AccessFlags.PUBLIC or AccessFlags.CONSTRUCTOR,
            implementation = DexFixtures.implementation(
                registerCount = 2,
                instructions = listOf(
                    ImmutableInstruction35c(Opcode.INVOKE_DIRECT, 1, 1, 0, 0, 0, 0, DexFixtures.methodRef(DexFixtures.OBJECT, "<init>")),
                    ImmutableInstruction11n(Opcode.CONST_4, 0, 5),
                    ImmutableInstruction22c(Opcode.IPUT, 0, 1, field),
                    ImmutableInstruction10x(Opcode.RETURN_VOID),
                ),
            ),
        )
        val getter = DexFixtures.instanceMethod(
            owner = "Lb;",
            name = "getC",
            returnType = "I",
            implementation = DexFixtures.implementation(
                registerCount = 2,
                instructions = listOf(
                    ImmutableInstruction22c(Opcode.IGET, 0, 1, field),
                    ImmutableInstruction11x(Opcode.RETURN, 0),
                ),
            ),
        )
        return DexFixtures.classDef(
            type = "Lb;",
            methods = listOf(constructor, getter),
            fields = listOf(DexFixtures.field("Lb;", "c", "I", AccessFlags.PRIVATE)),
        )
    }

    /**
     * A method holding an opcode the engine recognises but deliberately does not translate.
     *
     * `return-void-no-barrier` is an ART bookkeeping instruction with no JVM counterpart, so it stays
     * out of scope; the class must still come out valid, with this one method replaced by a stub that
     * says why.
     */
    private fun untranslatable(owner: String) = DexFixtures.method(
        owner = owner,
        name = "make",
        returnType = "V",
        implementation = DexFixtures.implementation(
            registerCount = 1,
            instructions = listOf(
                ImmutableInstruction10x(Opcode.RETURN_VOID_NO_BARRIER),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            ),
        ),
    )

    private fun dexFile(name: String, vararg classes: ClassDef): Path {
        val path = workDir.resolve(name)
        DexFixtures.write(path, DexFixtures.dex(*classes))
        return path
    }

    private fun zip(target: Path, vararg entries: Pair<String, Path>) {
        ZipOutputStream(Files.newOutputStream(target)).use { zip ->
            entries.forEach { (name, source) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(Files.readAllBytes(source))
                zip.closeEntry()
            }
        }
    }

    private fun withLoader(output: Path, block: (URLClassLoader) -> Unit) {
        URLClassLoader(arrayOf(output.toUri().toURL()), javaClass.classLoader).use(block)
    }

    private companion object {
        val INT: Class<*> = Int::class.javaPrimitiveType!!
    }
}
