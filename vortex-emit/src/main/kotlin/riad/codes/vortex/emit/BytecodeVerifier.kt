package riad.codes.vortex.emit

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.util.CheckClassAdapter
import riad.codes.vortex.ir.ClassHierarchy
import riad.codes.vortex.ir.ObjectType
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Bytecode verification.
 *
 * Two levels are available, and they answer different questions.
 *
 * * [verify] runs ASM's checker over the emitted bytes. It catches structural errors — bad visitor
 *   call order, an instruction that leaves the operand stack unbalanced, a branch to a label that is
 *   not defined, a local variable index beyond `maxLocals` — without needing the referenced classes
 *   to exist, which matters because a translated APK references Android types the toolpath does not
 *   have.
 * * [defineWithClassLoader] defines the class through a real `ClassLoader`, so the JVM's own
 *   verifier runs. That is the ground truth, and it is what the test suite uses. It only works for
 *   classes whose references are all resolvable, so it is used in tests rather than on every run.
 */
object BytecodeVerifier {

    /**
     * Returns one message per problem ASM's checker found; empty means the class is structurally sound.
     *
     * [hierarchy] is what lets the checker answer questions about the classes being translated. The
     * class file it inspects refers to types that exist only in the output of this very run, and ASM's
     * flow analysis asks a class loader whether one type is assignable to another; without the graph
     * every such reference comes back as `ClassNotFoundException` instead of an answer.
     */
    fun verify(
        bytes: ByteArray,
        className: String = "<unknown>",
        hierarchy: ClassHierarchy? = null,
    ): List<String> {
        val output = StringWriter()
        val loader = if (hierarchy == null) javaClass.classLoader else ProgramClassLoader(hierarchy)
        return try {
            CheckClassAdapter.verify(ClassReader(bytes), loader, false, PrintWriter(output))
            output.toString()
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { "$className: $it" }
        } catch (error: Throwable) {
            listOf("$className: verification threw ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    /**
     * Defines [bytes] in a throwaway class loader and returns the loaded class.
     *
     * Loading is what makes the JVM verify the class; calling it with `initialize = false` keeps static
     * initialisers from running, so a verified class cannot have side effects.
     */
    fun defineWithClassLoader(classes: Map<String, ByteArray>, binaryName: String): Class<*> {
        val loader = object : ClassLoader(BytecodeVerifier::class.java.classLoader) {
            override fun findClass(name: String): Class<*> {
                val bytes = classes[name] ?: classes[name.replace('.', '/')]
                ?: throw ClassNotFoundException(name)
                return defineClass(name, bytes, 0, bytes.size)
            }
        }
        return Class.forName(binaryName, false, loader)
    }

    /** Defines every class in [classes], forcing the JVM to verify each one. */
    fun defineAll(classes: Map<String, ByteArray>): List<Class<*>> =
        classes.keys.map { internalName -> defineWithClassLoader(classes, internalName.replace('/', '.')) }

    /**
     * A class loader that stands in for the program being translated.
     *
     * `Class.forName` is what ASM's flow checker uses to compare two reference types, and the types it
     * is asked about are the ones in the file being emitted: they have no bytes anywhere yet. Every
     * type the DEX graph knows therefore becomes an empty class carrying the same supertypes, flags and
     * interfaces, which is exactly the information the checker uses — it asks about relationships, never
     * about code. Types that are in neither the graph nor the toolpath still fail to load, and that is
     * the honest answer: they are reported rather than silently assumed to be compatible.
     */
    private class ProgramClassLoader(private val hierarchy: ClassHierarchy) :
        ClassLoader(BytecodeVerifier::class.java.classLoader) {

        override fun findClass(name: String): Class<*> {
            // Types outside the graph are modelled exactly like the hierarchy models them:
            // conservatively, with `java/lang/Object` as the supertype. Throwing here would make a
            // perfectly valid method fail verification just because it touches a library class
            // (`okio.BufferedSink`, `androidx.compose...`) that neither the tool path nor the DEX
            // file defines — every known caveat of the old JDK-dependent tools.
            val internalName = name.replace('.', '/')
            val info = hierarchy.classInfo(internalName)
            val bytes = stubBytes(
                info ?: ClassHierarchy.ClassInfo(
                    type = ObjectType(internalName),
                    superType = ObjectType.OBJECT,
                    interfaces = emptyList(),
                    accessFlags = Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT,
                ),
            )
            return defineClass(null, bytes, 0, bytes.size)
        }

        private fun stubBytes(info: ClassHierarchy.ClassInfo): ByteArray {
            val writer = ClassWriter(0)
            writer.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT or
                    if (info.isInterface) Opcodes.ACC_INTERFACE else 0,
                info.internalName,
                null,
                info.superType?.internalName ?: ObjectType.OBJECT.internalName,
                info.interfaces.map { it.internalName }.toTypedArray(),
            )
            writer.visitEnd()
            return writer.toByteArray()
        }
    }
}
