package riad.codes.vortex.ir

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class VortexTypeTest {

    @Test
    fun `parses class descriptors into internal names`() {
        val type = TypeDescriptors.parse("Ljava/lang/String;")
        type.shouldBeInstanceOf<ObjectType>().internalName shouldBe "java/lang/String"
        type.descriptor shouldBe "Ljava/lang/String;"
        type.binaryName shouldBe "java.lang.String"
        type.width shouldBe 1
        type.isReference shouldBe true
    }

    @Test
    fun `parses multi dimensional primitive arrays`() {
        val array = TypeDescriptors.parse("[[I")
        array.shouldBeInstanceOf<ArrayType>()
        array.dimensions shouldBe 2
        array.component shouldBe VortexTypes.INT
        array.descriptor shouldBe "[[I"
        array.elementType shouldBe ArrayType(VortexTypes.INT)
    }

    @Test
    fun `parses object array element types`() {
        val type = TypeDescriptors.parse("[Ljava/lang/Object;") as ArrayType
        type.elementType shouldBe ObjectType.OBJECT
        type.dimensions shouldBe 1
    }

    @Test
    fun `wide primitives occupy two slots`() {
        VortexTypes.LONG.width shouldBe 2
        VortexTypes.DOUBLE.width shouldBe 2
        VortexTypes.LONG.isCategory2 shouldBe true
        VortexTypes.INT.width shouldBe 1
        VortexTypes.VOID.width shouldBe 0
    }

    @Test
    fun `parses method descriptors and parameter slots`() {
        val methodType = TypeDescriptors.parseMethod("(I[Ljava/lang/String;JD)V")
        methodType.parameters shouldBe listOf(VortexTypes.INT, VortexTypes.STRING_ARRAY, VortexTypes.LONG, VortexTypes.DOUBLE)
        methodType.returnType shouldBe VortexTypes.VOID
        // int(1) + String[](1) + long(2) + double(2)
        methodType.parameterSlots shouldBe 6
        methodType.descriptor shouldBe "(I[Ljava/lang/String;JD)V"
    }

    @Test
    fun `rejects malformed descriptors`() {
        assertThrows<TypeDescriptorException> { TypeDescriptors.parse("Ljava/lang/String") }
        assertThrows<TypeDescriptorException> { TypeDescriptors.parse("") }
        assertThrows<TypeDescriptorException> { TypeDescriptors.parse("II") }
        assertThrows<TypeDescriptorException> { TypeDescriptors.parse("[") }
        assertThrows<TypeDescriptorException> { TypeDescriptors.parseMethod("I)V") }
        assertThrows<TypeDescriptorException> { TypeDescriptors.parseMethod("(I)") }
        assertThrows<TypeDescriptorException> { TypeDescriptors.parseValueType("V") }
    }

    @Test
    fun `parsing is memoised so repeated descriptors share instances`() {
        // A property the engine relies on: descriptor parsing happens once per distinct string.
        TypeDescriptors.parse("Lriad/codes/vortex/Sample;") shouldBe TypeDescriptors.parse("Lriad/codes/vortex/Sample;")
    }

    @Test
    fun `descriptor of internal name distinguishes primitives, arrays and classes`() {
        TypeDescriptors.descriptorOfInternalName("I") shouldBe "I"
        TypeDescriptors.descriptorOfInternalName("[I") shouldBe "[I"
        TypeDescriptors.descriptorOfInternalName("java/lang/Object") shouldBe "Ljava/lang/Object;"
    }
}

class AccessFlagsTest {

    @Test
    fun `dalvik declared synchronized maps onto the jvm bit`() {
        val dexFlags = AccessFlags.PUBLIC or AccessFlags.DECLARED_SYNCHRONIZED
        val jvmFlags = AccessFlags.dexMethodFlagsToJvm(dexFlags)
        jvmFlags shouldBe (AccessFlags.PUBLIC or AccessFlags.SYNCHRONIZED)
    }

    @Test
    fun `dalvik constructor bit never reaches a class file`() {
        val dexFlags = AccessFlags.PUBLIC or AccessFlags.CONSTRUCTOR
        AccessFlags.dexMethodFlagsToJvm(dexFlags) shouldBe AccessFlags.PUBLIC
    }

    @Test
    fun `class flags are bit compatible`() {
        val dexFlags = AccessFlags.PUBLIC or AccessFlags.FINAL or AccessFlags.ENUM
        AccessFlags.dexClassFlagsToJvm(dexFlags) shouldBe dexFlags
    }

