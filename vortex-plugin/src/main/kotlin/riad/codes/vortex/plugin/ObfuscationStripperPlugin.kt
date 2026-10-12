package riad.codes.vortex.plugin

import riad.codes.vortex.emit.EmittedClass
import riad.codes.vortex.ir.AccessFlags
import riad.codes.vortex.ir.HirClass
import riad.codes.vortex.ir.HirField
import riad.codes.vortex.ir.HirMethod
import riad.codes.vortex.ir.HirProgram

/**
 * Renames obfuscated names so a translated jar is readable in a decompiler.
 *
 * Obfuscators give classes and members one- or two-character names, which is fine inside a DEX with a
 * complete class hierarchy but makes translated Java unreadable. This plugin builds one
 * [RenamePlan] from the whole program and hands it to [BytecodeRewriter], so every reference —
 * supertypes, descriptors, frames, catch types, annotation values, `ldc` class constants — is remapped
 * consistently by ASM rather than by hand.
 *
 * **What it renames, and what it deliberately does not.** This is a conservative, always-correct
 * subset of what a whole-program renamer could do:
 *
 * * **Classes** whose simple name looks obfuscated, excluding library packages and nested classes
 *   (`a$b`). Renaming a nested class would leave the `InnerClasses` simple name inconsistent with the
 *   new name, and every Java/Kotlin tool that reads it back would report the wrong thing.
 * * **Private methods and private fields** whose name looks obfuscated. Private members cannot
 *   participate in inheritance, and DEX/JVM resolution always resolves them against their declaring
 *   class, so renaming them cannot change which member a call site reaches.
 *
 * Public and virtual members are left alone on purpose: renaming those correctly needs whole-program
 * override analysis (a virtual method must be renamed in every subclass and every interface that
 * declares it, plus any library code outside the input would have to be updated too). Doing it
 * partially would silently change dispatch, which is exactly the failure mode this project refuses to
 * ship. Such a translation is still a superset of the input, just with less pretty names.
 *
 * A [RenamePlan] can only be built from a complete program, so [transformProgram] is required to run
 * before [transformBytecode]; the engine calls them in that order.
 */
class ObfuscationStripperPlugin(
    private val renameClasses: Boolean = true,
    private val renamePrivateMembers: Boolean = true,
) : VortexPlugin {

    override val id: String = "obfuscation-stripper"

    override val description: String =
        "Renames obfuscated classes and private members to stable, readable identifiers."

    @Volatile
    private var plan: RenamePlan = RenamePlan.EMPTY

    override fun transformProgram(program: HirProgram, context: PluginContext): HirProgram {
        plan = RenamePlanner(program, renameClasses, renamePrivateMembers).build()
        context.record(id, explain(plan))
        return program
    }

    override fun transformBytecode(emitted: EmittedClass, context: PluginContext): EmittedClass =
        BytecodeRewriter.rename(emitted, plan)

    private fun explain(plan: RenamePlan): String {
        if (plan.isEmpty) return "nothing to rename: no obfuscated top-level classes or private members found"
        val sample = plan.classes.entries.take(3).joinToString(", ") { "${it.key} -> ${it.value}" }
        return buildString {
            append("planned ${plan.classes.size} class, ${plan.methods.size} private method and ")
            append("${plan.fields.size} private field rename(s)")
            if (sample.isNotEmpty()) append("; e.g. ").append(sample)
        }
    }
}

/**
 * Computes the rename [RenamePlan].
 *
 * New names are derived from a counter rather than from the old name so the result is reproducible
 * (identical input, identical names) and free of the `$` and digit-leading pitfalls of mangled names.
 * Every candidate is checked against the names that already exist in the program, so a generated name
 * can never collide with a real one.
 */
