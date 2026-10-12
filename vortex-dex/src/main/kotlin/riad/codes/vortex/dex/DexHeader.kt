package riad.codes.vortex.dex

import riad.codes.vortex.ir.DexFormatException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** What kind of container the input file turned out to be. */
enum class DexContainerKind(val displayName: String) {
    DEX("DEX"),
    ZIP("ZIP/APK/JAR"),
    OAT("OAT"),
    VDEX("VDEX"),
    ODEX("ODEX"),
    UNKNOWN("unknown"),
    ;

    /** Containers vortex can translate today. VDEX/ODEX hold optimised, non-standard code items. */
    val isTranslatable: Boolean get() = this == DEX || this == ZIP || this == OAT
}

/**
 * The 112 byte DEX header, read straight out of a memory-mapped [FileChannel].
 *
 * The header is the cheapest possible validity check: a translated jar built from a truncated or
 * mis-detected input is worse than a clean failure, so the engine reads and validates the magic,
 * the declared file size and the map offset before handing anything to `dexlib2`.
 */
data class DexHeader(
    val magic: String,
    val version: Int,
    val checksum: Long,
    val signature: ByteArray,
    val fileSize: Long,
    val headerSize: Int,
    val endianTag: Long,
    val mapOffset: Long,
    val stringIdsSize: Int,
    val typeIdsSize: Int,
    val protoIdsSize: Int,
    val fieldIdsSize: Int,
    val methodIdsSize: Int,
    val classDefsSize: Int,
    val dataSize: Long,
) {
    val isLittleEndian: Boolean get() = endianTag == ENDIAN_CONSTANT

    val magicVersion: String get() = "DEX $version"

    /** `036`/`037` are Dalvik-era, `038`+ are ART (Android 8 and newer). */
    val isArtFormat: Boolean get() = version >= 38

    override fun toString(): String =
        "$magicVersion classes=$classDefsSize methods=$methodIdsSize fields=$fieldIdsSize types=$typeIdsSize"

    override fun equals(other: Any?): Boolean = this === other || (other is DexHeader && other.magic == magic && other.version == version)

    override fun hashCode(): Int = magic.hashCode() * 31 + version

    companion object {
        const val HEADER_SIZE = 112
        const val MAGIC_SIZE = 8
        const val ENDIAN_CONSTANT = 0x12345678L
        const val REVERSE_ENDIAN_CONSTANT = 0x78563412L

        const val DEX_MAGIC = "dex\n"
        const val VDEX_MAGIC = "vdex"
        const val ODEX_MAGIC = "dey\n"

        /** `FileChannel.map` window used for header sniffing: never loads the whole archive. */
        private const val SNIFF_WINDOW = 4096L

        /**
         * Reads the header of [path] without reading the whole file into heap memory.
         *
         * @throws DexFormatException when the file is not a DEX container.
         */
        @Throws(IOException::class)
        fun read(path: Path): DexHeader {
            FileChannel.open(path, StandardOpenOption.READ).use { channel ->
                val size = channel.size()
                if (size < MAGIC_SIZE) throw DexFormatException("$path is too small (${size} bytes) to be a DEX container")
                val window = minOf(size, SNIFF_WINDOW)
                val buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, window)
                val kind = sniff(buffer)
                if (kind != DexContainerKind.DEX) throw DexFormatException(describeNonDex(path, kind))
                if (size < HEADER_SIZE) throw DexFormatException("$path has a DEX magic but only $size bytes; the header needs $HEADER_SIZE")
                return parseHeader(buffer, expectedFileSize = size)
            }
        }

        /** Classifies a file by its first bytes, mapping only a small window of it. */
        @Throws(IOException::class)
        fun sniff(path: Path): DexContainerKind {
            FileChannel.open(path, StandardOpenOption.READ).use { channel ->
                val size = channel.size()
                if (size < 4) return DexContainerKind.UNKNOWN
                val window = minOf(size, SNIFF_WINDOW)
                return sniff(channel.map(FileChannel.MapMode.READ_ONLY, 0, window))
            }
        }

        /** Classifies a file by its first bytes, using the already-mapped [buffer]. */
        fun sniff(buffer: ByteBuffer): DexContainerKind {
            if (buffer.remaining() < 4) return DexContainerKind.UNKNOWN
            val magic = ByteArray(minOf(MAGIC_SIZE, buffer.remaining()))
            buffer.duplicate().get(magic)
            val ascii = String(magic, Charsets.ISO_8859_1)
            return when {
                ascii.startsWith(DEX_MAGIC) -> DexContainerKind.DEX
                ascii.startsWith(VDEX_MAGIC) -> DexContainerKind.VDEX
                ascii.startsWith(ODEX_MAGIC) -> DexContainerKind.ODEX
                ascii.startsWith("PK\u0003\u0004") -> DexContainerKind.ZIP
                ascii.startsWith("oat\n") -> DexContainerKind.OAT
                else -> DexContainerKind.UNKNOWN
            }
        }

        private fun describeNonDex(path: Path, kind: DexContainerKind): String = when (kind) {
            DexContainerKind.VDEX ->
                "$path is a VDEX container, not a plain DEX file. VDEX files hold ART-optimised code items with " +
                    "arrays whose element type comes from the runtime; extract the embedded `dex` section first."

            DexContainerKind.ODEX ->
                "$path is an ODEX (optimised DEX) file. ODEX rewrites opcodes for a specific device and is not portable; " +
                    "run `deodex` or use a VDEX/extracted DEX instead."

            DexContainerKind.ZIP -> "$path is a ZIP/APK container; use it as an archive input so its classes*.dex entries are enumerated"
            DexContainerKind.OAT -> "$path is an OAT container; use it as an archive input so its embedded dex files are enumerated"
            else -> "$path is not a DEX file (unknown magic)"
        }

        /**
         * Decodes the 112 byte header.
         *
         * [expectedFileSize] is the number of bytes actually available (`null` when only the header
         * window was mapped, for example a `classes.dex` entry inside an APK).
         */
        internal fun parseHeader(buffer: ByteBuffer, expectedFileSize: Long?): DexHeader {
            val order = ByteOrder.LITTLE_ENDIAN
            val view = buffer.duplicate().order(order)
            val magicBytes = ByteArray(MAGIC_SIZE)
            view.get(magicBytes)
            val magic = String(magicBytes, 0, 4, Charsets.US_ASCII)
            val version = try {
                String(magicBytes, 4, 3, Charsets.US_ASCII).trim('\u0000').toInt()
            } catch (_: NumberFormatException) {
                0
            }
            view.position(8)
            val checksum = view.int.toLong() and 0xFFFFFFFFL
            val signature = ByteArray(20)
            view.get(signature)
            view.position(32)
            val declaredFileSize = view.int.toLong() and 0xFFFFFFFFL
            val headerSize = view.int
            val endianTag = view.int.toLong() and 0xFFFFFFFFL
            view.int // link_size
            view.int // link_off
            val mapOffset = view.int.toLong() and 0xFFFFFFFFL
            val stringIdsSize = view.int
            view.int
            val typeIdsSize = view.int
            view.int
            val protoIdsSize = view.int
            view.int
            val fieldIdsSize = view.int
            view.int
            val methodIdsSize = view.int
            view.int
            val classDefsSize = view.int
            view.int
            val dataSize = view.int.toLong() and 0xFFFFFFFFL
            view.int

            if (expectedFileSize != null && declaredFileSize != expectedFileSize) {
                throw DexFormatException(
                    "DEX header declares $declaredFileSize bytes but the file holds $expectedFileSize; the container is truncated",
                )
            }
            if (headerSize != HEADER_SIZE) {
                throw DexFormatException("unexpected DEX header_size $headerSize (expected $HEADER_SIZE)")
            }
            if (endianTag != ENDIAN_CONSTANT && endianTag != REVERSE_ENDIAN_CONSTANT) {
                throw DexFormatException("invalid DEX endian_tag 0x${endianTag.toString(16)}")
            }
            if (mapOffset == 0L) throw DexFormatException("DEX header has no map_off; the file has no map list")

            return DexHeader(
                magic = magic,
                version = version,
                checksum = checksum,
                signature = signature,
                fileSize = declaredFileSize,
                headerSize = headerSize,
                endianTag = endianTag,
                mapOffset = mapOffset,
                stringIdsSize = stringIdsSize,
                typeIdsSize = typeIdsSize,
                protoIdsSize = protoIdsSize,
                fieldIdsSize = fieldIdsSize,
                methodIdsSize = methodIdsSize,
                classDefsSize = classDefsSize,
                dataSize = dataSize,
            )
        }
    }
}

