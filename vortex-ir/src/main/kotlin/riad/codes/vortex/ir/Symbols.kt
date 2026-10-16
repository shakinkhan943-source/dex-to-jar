package riad.codes.vortex.ir

/** A reference to a method, resolved: owner plus name plus fully described signature. */
data class MethodReference(
    // The owner is a plain `VortexType`, not an `ObjectType`: DEX lets array types own methods
    // (`clone`, `equals`, ...) and the verifier keeps the receiver's type honest through it.
    val owner: VortexType,
    val name: String,
    val parameters: List<VortexType>,
    val returnType: VortexType,
) {
    val methodType: MethodType get() = MethodType(parameters, returnType)

    /** JVM method descriptor, identical to the DEX proto descriptor. */
    val descriptor: String get() = methodType.descriptor

    /** Slots the invocation arguments consume on the operand stack, excluding the receiver. */
    val parameterSlots: Int get() = methodType.parameterSlots

    val isConstructor: Boolean get() = name == "<init>"

    val isStaticInitializer: Boolean get() = name == "<clinit>"

    fun withOwner(newOwner: ObjectType): MethodReference = copy(owner = newOwner)

    /**
     * The owner as the JVM encodes it in the constant pool: the internal name for classes, the
     * array descriptor for array types (`Array.clone` is invoked on the array itself).
     */
    val jvmOwnerName: String
        get() = when (val type = owner) {
            is ObjectType -> type.internalName
            else -> type.descriptor
        }

    override fun toString(): String = "${jvmOwnerName}.$name$descriptor"
}

/** A reference to a field: owner, name and type. */
data class FieldReference(
    val owner: ObjectType,
    val name: String,
    val type: VortexType,
) {
    /** JVM field descriptor. */
    val descriptor: String get() = type.descriptor

    fun withOwner(newOwner: ObjectType): FieldReference = copy(owner = newOwner)

    override fun toString(): String = "${owner.internalName}.$name:${type.descriptor}"
}

/**
 * The class graph of a translated program, used for
 * * precise type merging during abstract interpretation,
 * * `ClassWriter.getCommonSuperClass` during frame computation (the DEX graph replaces class
 *   loading, so references to Android types the toolpath cannot load never blow up ASM).
 *
 * Classes that are not part of the program are modelled conservatively: their supertype is
 * `java/lang/Object`, so merges degrade to `Object` instead of failing. They are never a reason to
 * reject a translation.
 */
class ClassHierarchy(private val entries: Map<String, ClassInfo>) {

    data class ClassInfo(
        val type: ObjectType,
        val superType: ObjectType?,
        val interfaces: List<ObjectType>,
        val accessFlags: Int,
    ) {
        val internalName: String get() = type.internalName
        val isInterface: Boolean get() = AccessFlags.isInterface(accessFlags)
        val isAbstract: Boolean get() = AccessFlags.isAbstract(accessFlags)
    }

    /** Interfaces that are ubiquitous in translated code but absent from the DEX graph. */
    private val knownInterfaces: Set<String> = KNOWN_JDK_INTERFACES

    fun size(): Int = entries.size

    fun classInfo(internalName: String): ClassInfo? = entries[internalName]

    fun contains(internalName: String): Boolean = entries.containsKey(internalName)

    fun classes(): Collection<ClassInfo> = entries.values

    /** Direct superclass, or `null` when the class is not part of the program. */
    fun superClassOf(internalName: String): String? = entries[internalName]?.superType?.internalName

    fun isInterface(internalName: String): Boolean =
        entries[internalName]?.isInterface ?: (internalName in knownInterfaces)

    /** All transitive supertypes (classes and interfaces) of [internalName]. */
    fun superTypesOf(internalName: String): Set<String> {
        val seen = LinkedHashSet<String>()
        val queue = ArrayDeque<String>()
        entries[internalName]?.let { info ->
            info.superType?.let { queue += it.internalName }
            info.interfaces.forEach { queue += it.internalName }
        }
        var guard = 0
        while (queue.isNotEmpty() && guard++ < MAX_HIERARCHY_STEPS) {
            val next = queue.removeFirst()
            if (!seen.add(next)) continue
            val info = entries[next] ?: continue
            info.superType?.let { queue += it.internalName }
            info.interfaces.forEach { queue += it.internalName }
        }
        // Everything reachable ends up at java/lang/Object.
        if (internalName != ObjectType.OBJECT.internalName) seen += ObjectType.OBJECT.internalName
        return seen
    }

