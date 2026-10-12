package riad.codes.vortex.ir

/**
 * Dalvik and JVM access flags.
 *
 * Class and field flags share bit assignments between the two formats. Methods do not:
 * Dalvik uses `0x10000` (`ACC_CONSTRUCTOR`) and `0x20000` (`ACC_DECLARED_SYNCHRONIZED`),
 * which sit outside the 16 bits the JVM reserves for member flags, so the low 16 bits are
 * portable and the two Dalvik-only bits have to be translated explicitly.
 */
object AccessFlags {
    const val PUBLIC = 0x0001
    const val PRIVATE = 0x0002
    const val PROTECTED = 0x0004
    const val STATIC = 0x0008
    const val FINAL = 0x0010

    /** `synchronized` on methods, `ACC_SYNCHRONIZED` on the JVM. */
    const val SYNCHRONIZED = 0x0020

    /** `volatile` on fields. */
    const val VOLATILE = 0x0040

    /** `ACC_BRIDGE` on methods (shares the bit with [VOLATILE]). */
    const val BRIDGE = 0x0040

    /** `transient` on fields. */
    const val TRANSIENT = 0x0080

    /** `ACC_VARARGS` on methods (shares the bit with [TRANSIENT]). */
    const val VARARGS = 0x0080

    const val NATIVE = 0x0100
    const val INTERFACE = 0x0200
    const val ABSTRACT = 0x0400
    const val STRICT = 0x0800
    const val SYNTHETIC = 0x1000
    const val ANNOTATION = 0x2000
    const val ENUM = 0x4000

    /** Dalvik only: the method is a constructor. */
    const val CONSTRUCTOR = 0x10000

    /** Dalvik only: `synchronized` declared in the source language. */
    const val DECLARED_SYNCHRONIZED = 0x20000

    /** Member flags beyond these bits are Dalvik-internal and must not reach a class file. */
    private const val JVM_MEMBER_MASK = 0xFFFF

    /** Class flags are bit-compatible; only the Dalvik-only high bits are dropped. */
    fun dexClassFlagsToJvm(dexFlags: Int): Int = dexFlags and JVM_MEMBER_MASK

    /** Maps `ACC_DECLARED_SYNCHRONIZED` onto the JVM `ACC_SYNCHRONIZED` bit. */
    fun dexMethodFlagsToJvm(dexFlags: Int): Int {
        val portable = dexFlags and JVM_MEMBER_MASK
        return if (dexFlags and DECLARED_SYNCHRONIZED != 0) portable or SYNCHRONIZED else portable
    }

    fun dexFieldFlagsToJvm(dexFlags: Int): Int = dexFlags and JVM_MEMBER_MASK

    fun isStatic(flags: Int): Boolean = flags and STATIC != 0

    fun isFinal(flags: Int): Boolean = flags and FINAL != 0

    fun isInterface(flags: Int): Boolean = flags and INTERFACE != 0

    fun isAbstract(flags: Int): Boolean = flags and ABSTRACT != 0

    fun isAnnotation(flags: Int): Boolean = flags and ANNOTATION != 0

    fun isEnum(flags: Int): Boolean = flags and ENUM != 0

    fun isPublic(flags: Int): Boolean = flags and PUBLIC != 0

    fun isNative(flags: Int): Boolean = flags and NATIVE != 0

    fun isAbstractOrNative(flags: Int): Boolean = flags and (ABSTRACT or NATIVE) != 0

    /** Human readable flag list, used by reports and tests. */
    fun describe(flags: Int): String {
        val names = buildList {
            if (flags and PUBLIC != 0) add("public")
            if (flags and PRIVATE != 0) add("private")
            if (flags and PROTECTED != 0) add("protected")
            if (flags and STATIC != 0) add("static")
            if (flags and FINAL != 0) add("final")
            if (flags and SYNCHRONIZED != 0) add("synchronized")
            if (flags and VOLATILE != 0) add("volatile/bridge")
            if (flags and TRANSIENT != 0) add("transient/varargs")
            if (flags and NATIVE != 0) add("native")
            if (flags and INTERFACE != 0) add("interface")
            if (flags and ABSTRACT != 0) add("abstract")
            if (flags and STRICT != 0) add("strict")
            if (flags and SYNTHETIC != 0) add("synthetic")
            if (flags and ANNOTATION != 0) add("annotation")
            if (flags and ENUM != 0) add("enum")
            if (flags and CONSTRUCTOR != 0) add("constructor")
            if (flags and DECLARED_SYNCHRONIZED != 0) add("declared-synchronized")
        }
        return names.joinToString(" ")
    }
}
