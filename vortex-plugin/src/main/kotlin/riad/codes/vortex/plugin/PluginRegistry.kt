package riad.codes.vortex.plugin

import java.util.ServiceLoader

/**
 * The set of plugins a run may use.
 *
 * Plugins are found two ways: the built-ins are always available, and anything on the classpath that
 * registers itself as a `java.util.ServiceLoader` provider of [VortexPlugin] is picked up. Discovered
 * plugins are opt-in — they run only when named with `--plugin <id>` — so adding a jar to the
 * classpath can never silently change a translation.
 */
class PluginRegistry private constructor(private val plugins: Map<String, VortexPlugin>) {

    fun ids(): List<String> = plugins.keys.sorted()

    fun all(): List<VortexPlugin> = ids().map { plugins.getValue(it) }

    fun byId(id: String): VortexPlugin? = plugins[id]

    /**
     * Resolves the requested plugin ids, failing on an unknown one.
     *
     * A typo in `--plugin` must not silently produce an untransformed jar, so an unknown id is an
     * error that names the ids that do exist.
     */
    fun select(requested: List<String>): List<VortexPlugin> {
        if (requested.isEmpty()) return emptyList()
        return requested.map { id ->
            byId(id) ?: throw IllegalArgumentException(
                "unknown plugin '$id'; available plugins: ${ids().joinToString()}",
            )
        }
    }

    fun with(vararg extra: VortexPlugin): PluginRegistry =
        PluginRegistry(plugins + extra.associateBy { it.id })

    companion object {
        /** The plugins the engine ships with. */
        fun builtIn(): PluginRegistry = PluginRegistry(
            listOf(
                ObfuscationStripperPlugin(),
                KotlinMetadataPlugin(),
            ).associateBy { it.id },
        )

        /** Built-ins plus every [VortexPlugin] service on [loader]'s classpath. */
        fun discover(loader: ClassLoader = PluginRegistry::class.java.classLoader): PluginRegistry {
            val discovered = ServiceLoader.load(VortexPlugin::class.java, loader)
            val builtIn = builtIn()
            return PluginRegistry(builtIn.all().associateBy { it.id } + discovered.associateBy { it.id })
        }

        fun of(vararg plugins: VortexPlugin): PluginRegistry = PluginRegistry(plugins.associateBy { it.id })
    }
}
