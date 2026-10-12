package riad.codes.vortex.ir

import java.util.concurrent.ConcurrentHashMap

/** JVM numeric families. `boolean`, `byte`, `short` and `char` are all `int` on the JVM. */
enum class NumericKind { INT, LONG, FLOAT, DOUBLE }

/** The nine primitive descriptors shared by Dalvik and the JVM. */
enum class PrimitiveKind(
    val dexDescriptor: Char,
    val wide: Boolean,
    val numeric: NumericKind?,
) {
    VOID('V', false, null),
    BOOLEAN('Z', false, NumericKind.INT),
    BYTE('B', false, NumericKind.INT),
    SHORT('S', false, NumericKind.INT),
    CHAR('C', false, NumericKind.INT),
    INT('I', false, NumericKind.INT),
    LONG('J', true, NumericKind.LONG),
    FLOAT('F', false, NumericKind.FLOAT),
    DOUBLE('D', true, NumericKind.DOUBLE),
    ;

    /** Slots occupied in a JVM local variable array. */
    val width: Int
        get() = when {
            this == VOID -> 0
            wide -> 2
            else -> 1
        }

    companion object {
        private val BY_DESCRIPTOR: Map<Char, PrimitiveKind> = entries.associateBy { it.dexDescriptor }

        fun fromDescriptor(descriptor: Char): PrimitiveKind =
            BY_DESCRIPTOR[descriptor] ?: throw TypeDescriptorException("'$descriptor' is not a primitive type descriptor")

        fun fromDescriptorOrNull(descriptor: Char): PrimitiveKind? = BY_DESCRIPTOR[descriptor]
    }
}

/**
 * A Dalvik/JVM type.
 *
 * Field and method type descriptors are byte-for-byte identical in DEX and in class files
 * (`Ljava/lang/String;`, `[I`, `V`), so a single [descriptor] form serves both formats. The only
 * format-specific view is the JVM's *internal name* of a class (`java/lang/String`), exposed as
 * [ObjectType.internalName].
 */
sealed interface VortexType {
    /** Descriptor shared by DEX `type_ids` and JVM constant pools. */
    val descriptor: String

    /** Slots occupied in a JVM local variable array or on the operand stack. */
    val width: Int

    val isPrimitive: Boolean

    val isReference: Boolean

    val isCategory2: Boolean get() = width == 2

    val isVoid: Boolean get() = false

    /** Dotted binary name, as used by reflection and `Class.getName`. */
    val binaryName: String
}

data class PrimitiveType(val kind: PrimitiveKind) : VortexType {
    override val descriptor: String get() = kind.dexDescriptor.toString()
    override val width: Int get() = kind.width
    override val isPrimitive: Boolean get() = true
    override val isReference: Boolean get() = false
    override val isVoid: Boolean get() = kind == PrimitiveKind.VOID
    override val binaryName: String get() = descriptor

    /** `null` for `void`, otherwise the JVM arithmetic family of this type. */
    val numeric: NumericKind? get() = kind.numeric

    /** True for `boolean`, `byte`, `short`, `char` and `int`: the JVM `int` family. */
    val isIntLike: Boolean get() = kind.numeric == NumericKind.INT

    override fun toString(): String = descriptor
}

data class ObjectType(val internalName: String) : VortexType {
    init {
        require(internalName.isNotEmpty()) { "object type internal name must not be empty" }
        require(!internalName.contains(';') && !internalName.contains('[') && !internalName.contains('.')) {
            "invalid object type internal name: '$internalName'"
        }
    }

    override val descriptor: String get() = "L$internalName;"
    override val width: Int get() = 1
    override val isPrimitive: Boolean get() = false
    override val isReference: Boolean get() = true
    override val binaryName: String get() = internalName.replace('/', '.')

    override fun toString(): String = descriptor

    companion object {
        val OBJECT = ObjectType("java/lang/Object")
        val STRING = ObjectType("java/lang/String")
        val CLASS = ObjectType("java/lang/Class")
        val THROWABLE = ObjectType("java/lang/Throwable")
        val CLONEABLE = ObjectType("java/lang/Cloneable")
        val SERIALIZABLE = ObjectType("java/io/Serializable")
    }
}

/**
 * An array type. [component] is always a non-array type and [dimensions] carries the nesting, so
 * `[[I` is `ArrayType(INT, 2)` and reads of an element yield `[I`.
 */
data class ArrayType(val component: VortexType, val dimensions: Int = 1) : VortexType {
    init {
        require(component !is ArrayType) { "array component must be un-nested; use dimensions" }
        require(dimensions >= 1) { "array dimensions must be >= 1" }
        require(!component.isVoid) { "void[] is not a valid array type" }
    }

    override val descriptor: String get() = "[".repeat(dimensions) + component.descriptor
    override val width: Int get() = 1
    override val isPrimitive: Boolean get() = false
    override val isReference: Boolean get() = true
    override val binaryName: String get() = descriptor.replace('/', '.')

    /** The type produced by a single element read, or consumed by a single element write. */
    val elementType: VortexType
        get() = if (dimensions == 1) component else ArrayType(component, dimensions - 1)

    override fun toString(): String = descriptor
}

/** A method's parameter and return types. */
data class MethodType(val parameters: List<VortexType>, val returnType: VortexType) {
    val descriptor: String
        get() = buildString {
            append('(')
            parameters.forEach { append(it.descriptor) }
            append(')')
            append(returnType.descriptor)
        }

    /** JVM local slots consumed by the parameters, excluding the receiver. */
    val parameterSlots: Int get() = parameters.sumOf { it.width }

    override fun toString(): String = descriptor
}