    @Test
    fun `describes flags for reports`() {
        AccessFlags.describe(AccessFlags.PUBLIC or AccessFlags.STATIC) shouldBe "public static"
        AccessFlags.describe(0) shouldBe ""
    }
}

class ClassHierarchyTest {

    private fun program(): HirProgram {
        fun clazz(
            name: String,
            superName: String?,
            flags: Int = AccessFlags.PUBLIC,
            interfaces: List<ObjectType> = emptyList(),
        ) = HirClass(
            type = ObjectType(name),
            accessFlags = flags,
            superType = superName?.let(::ObjectType),
            interfaces = interfaces,
            sourceFile = null,
            annotations = emptyList(),
            fields = emptyList(),
            methods = emptyList(),
        )

        return HirProgram(
            classes = listOf(
                clazz("riad/codes/vortex/Animal", "java/lang/Object", AccessFlags.PUBLIC or AccessFlags.ABSTRACT),
                clazz("riad/codes/vortex/Dog", "riad/codes/vortex/Animal"),
                clazz("riad/codes/vortex/Bird", "riad/codes/vortex/Animal"),
                clazz("riad/codes/vortex/Pet", "java/lang/Object", AccessFlags.PUBLIC or AccessFlags.INTERFACE),
                clazz("riad/codes/vortex/DogPet", "riad/codes/vortex/Dog", interfaces = listOf(ObjectType("riad/codes/vortex/Pet"))),
            ),
            sources = emptyList(),
        )
    }

    @Test
    fun `resolves nearest common superclass through the dex graph`() {
        val hierarchy = program().hierarchy
        hierarchy.commonSuperClass("riad/codes/vortex/Dog", "riad/codes/vortex/Bird") shouldBe "riad/codes/vortex/Animal"
        hierarchy.commonSuperClass("riad/codes/vortex/Dog", "riad/codes/vortex/Dog") shouldBe "riad/codes/vortex/Dog"
    }

    @Test
    fun `falls back to java lang Object for classes outside the program`() {
        val hierarchy = program().hierarchy
        // android.app.Activity is not in the DEX graph: merging must stay total, never throw.
        hierarchy.commonSuperClass("android/app/Activity", "riad/codes/vortex/Dog") shouldBe ObjectType.OBJECT.internalName
    }

    @Test
    fun `isAssignable walks supertypes and interfaces`() {
        val hierarchy = program().hierarchy
        hierarchy.isAssignable(ObjectType("riad/codes/vortex/DogPet"), ObjectType("riad/codes/vortex/Animal")) shouldBe true
        hierarchy.isAssignable(ObjectType("riad/codes/vortex/DogPet"), ObjectType("riad/codes/vortex/Pet")) shouldBe true
        hierarchy.isAssignable(ObjectType("riad/codes/vortex/Animal"), ObjectType("riad/codes/vortex/Dog")) shouldBe false
        hierarchy.isAssignable(VortexTypes.INT_ARRAY, ObjectType.OBJECT) shouldBe true
        hierarchy.isAssignable(VortexTypes.INT_ARRAY, ObjectType.CLONEABLE) shouldBe true
        hierarchy.isAssignable(VortexTypes.INT_ARRAY, ObjectType.STRING) shouldBe false
    }

    @Test
    fun `least upper bound keeps precision inside the program and degrades outside it`() {
        val hierarchy = program().hierarchy
        hierarchy.leastUpperBound(ObjectType("riad/codes/vortex/Dog"), ObjectType("riad/codes/vortex/Bird")) shouldBe
            ObjectType("riad/codes/vortex/Animal")
        hierarchy.leastUpperBound(VortexTypes.INT, VortexTypes.INT) shouldBe VortexTypes.INT
        val merged = hierarchy.leastUpperBound(VortexTypes.STRING, VortexTypes.INT)
        merged shouldNotBe null
        merged shouldBe ObjectType.OBJECT
    }

    @Test
    fun `knows jdk interfaces that are absent from the dex graph`() {
        val hierarchy = program().hierarchy
        hierarchy.isInterface("java/lang/Runnable") shouldBe true
        hierarchy.isInterface("riad/codes/vortex/Pet") shouldBe true
        hierarchy.isInterface("riad/codes/vortex/Dog") shouldBe false
    }
}
