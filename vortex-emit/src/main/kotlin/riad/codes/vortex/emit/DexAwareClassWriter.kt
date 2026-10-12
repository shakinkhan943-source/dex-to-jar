package riad.codes.vortex.emit

import org.objectweb.asm.ClassWriter
import riad.codes.vortex.ir.ClassHierarchy
import riad.codes.vortex.ir.ObjectType

/**
 * A [ClassWriter] that computes stack map frames from the DEX class graph instead of by loading
 * classes.
 *
 * `COMPUTE_FRAMES` asks the writer for the common superclass of the types meeting at every merge
 * point. The stock implementation calls `Class.forName`, which cannot work here: a translated APK
 * refers to `android/app/Activity`, `androidx/...` and every app class, none of which are on the
 * tool's classpath, and a single missing class would abort frame computation with a
 * `TypeNotPresentException`.
 *
 * Resolution therefore goes:
 *
 * 1. the DEX graph, which always answers and never throws — app types are resolved exactly, and
 *    unknown types conservatively become `java/lang/Object`;
 * 2. the JDK, but only when the graph had to answer `java/lang/Object`, so merges of `String` and
 *    `StringBuilder` keep the precision the verifier needs;
 * 3. `java/lang/Object` if even that fails.
 *
 * Arrays always merge to `java/lang/Object`, which is exact enough: the verifier only needs a frame
 * type that the following instructions accept, and every reference use of an array is through
 * `Object`.
 */
class DexAwareClassWriter(
    private val hierarchy: ClassHierarchy,
    flags: Int = COMPUTE_FRAMES or COMPUTE_MAXS,
) : ClassWriter(flags) {

    override fun getCommonSuperClass(type1: String, type2: String): String {
        if (type1 == type2) return type1
        val objectName = ObjectType.OBJECT.internalName
        if (type1 == objectName || type2 == objectName) return objectName
        // Arrays: `java/lang/Object` is the only array supertype worth modelling here.
        if (type1.startsWith("[") || type2.startsWith("[")) return objectName

        val fromGraph = hierarchy.commonSuperClass(type1, type2)
        if (fromGraph != objectName) return fromGraph

        return try {
            super.getCommonSuperClass(type1, type2)
        } catch (_: Throwable) {
            // The class is absent from the toolpath *and* from the DEX graph: stay conservative.
            objectName
        }
    }
}
