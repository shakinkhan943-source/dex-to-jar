package riad.codes.vortex.dex

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.DexFile
import com.android.tools.smali.dexlib2.iface.MultiDexContainer
import riad.codes.vortex.ir.DexFormatException
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One DEX file found inside the input, before it has been parsed. */
data class DexArchiveEntry(val name: String, val dexIndex: Int)

/** The result of inspecting an input: where the DEX files are and how big the source is. */
data class DexInput(
    val path: Path,
    val kind: DexContainerKind,
    val sourceName: String,
    val entries: List<DexArchiveEntry>,
    /** Present for a raw DEX file, `null` for entries inside an archive. */
    val header: DexHeader?,
) {
    val isMultiDex: Boolean get() = entries.size > 1

    fun entryLabel(entry: DexArchiveEntry): String = entry.name.ifEmpty { sourceName }
}

/** Knobs for discovery and loading. */
data class IngestionOptions(
    /** `dexlib2` opcode set used when parsing. See [MODERN_OPCODES] for why it is not `dexlib2`'s default. */
    val opcodes: Opcodes = MODERN_OPCODES,
) {
    companion object {
        /**
         * The Android API level whose opcode table the engine decodes with.
         *
         * API 26 (DEX 038) is where `invoke-custom` and `return-void-no-barrier` appear and where
         * `0xfa` becomes `invoke-polymorphic`, and API 28 (DEX 039) adds `const-method-handle` and
         * `const-method-type`. 34 is the newest level `dexlib2` 3.0.10 can *read* a file written at:
         * its API 35 table describes the DEX 041 container layout and rejects a plain file written by
         * its own writer, so a higher constant would break every round trip.
         */
        private const val API_LEVEL = 34

        /**
         * The opcode table used to decode a DEX.
         *
         * `dexlib2`'s own default describes API 20, from before DEX 038. Decoding a file from any
         * modern toolchain against it is not merely incomplete, it is *wrong*: opcode `0xfa` is
         * `invoke-polymorphic` from API 26 onwards but `invoke-super/quick` in that older table, and
         * opcodes that the older table has no entry for — `const-method-type`, `const-method-handle`,
         * `invoke-custom` — decode to a `null` opcode, which the engine can only report as an unreadable
         * byte instead of as a named instruction it does not translate.
         *
         * Decoding with the newest table costs nothing: opcode values are stable across API levels, and a
         * newer table only ever adds entries.
         */
        val MODERN_OPCODES: Opcodes = Opcodes.forApi(API_LEVEL)

        // Declared last on purpose: companion properties initialise in declaration order, so anything
        // used as a default argument below has to be initialised before it.
        val DEFAULT = IngestionOptions()
    }
}

/**
 * Discovers DEX files and loads them through `dexlib2`.
 *
 * Discovery reads only the first 4 KiB of a file (via a memory-mapped [FileChannel]) to classify it,
 * so even a 2 GiB APK is not read to find out what it is. Archives are then enumerated from the ZIP
 * central directory. Loading is lazy and per-entry, which is what lets
 * [riad.codes.vortex.engine.TranslationEngine] parse multi-dex inputs on several cores at once.
 */
class DexIngestion(private val options: IngestionOptions = IngestionOptions.DEFAULT) {

    /** Reads just enough of [path] to classify it and enumerate its DEX entries. */
    suspend fun discover(path: Path): DexInput = withContext(Dispatchers.IO) { discoverBlocking(path) }

    fun discoverBlocking(path: Path): DexInput {
        if (!Files.exists(path)) throw DexFormatException("input does not exist: $path")
        if (Files.isDirectory(path)) throw DexFormatException("input is a directory, not a DEX container: $path")
        if (!Files.isReadable(path)) throw DexFormatException("input is not readable: $path")

        val sourceName = path.fileName?.toString() ?: path.toString()
        val kind = try {
            DexHeader.sniff(path)
        } catch (error: IOException) {
            throw DexFormatException("cannot read $path: ${error.message}", error)
        }

        return when (kind) {
            DexContainerKind.DEX -> {
                val header = DexHeader.read(path)
                DexInput(
                    path = path,
                    kind = kind,
                    sourceName = sourceName,
                    entries = listOf(DexArchiveEntry(name = "", dexIndex = 0)),
                    header = header,
                )
            }

            DexContainerKind.ZIP -> DexInput(
                path = path,
                kind = kind,
                sourceName = sourceName,
                entries = listZipDexEntries(path),
                header = null,
            )

            DexContainerKind.OAT -> DexInput(
                path = path,
                kind = kind,
                sourceName = sourceName,
                entries = listContainerDexEntries(path),
                header = null,
            )

            DexContainerKind.VDEX -> throw DexFormatException(
                "$sourceName is a VDEX container, not a plain DEX file: VDEX holds ART-optimised code " +
                    "items whose array element types come from the runtime. Extract the embedded `dex` section first.",
            )

            DexContainerKind.ODEX -> throw DexFormatException(
                "$sourceName is an ODEX (optimised DEX) file: opcodes are rewritten for one device build and " +
                    "are not portable. Deodex it or use a VDEX/extracted DEX instead.",
            )

            DexContainerKind.UNKNOWN -> throw DexFormatException(
                "$sourceName is not a DEX, APK, JAR, OAT or VDEX container (unrecognised magic).",
            )
        }
    }

