package riad.codes.vortex.emit

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.Type
import riad.codes.vortex.ir.HirAnnotation
import riad.codes.vortex.ir.HirEncodedValue

/**
 * Emits DEX annotations into the class file's annotation attributes.
 *
 * DEX and the JVM share the annotation model closely enough that the mapping is nearly direct; the
 * differences handled here are that the JVM has no `null` annotation value, that a nested annotation
 * carries no visibility of its own, and that a DEX `MethodType` maps onto an ASM method type instead
 * of a class literal.
 */
object AnnotationEmitter {

    /** `(descriptor, visible) -> AnnotationVisitor` is `visitAnnotation` on a class/method/field. */
    fun emit(factory: (String, Boolean) -> AnnotationVisitor?, annotation: HirAnnotation) {
        val visitor = factory(annotation.type.descriptor, annotation.isRuntimeVisible) ?: return
        annotation.elements.forEach { element -> emitValue(visitor, element.name, element.value) }
        visitor.visitEnd()
    }

    fun emitAll(
        factory: (String, Boolean) -> AnnotationVisitor?,
        annotations: List<HirAnnotation>,
    ) {
        annotations.forEach { emit(factory, it) }
    }

    private fun emitValue(visitor: AnnotationVisitor, name: String?, value: HirEncodedValue) {
        when (value) {
            is HirEncodedValue.IntValue -> visitor.visit(name, value.value)
            is HirEncodedValue.ByteValue -> visitor.visit(name, value.value)
            is HirEncodedValue.ShortValue -> visitor.visit(name, value.value)
            is HirEncodedValue.CharValue -> visitor.visit(name, value.value)
            is HirEncodedValue.LongValue -> visitor.visit(name, value.value)
            is HirEncodedValue.FloatValue -> visitor.visit(name, value.value)
            is HirEncodedValue.DoubleValue -> visitor.visit(name, value.value)
            is HirEncodedValue.BooleanValue -> visitor.visit(name, value.value)
            is HirEncodedValue.StringValue -> visitor.visit(name, value.value)
            is HirEncodedValue.TypeValue -> visitor.visit(name, Type.getType(value.type.descriptor))
            is HirEncodedValue.MethodTypeValue -> visitor.visit(name, Type.getMethodType(value.methodType.descriptor))
            is HirEncodedValue.EnumValue ->
                visitor.visitEnum(name, value.constant.owner.descriptor, value.constant.name)

            // The class file format has no way to encode a null annotation value.
            HirEncodedValue.NullValue -> Unit

            is HirEncodedValue.ArrayValue -> {
                val arrayVisitor = visitor.visitArray(name) ?: return
                value.values.forEach { element -> emitValue(arrayVisitor, null, element) }
                arrayVisitor.visitEnd()
            }

            is HirEncodedValue.AnnotationValue -> {
                val nested = visitor.visitAnnotation(name, value.annotation.type.descriptor) ?: return
                value.annotation.elements.forEach { element -> emitValue(nested, element.name, element.value) }
                nested.visitEnd()
            }
        }
    }
}
