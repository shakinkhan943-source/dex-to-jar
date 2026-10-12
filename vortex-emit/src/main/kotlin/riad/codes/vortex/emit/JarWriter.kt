package riad.codes.vortex.emit

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.CRC32
import java.util.zip.ZipEntry

/** One file to place in the output jar. */
class JarEntryData(val path: String, val bytes: ByteArray) {
    override fun toString(): String = "$path (${bytes.size} bytes)"
}

/** Packaging options. */
data class JarOptions(
    val compress: Boolean = true,

    /**
     * Timestamp written into every entry.
     *
     * A fixed time is what makes translation reproducible: two runs over the same DEX produce
     * byte-identical jars, so a build can be cached and diffed. `0` is the DOS epoch (1980-01-01).
     */
    val fixedTimeMillis: Long = 0L,

    val includeManifest: Boolean = true,

    val manifestAttributes: Map<String, String> = emptyMap(),
) {
    companion object {
        val DEFAULT = JarOptions()

        const val MANIFEST_PATH = "META-INF/MANIFEST.MF"
    }
}

/** What was written, including anything that had to be dropped. */
data class JarWriteResult(
    val path: Path,
    val sizeBytes: Long,
    val entryCount: Int,
    /** Entries dropped because another entry with the same path came first. */
    val skippedDuplicates: List<String>,
)

/**
 * Writes the translated classes into a jar.
 *
 * Multi-dex input is the interesting case: the same class can legitimately appear in two dex files
 * (a repackaged APK, a patched secondary dex). The first occurrence wins and the rest are reported in
 * [JarWriteResult.skippedDuplicates], because silently overwriting an entry would make the output
 * depend on iteration order.
 *
 * The manifest is written by hand rather than through `JarOutputStream(stream, manifest)` so that its
 * timestamp is the fixed one too — the JDK's manifest writing otherwise stamps it with the current
 * time and defeats reproducibility.
 */
class JarWriter(private val options: JarOptions = JarOptions.DEFAULT) {

    fun write(target: Path, entries: List<JarEntryData>): JarWriteResult {
        target.toAbsolutePath().parent?.let { Files.createDirectories(it) }

        val seen = HashSet<String>(entries.size * 2)
        val duplicates = ArrayList<String>()
        val ordered = entries
            .sortedBy { it.path }
            .filter { entry ->
                if (seen.add(entry.path)) {
                    true
                } else {
                    duplicates += entry.path
                    false
                }
            }

        Files.newOutputStream(target).use { fileStream ->
            BufferedOutputStream(fileStream, BUFFER_SIZE).use { buffered ->
                JarOutputStream(buffered).use { jar ->
                    if (options.includeManifest) {
                        putEntry(jar, JarOptions.MANIFEST_PATH, renderManifest())
                    }
                    ordered.forEach { entry -> putEntry(jar, entry.path, entry.bytes) }
                }
            }
        }

        return JarWriteResult(
            path = target,
            sizeBytes = Files.size(target),
            entryCount = ordered.size + if (options.includeManifest) 1 else 0,
            skippedDuplicates = duplicates,
        )
    }

    private fun putEntry(jar: JarOutputStream, path: String, bytes: ByteArray) {
        val entry = JarEntry(path)
        entry.time = options.fixedTimeMillis
        if (!options.compress) {
            val crc = CRC32().apply { update(bytes) }
            entry.method = ZipEntry.STORED
            entry.size = bytes.size.toLong()
            entry.compressedSize = bytes.size.toLong()
            entry.crc = crc.value
        }
        jar.putNextEntry(entry)
        jar.write(bytes)
        jar.closeEntry()
    }

    private fun renderManifest(): ByteArray {
        val manifest = Manifest()
        val attributes = manifest.mainAttributes
        attributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        attributes[Attributes.Name("Created-By")] = "vortex-dex $VERSION"
        options.manifestAttributes.forEach { (key, value) ->
            // Attribute names are restricted to [a-zA-Z0-9_-]; a bad name would abort packaging.
            if (ATTRIBUTE_NAME.matches(key)) {
                attributes[Attributes.Name(key)] = value
            }
        }
        val out = ByteArrayOutputStream()
        manifest.write(out)
        return out.toByteArray()
    }

    companion object {
        const val VERSION = "0.1.0"

        private const val BUFFER_SIZE = 1 shl 16

        private val ATTRIBUTE_NAME = Regex("[A-Za-z0-9_-]{1,70}")
    }
}
