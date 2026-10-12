package riad.codes.vortex.plugin

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import riad.codes.vortex.emit.EmittedClass

/**
 * A whole-program rename, applied consistently to every emitted class.
 *
 * Keys are built from the **original** owner, because that is what ASM hands to
 * [Remapper.mapMethodName] before it remaps the owner itself. Using a stable, printed key format also
 * makes a plan diffable in a report.
 */
class RenamePlan(
    /** Original internal name to new internal name. */
    val classes: Map<String, String> = emptyMap(),
    /** `owner#name descriptor` to new name. */
    val methods: Map<String, String> = emptyMap(),
    /** `owner#name:descriptor` to new name. */
    val fields: Map<String, String> = emptyMap(),
) {
    val isEmpty: Boolean get() = classes.isEmpty() && methods.isEmpty() && fields.isEmpty()

    val size: Int get() = classes.size + methods.size + fields.size

    fun methodKey(owner: String, name: String, descriptor: String): String =
        Companion.methodKey(owner, name, descriptor)

    fun fieldKey(owner: String, name: String, descriptor: String): String =
        Companion.fieldKey(owner, name, descriptor)

    companion object {
        val EMPTY = RenamePlan()

        fun methodKey(owner: String, name: String, descriptor: String): String = "$owner#$name$descriptor"

        fun fieldKey(owner: String, name: String, descriptor: String): String = "$owner#$name:$descriptor"
    }
}

/**
 * Applies a [RenamePlan] to emitted bytecode with ASM's `ClassRemapper`.
 *
 * Frames and `maxStack`/`maxLocals` are copied from the input rather than recomputed. Renaming only
 * changes names in the constant pool, never stack shapes, so recomputing them would add a dependency
 * on being able to resolve every referenced type — exactly what [riad.codes.vortex.emit.DexAwareClassWriter]
 * exists to avoid.
 */
object BytecodeRewriter {

    fun rename(emitted: EmittedClass, plan: RenamePlan): EmittedClass {
        if (plan.isEmpty) return emitted
        val reader = ClassReader(emitted.bytes)
        val writer = ClassWriter(0)
        reader.accept(ClassRemapper(writer, ProgramRemapper(plan)), 0)
        val bytes = writer.toByteArray()
        return EmittedClass(
            // The jar entry path has to match the name inside the class file.
            internalName = plan.classes[emitted.internalName] ?: emitted.internalName,
            bytes = bytes,
            methodCount = emitted.methodCount,
            stubbedMethods = emitted.stubbedMethods,
            verificationErrors = emitted.verificationErrors,
        )
    }
}

/** The ASM [Remapper] that applies a [RenamePlan]. */
class ProgramRemapper(private val plan: RenamePlan) : Remapper(Opcodes.ASM9) {

    override fun map(internalName: String): String = plan.classes[internalName] ?: internalName

    override fun mapMethodName(owner: String, name: String, descriptor: String): String {
        // Constructors and class initialisers are never renamable.
        if (name.startsWith("<")) return name
        val remappedOwner = plan.classes[owner] ?: owner
        return plan.methods[plan.methodKey(owner, name, descriptor)]
            ?: plan.methods[plan.methodKey(remappedOwner, name, descriptor)]
            ?: name
    }

    override fun mapFieldName(owner: String, name: String, descriptor: String): String {
        val remappedOwner = plan.classes[owner] ?: owner
        return plan.fields[plan.fieldKey(owner, name, descriptor)]
            ?: plan.fields[plan.fieldKey(remappedOwner, name, descriptor)]
            ?: name
    }
}
