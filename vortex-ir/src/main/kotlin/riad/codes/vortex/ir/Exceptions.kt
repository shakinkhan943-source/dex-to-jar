package riad.codes.vortex.ir

/** Base type for every failure raised by the Vortex translation engine. */
open class VortexException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** A type descriptor in a DEX file could not be parsed. */
class TypeDescriptorException(message: String) : VortexException(message)

/** The input was not a readable DEX/APK/OAT container. */
class DexFormatException(message: String, cause: Throwable? = null) : VortexException(message, cause)

/** A DEX feature exists but Vortex deliberately does not translate it yet. */
class UnsupportedDexFeatureException(message: String) : VortexException(message)

/** A method could not be translated into verifiable JVM bytecode. */
class TranslationException(message: String, cause: Throwable? = null) : VortexException(message, cause)

/** Emitted bytecode failed the JVM/ASM verification pass. */
class BytecodeVerificationException(message: String) : VortexException(message)
