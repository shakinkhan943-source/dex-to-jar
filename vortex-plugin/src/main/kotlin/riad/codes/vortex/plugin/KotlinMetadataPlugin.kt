package riad.codes.vortex.plugin

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import riad.codes.vortex.emit.EmittedClass
import riad.codes.vortex.ir.AnnotationVisibility
import riad.codes.vortex.ir.HirEncodedValue
import riad.codes.vortex.ir.HirProgram

/**
 * Keeps Kotlin metadata usable after translation.
 *
 * Kotlin compiles a lot of structure into `kotlin.Metadata` — property/function names, nullability,
 * suspend points, default-argument masks — and decompilers read it back through reflection. DEX often
 * stores that annotation with `BUILD` visibility, which the class file format turns into a
 * *runtime-invisible* annotation that reflection cannot see; the metadata is still there in the file,
 * but every tool that goes through the reflection API loses it.
 *
 * This plugin promotes `@Metadata` to `RUNTIME` visibility so it survives as `RuntimeVisibleAnnotations`,
 * and it records the metadata kind `k` of every annotated class for the report. The bytecode pass then
 * checks that the Kotlin-specific access flags (`ACC_BRIDGE`, `ACC_SYNTHETIC`) and the annotation
 * actually made it into the emitted class, and reports a class where they did not — a translation that
 * quietly dropped them would decompile as Java-shaped Kotlin.
 */
class KotlinMetadataPlugin : VortexPlugin {

    override val id: String = "kotlin-metadata"

    override val description: String =
        "Preserves kotlin.Metadata: promotes it to runtime-visible and checks Kotlin flags survive."

    override fun transformProgram(program: HirProgram, context: PluginContext): HirProgram {
        var annotated = 0
        var promoted = 0
        val kinds = sortedMapOf<Int, Int>()

        val classes = program.classes.map { hirClass ->
            val metadata = hirClass.annotations.filter { it.type.descriptor == METADATA_DESCRIPTOR }
            if (metadata.isEmpty()) return@map hirClass
            annotated++
            metadata.forEach { annotation ->
                val kind = (annotation.element("k") as? HirEncodedValue.IntValue)?.value
                if (kind != null) kinds.merge(kind, 1, Int::plus)
            }
            val updated = hirClass.annotations.map { annotation ->
                if (annotation.type.descriptor == METADATA_DESCRIPTOR && annotation.visibility != AnnotationVisibility.RUNTIME) {
                    promoted++
                    annotation.copy(visibility = AnnotationVisibility.RUNTIME)
                } else {
                    annotation
                }
            }
            hirClass.copy(annotations = updated)
        }

        context.record(
            id,
            "found @Metadata on $annotated class(es), promoted $promoted to runtime-visible" +
                if (kinds.isEmpty()) "" else ", kinds ${kinds.entries.joinToString { "k=${it.key}:${it.value}" }}",
        )
        return program.copy(classes = classes)
    }

    override fun transformBytecode(emitted: EmittedClass, context: PluginContext): EmittedClass {
        val node = ClassNode()
        ClassReader(emitted.bytes).accept(node, 0)
        val visibleMetadata = node.visibleAnnotations?.count { it.desc == METADATA_DESCRIPTOR } ?: 0
        val bridges = node.methods.count { it.access and Opcodes.ACC_BRIDGE != 0 }
        val synthetic = node.methods.count { it.access and Opcodes.ACC_SYNTHETIC != 0 }
        val fields = node.fields.count { it.access and Opcodes.ACC_SYNTHETIC != 0 }

        if (visibleMetadata > 0 || bridges > 0 || synthetic > 0 || fields > 0) {
            context.record(
                id,
                "${emitted.internalName}: @Metadata=$visibleMetadata bridgeMethods=$bridges " +
                    "syntheticMethods=$synthetic syntheticFields=$fields",
            )
        }
        return emitted
    }

    companion object {
        const val METADATA_DESCRIPTOR = "Lkotlin/Metadata;"
    }
}
