package riad.codes.vortex.dex

import com.android.tools.smali.dexlib2.iface.reference.FieldReference as DexFieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodProtoReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference as DexMethodReference
import riad.codes.vortex.ir.DexFormatException
import riad.codes.vortex.ir.FieldReference
import riad.codes.vortex.ir.MethodReference
import riad.codes.vortex.ir.MethodType
import riad.codes.vortex.ir.ObjectType
import riad.codes.vortex.ir.TypeDescriptors
import riad.codes.vortex.ir.VortexType

/**
 * Resolves the constant-pool references `dexlib2` hands back into the engine's own reference types,
 * which are fully typed (`VortexType` values rather than descriptor strings).
 *
 * Every DEX reference is a descriptor string in the constant pool; the IR keeps the parsed type so
 * no stage after ingestion has to re-parse, and so type checks cannot silently skip `Ljava/lang/...`
 * versus array forms.
 */
class DexReferenceResolver {

    fun type(descriptor: String): VortexType = TypeDescriptors.parse(descriptor)

    /** Resolves a reference that must name a class (`new-instance`, `const-class`, a field owner). */
    fun objectType(descriptor: String): ObjectType =
        TypeDescriptors.parse(descriptor) as? ObjectType
            ?: throw DexFormatException("expected a class type but found '$descriptor'")

    fun methodType(reference: DexMethodReference): MethodType = MethodType(
        parameters = reference.parameterTypes.map { TypeDescriptors.parse(it.toString()) },
        returnType = TypeDescriptors.parse(reference.returnType),
    )

    fun methodType(proto: MethodProtoReference): MethodType = MethodType(
        parameters = proto.parameterTypes.map { TypeDescriptors.parse(it.toString()) },
        returnType = TypeDescriptors.parse(proto.returnType),
    )

    fun method(reference: DexMethodReference): MethodReference {
        val methodType = methodType(reference)
        // Array types own methods in DEX (`clone`, inherited `Object` members), so the owner is
        // resolved as a plain type instead of being forced into a class shape.
        return MethodReference(
            owner = type(reference.definingClass),
            name = reference.name,
            parameters = methodType.parameters,
            returnType = methodType.returnType,
        )
    }

    fun method(reference: DexMethodReference, proto: MethodProtoReference): MethodReference {
        val methodType = methodType(proto)
        return MethodReference(
            owner = type(reference.definingClass),
            name = reference.name,
            parameters = methodType.parameters,
            returnType = methodType.returnType,
        )
    }

    fun field(reference: DexFieldReference): FieldReference = FieldReference(
        owner = objectType(reference.definingClass),
        name = reference.name,
        type = TypeDescriptors.parse(reference.type),
    )
}