class RenamePlanner(
    private val program: HirProgram,
    private val renameClasses: Boolean,
    private val renamePrivateMembers: Boolean,
) {

    fun build(): RenamePlan {
        val classRenames = if (renameClasses) planClassRenames() else emptyMap()
        val methodRenames = LinkedHashMap<String, String>()
        val fieldRenames = LinkedHashMap<String, String>()
        if (renamePrivateMembers) {
            program.classes.forEach { hirClass -> planMemberRenames(hirClass, methodRenames, fieldRenames) }
        }
        return RenamePlan(classRenames, methodRenames, fieldRenames)
    }

    private fun planClassRenames(): Map<String, String> {
        val existing = program.classes.mapTo(HashSet()) { it.type.internalName }
        val renames = LinkedHashMap<String, String>()
        var counter = 1

        program.classes
            .map { it.type.internalName }
            .filter { isRenamableClass(it) }
            .sorted()
            .forEach { internalName ->
                val packagePrefix = internalName.substringBeforeLast('/', missingDelimiterValue = "")
                var candidate: String
                do {
                    val simple = "VortexClass$counter"
                    candidate = if (packagePrefix.isEmpty()) simple else "$packagePrefix/$simple"
                    counter++
                } while (candidate in existing || candidate in renames.values)

                existing += candidate
                renames[internalName] = candidate
            }
        return renames
    }

    private fun isRenamableClass(internalName: String): Boolean {
        if (LIBRARY_PREFIXES.any { internalName.startsWith(it) }) return false
        val simpleName = internalName.substringAfterLast('/')
        // Nested and anonymous classes keep their `$` shape, which `InnerClasses` refers back to.
        if (simpleName.contains('$')) return false
        return looksObfuscated(simpleName)
    }

    private fun planMemberRenames(
        hirClass: HirClass,
        methodRenames: MutableMap<String, String>,
        fieldRenames: MutableMap<String, String>,
    ) {
        val owner = hirClass.type.internalName

        val methodNames = hirClass.methods.mapTo(HashSet()) { "${it.name}${it.reference.descriptor}" }
        var methodCounter = 1
        hirClass.methods.forEach { method ->
            if (!isRenamablePrivateMethod(method)) return@forEach
            var candidate: String
            do {
                candidate = "vortexMethod$methodCounter"
                methodCounter++
            } while ("$candidate${method.reference.descriptor}" in methodNames)
            methodNames += "$candidate${method.reference.descriptor}"
            methodRenames[RenamePlan.methodKey(owner, method.name, method.reference.descriptor)] = candidate
        }

        val fieldNames = hirClass.fields.mapTo(HashSet()) { "${it.name}:${it.type.descriptor}" }
        var fieldCounter = 1
        hirClass.fields.forEach { field ->
            if (!isRenamablePrivateField(field)) return@forEach
            var candidate: String
            do {
                candidate = "vortexField$fieldCounter"
                fieldCounter++
            } while ("$candidate:${field.type.descriptor}" in fieldNames)
            fieldNames += "$candidate:${field.type.descriptor}"
            fieldRenames[RenamePlan.fieldKey(owner, field.name, field.type.descriptor)] = candidate
        }
    }

    private fun isRenamablePrivateMethod(method: HirMethod): Boolean {
        if (method.name.startsWith("<")) return false
        if (method.accessFlags and AccessFlags.PRIVATE == 0) return false
        if (AccessFlags.isAbstractOrNative(method.accessFlags)) return false
        return looksObfuscated(method.name)
    }

    private fun isRenamablePrivateField(field: HirField): Boolean {
        if (field.accessFlags and AccessFlags.PRIVATE == 0) return false
        return looksObfuscated(field.name)
    }

    companion object {
        /** Packages whose names must never be touched, however short they are. */
        private val LIBRARY_PREFIXES = listOf(
            "java/", "javax/", "jdk/", "sun/", "com/sun/", "org/w3c/", "org/xml/",
            "android/", "androidx/", "dalvik/", "java/lang/",
            "kotlin/", "kotlinx/", "org/jetbrains/", "org/intellij/",
            "com/google/", "org/apache/", "org/objectweb/", "org/json/",
        )

        /**
         * Obfuscated names are one or two characters, sometimes with a trailing digit (`a`, `ab`,
         * `a1`, `zz`). Matching only that shape keeps the plugin from renaming real short names such
         * as `id`, `x`, `io` or `key`.
         */
        private val OBFUSCATED_NAME = Regex("[a-zA-Z]{1,2}[0-9]?")

        fun looksObfuscated(name: String): Boolean = OBFUSCATED_NAME.matches(name)
    }
}