/** The DEX `map_list` entry types the engine cares about, used for reports. */
object DexMapItemType {
    const val HEADER_ITEM = 0x0000
    const val STRING_ID_ITEM = 0x0001
    const val TYPE_ID_ITEM = 0x0002
    const val PROTO_ID_ITEM = 0x0003
    const val FIELD_ID_ITEM = 0x0004
    const val METHOD_ID_ITEM = 0x0005
    const val CLASS_DEF_ITEM = 0x0006
    const val CALL_SITE_ID_ITEM = 0x0007
    const val METHOD_HANDLE_ITEM = 0x0008
    const val MAP_LIST = 0x1000
    const val TYPE_LIST = 0x1001
    const val ANNOTATION_SET_REF_LIST = 0x1002
    const val ANNOTATION_SET_ITEM = 0x1003
    const val CLASS_DATA_ITEM = 0x2000
    const val CODE_ITEM = 0x2001
    const val STRING_DATA_ITEM = 0x2002
    const val DEBUG_INFO_ITEM = 0x2003
    const val ANNOTATION_ITEM = 0x2004
    const val ENCODED_ARRAY_ITEM = 0x2005
    const val ANNOTATIONS_DIRECTORY_ITEM = 0x2006
    const val HIDDENAPI_CLASS_DATA_ITEM = 0xF000
}