    /** True when a value of type [from] can be used where [to] is expected. */
    fun isAssignable(from: VortexType, to: VortexType): Boolean {
        if (from == to) return true
        if (!from.isReference || !to.isReference) return false
        if (to == ObjectType.OBJECT) return true
        return when {
            from is ObjectType && to is ObjectType ->
                isAssignableInternal(from.internalName, to.internalName)

            from is ArrayType && to is ObjectType -> to.internalName in ARRAY_SUPERTYPES

            from is ArrayType && to is ArrayType -> {
                if (from.dimensions != to.dimensions) {
                    // A lower-dimensional array (or Object) is a supertype of a higher-dimensional one.
                    from.dimensions > to.dimensions &&
                        (to.component.isReference || to.component == ObjectType.OBJECT)
                } else {
                    when {
                        from.component == to.component -> true
                        from.component.isPrimitive || to.component.isPrimitive -> false
                        else -> isAssignable(from.component, to.component)
                    }
                }
            }

            else -> false
        }
    }

    /** Never-null JVM `getCommonSuperClass` replacement backed by the DEX graph. */
    fun commonSuperClass(first: String, second: String): String {
        if (first == second) return first
        val superName = ObjectType.OBJECT.internalName
        if (first == superName || second == superName) return superName
        if (isAssignableInternal(second, first)) return first
        if (isAssignableInternal(first, second)) return second
        var current: String? = superClassOf(first) ?: superName
        var guard = 0
        while (current != null && current != superName && guard++ < MAX_HIERARCHY_STEPS) {
            if (isAssignableInternal(second, current)) return current
            current = superClassOf(current)
        }
        return superName
    }

    /** Least upper bound of two concrete types; falls back to `java/lang/Object`. */
    fun leastUpperBound(first: VortexType, second: VortexType): VortexType {
        if (first == second) return first
        return when {
            first is PrimitiveType && second is PrimitiveType -> first
            first is ArrayType && second is ArrayType && first.dimensions == second.dimensions -> {
                if (first.component == second.component) first
                else if (first.component.isPrimitive || second.component.isPrimitive) ObjectType.OBJECT
                else ArrayType(leastUpperBound(first.component, second.component), first.dimensions)
            }

            first.isReference && second.isReference -> ObjectType(commonSuperClass(internalNameOf(first), internalNameOf(second)))
            else -> ObjectType.OBJECT
        }
    }

    private fun internalNameOf(type: VortexType): String = when (type) {
        is ObjectType -> type.internalName
        is ArrayType -> type.descriptor
        else -> ObjectType.OBJECT.internalName
    }

    private fun isAssignableInternal(from: String, to: String): Boolean {
        if (from == to) return true
        if (to == ObjectType.OBJECT.internalName) return true
        if (from == ObjectType.OBJECT.internalName) return false
        return superTypesOf(from).contains(to)
    }

    companion object {
        private const val MAX_HIERARCHY_STEPS = 4096

        private val ARRAY_SUPERTYPES = setOf(
            ObjectType.OBJECT.internalName,
            ObjectType.CLONEABLE.internalName,
            ObjectType.SERIALIZABLE.internalName,
        )

        /**
         * Interfaces that exist on every JVM but are not part of a DEX file's class list. Knowing
         * them keeps `invokeinterface` flags and frame merges accurate for JDK-typed values.
         */
        private val KNOWN_JDK_INTERFACES = setOf(
            "java/lang/Runnable",
            "java/lang/Cloneable",
            "java/io/Serializable",
            "java/lang/Comparable",
            "java/lang/CharSequence",
            "java/lang/Iterable",
            "java/util/Iterator",
            "java/util/Collection",
            "java/util/List",
            "java/util/Set",
            "java/util/Map",
            "java/util/Map\$Entry",
            "java/util/function/Function",
            "java/util/function/Consumer",
            "java/util/function/Supplier",
            "java/util/function/Predicate",
            "java/util/concurrent/Callable",
        )

        val EMPTY = ClassHierarchy(emptyMap())

        fun of(classes: Iterable<HirClass>): ClassHierarchy =
            ClassHierarchy(
                classes.associate { hirClass ->
                    hirClass.type.internalName to ClassInfo(
                        type = hirClass.type,
                        superType = hirClass.superType,
                        interfaces = hirClass.interfaces,
                        accessFlags = hirClass.accessFlags,
                    )
                },
            )
    }
}