    /** `classes.dex`, `classes2.dex`, … in numeric order, plus any other `*.dex` entry. */
    private fun listZipDexEntries(path: Path): List<DexArchiveEntry> {
        val names = try {
            ZipFile(path.toFile()).use { zip ->
                zip.entries().iterator().asSequence()
                    .map { it.name }
                    .filter { !it.endsWith("/") && it.lowercase().endsWith(".dex") }
                    .distinct()
                    .toList()
            }
        } catch (error: IOException) {
            throw DexFormatException("cannot read the zip directory of $path: ${error.message}", error)
        }
        if (names.isEmpty()) throw DexFormatException("$path is a zip archive but holds no .dex entries")
        return names
            .sortedWith(compareBy({ dexEntryOrdinal(it) }, { it }))
            .mapIndexed { index, name -> DexArchiveEntry(name, index) }
    }

    private fun listContainerDexEntries(path: Path): List<DexArchiveEntry> {
        val names = try {
            DexFileFactory.loadDexContainer(path.toFile(), options.opcodes).dexEntryNames
        } catch (error: Exception) {
            throw DexFormatException("cannot enumerate the DEX entries of $path: ${error.message}", error)
        }
        if (names.isEmpty()) throw DexFormatException("$path holds no DEX entries")
        return names.mapIndexed { index, name -> DexArchiveEntry(name, index) }
    }

    companion object {
        private val CLASSES_DEX = Regex("^classes(\\d*)\\.dex$")

        /** `classes.dex` is index 0, `classes2.dex` index 1, and anything else sorts last by name. */
        private fun dexEntryOrdinal(name: String): Int {
            val simple = name.substringAfterLast('/')
            val match = CLASSES_DEX.find(simple) ?: return Int.MAX_VALUE
            val digits = match.groupValues[1]
            return if (digits.isEmpty()) 0 else digits.toIntOrNull() ?: Int.MAX_VALUE
        }
    }
}

/**
 * Loads DEX files on demand, caching one `dexlib2` container per input path.
 *
 * `dexlib2` reads a container's entry only when asked, so the cache makes repeated per-entry loads
 * cheap while keeping each entry's parse independent and safe to run from its own coroutine.
 *
 * The container's entry index is materialised inside the cache lookup, under the map's per-key lock.
 * `dexlib2` builds that index lazily on first use, so two entries of the same archive loaded on two
 * cores could otherwise race and one of them would see a half-populated index — which shows up as a
 * spurious "no DEX entry named classes2.dex". Building it once, before any parallel load, removes the
 * race instead of hoping for the right interleaving.
 */
class DexLoader(private val options: IngestionOptions = IngestionOptions.DEFAULT) {

    private val containers = ConcurrentHashMap<Path, MultiDexContainer<out DexBackedDexFile>>()

    fun load(input: DexInput, entry: DexArchiveEntry): DexFile {
        return when {
            input.header != null -> loadRawDex(input.path)
            else -> loadContainerEntry(input.path, entry.name)
        }
    }

    private fun loadRawDex(path: Path): DexFile = try {
        DexFileFactory.loadDexFile(path.toFile(), options.opcodes)
    } catch (error: Exception) {
        throw DexFormatException("cannot parse $path as a DEX file: ${error.message}", error)
    }

    private fun loadContainerEntry(path: Path, entryName: String): DexFile {
        val container = try {
            containers.computeIfAbsent(path) { file ->
                val opened = DexFileFactory.loadDexContainer(file.toFile(), options.opcodes)
                // Force the lazy entry index now: this lambda runs under the map's per-key lock, so no
                // other thread can observe the container until the index is complete.
                opened.dexEntryNames
                opened
            }
        } catch (error: Exception) {
            throw DexFormatException("cannot open $path as a multi-dex container: ${error.message}", error)
        }
        val entry = container.getEntry(entryName)
            ?: throw DexFormatException("$path has no DEX entry named '$entryName'")
        return try {
            entry.dexFile
        } catch (error: Exception) {
            throw DexFormatException("cannot parse '$entryName' inside $path: ${error.message}", error)
        }
    }

    /** Number of Dex entries already opened, for diagnostics. */
    fun openContainerCount(): Int = containers.size
}

/** Reads the raw bytes of a DEX entry without loading the whole archive, used by the inspector CLI. */
@Throws(IOException::class)
fun readEntryHeader(path: Path, entryName: String): DexHeader? {
    if (entryName.isEmpty()) return DexHeader.read(path)
    val bytes = ZipFile(path.toFile()).use { zip ->
        val entry = zip.getEntry(entryName) ?: return null
        zip.getInputStream(entry).use { stream -> stream.readNBytes(DexHeader.HEADER_SIZE) }
    }
    if (bytes.size < DexHeader.HEADER_SIZE) return null
    val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    // Only the header window was read, so the declared file size is not checked here.
    return DexHeader.parseHeader(buffer, expectedFileSize = null)
}

/** Maps a whole DEX file for callers that want its bytes without a stream copy. */
@Throws(IOException::class)
fun mapDexFile(path: Path): java.nio.ByteBuffer =
    FileChannel.open(path, StandardOpenOption.READ).use { channel ->
        channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
    }