/** Well known primitive types, so hot paths do not re-parse descriptors. */
object VortexTypes {
    val VOID = PrimitiveType(PrimitiveKind.VOID)
    val BOOLEAN = PrimitiveType(PrimitiveKind.BOOLEAN)
    val BYTE = PrimitiveType(PrimitiveKind.BYTE)
    val SHORT = PrimitiveType(PrimitiveKind.SHORT)
    val CHAR = PrimitiveType(PrimitiveKind.CHAR)
    val INT = PrimitiveType(PrimitiveKind.INT)
    val LONG = PrimitiveType(PrimitiveKind.LONG)
    val FLOAT = PrimitiveType(PrimitiveKind.FLOAT)
    val DOUBLE = PrimitiveType(PrimitiveKind.DOUBLE)
    val OBJECT = ObjectType.OBJECT
    val STRING = ObjectType.STRING
    val THROWABLE = ObjectType.THROWABLE

    val INT_ARRAY = ArrayType(INT)
    val BYTE_ARRAY = ArrayType(BYTE)
    val OBJECT_ARRAY = ArrayType(OBJECT)
    val STRING_ARRAY = ArrayType(STRING)
}

/** Parsing and rendering of Dalvik/JVM type descriptors. */
object TypeDescriptors {
    private val cache = ConcurrentHashMap<String, VortexType>()

    /**
     * Parses a field or return type descriptor. `V` is accepted here because method return types
     * are parsed with the same entry point; use [parseValueType] where `void` must be rejected.
     */
    fun parse(descriptor: String): VortexType {
        if (descriptor.isEmpty()) throw TypeDescriptorException("empty type descriptor")
        cache[descriptor]?.let { return it }
        val parsed = parseUncached(descriptor)
        cache.putIfAbsent(descriptor, parsed)
        return parsed
    }

    /** Like [parse] but returns `null` instead of throwing, for forgiving call sites. */
    fun parseOrNull(descriptor: String): VortexType? =
        try {
            parse(descriptor)
        } catch (_: TypeDescriptorException) {
            null
        }

    /** Parses a descriptor that cannot be `void` (field types, array elements, parameters). */
    fun parseValueType(descriptor: String): VortexType {
        val type = parse(descriptor)
        if (type.isVoid) throw TypeDescriptorException("void is not a value type")
        return type
    }

    /** Parses a `(params)return` method descriptor. */
    fun parseMethod(descriptor: String): MethodType {
        if (descriptor.isEmpty() || descriptor[0] != '(') {
            throw TypeDescriptorException("method descriptor must start with '(': '$descriptor'")
        }
        val parameters = ArrayList<VortexType>(8)
        var index = 1
        while (index < descriptor.length && descriptor[index] != ')') {
            val start = index
            index = scanType(descriptor, index)
            parameters += parse(descriptor.substring(start, index))
        }
        if (index >= descriptor.length) {
            throw TypeDescriptorException("method descriptor has no return type: '$descriptor'")
        }
        val returnDescriptor = descriptor.substring(index + 1)
        if (returnDescriptor.isEmpty()) {
            throw TypeDescriptorException("method descriptor has an empty return type: '$descriptor'")
        }
        return MethodType(parameters, parse(returnDescriptor))
    }

    /** Builds an [ObjectType] from a dotted binary name (`java.lang.String`). */
    fun fromBinaryName(binaryName: String): ObjectType = ObjectType(binaryName.replace('.', '/'))

    /** Descriptor of [internalName], which may be a primitive descriptor or a class internal name. */
    fun descriptorOfInternalName(internalName: String): String =
        when {
            internalName.length == 1 && PrimitiveKind.fromDescriptorOrNull(internalName[0]) != null -> internalName
            internalName.startsWith("[") -> internalName
            else -> "L$internalName;"
        }

    private fun scanType(descriptor: String, start: Int): Int {
        var index = start
        while (index < descriptor.length && descriptor[index] == '[') index++
        if (index >= descriptor.length) throw TypeDescriptorException("truncated type in descriptor: '$descriptor'")
        return if (descriptor[index] == 'L') {
            val end = descriptor.indexOf(';', index)
            if (end < 0) throw TypeDescriptorException("unterminated class type in descriptor: '$descriptor'")
            end + 1
        } else {
            index + 1
        }
    }

    private fun parseUncached(descriptor: String): VortexType {
        var index = 0
        var dimensions = 0
        while (index < descriptor.length && descriptor[index] == '[') {
            dimensions++
            index++
        }
        if (index >= descriptor.length) throw TypeDescriptorException("truncated array descriptor: '$descriptor'")
        val base = when (val marker = descriptor[index]) {
            'L' -> {
                val end = descriptor.indexOf(';', index)
                if (end < 0) throw TypeDescriptorException("unterminated class descriptor: '$descriptor'")
                if (end != descriptor.length - 1) throw TypeDescriptorException("trailing characters in descriptor: '$descriptor'")
                val internalName = descriptor.substring(index + 1, end)
                if (internalName.isEmpty()) throw TypeDescriptorException("empty class name in descriptor: '$descriptor'")
                ObjectType(internalName)
            }

            '[' -> throw TypeDescriptorException("malformed array descriptor: '$descriptor'")

            else -> {
                if (index != descriptor.length - 1) throw TypeDescriptorException("trailing characters in descriptor: '$descriptor'")
                PrimitiveType(PrimitiveKind.fromDescriptor(marker))
            }
        }
        if (dimensions == 0) return base
        if (base.isVoid) throw TypeDescriptorException("void[] is not a valid array type")
        return ArrayType(base, dimensions)
    }
}
