# vortex-dex

A DEX-to-JVM translation engine. It reads Dalvik bytecode — a raw `classes.dex`, a multi-dex
`.apk`/`.jar`/`.zip` — and writes a JVM jar whose classes load, verify and run on a stock JDK.

The engine is built around three ideas that the older tools in this space get wrong:

* **A type-safe intermediate representation.** Dalvik bytecode is never translated straight to class
  files. It becomes a high-level IR (classes, methods, decoded instructions, addresses preserved),
  then a low-level IR (basic blocks, register *versions*, local slots), and only then bytecode.
* **Abstract interpretation instead of heuristics.** Dalvik registers are untyped and get reused for
  values of different types, while a JVM local slot is typed for the whole method. Which register
  becomes which slot — and whether `aget` is `iaload`, `baload` or `aaload` — is decided by running a
  fixpoint over the control flow graph, not by pattern matching on opcodes.
* **Verification as a first-class result.** Every emitted class is run through ASM's checker, and the
  report says exactly what could not be translated. A method the engine cannot handle becomes a
  throwing stub that is *named in the report*, never silently wrong code.

## Quick start

Requires **JDK 17** (enforced by the Gradle toolchain); the committed wrapper pins Gradle 8.14.6.

```bash
./gradlew build                                                    # compiles, runs 30 tests, packages every module
./gradlew :vortex-cli:installDist                                  # packages the CLI
vortex-cli/build/install/vortex/bin/vortex translate app.apk -o app.jar
```

```bash
vortex translate <input> [-o out.jar] [-p <plugin>]... [-j <threads>]
                        [--no-verify] [--no-debug-info] [--fail-fast] [--strict] [-v]
vortex inspect <input> [--classes]      # DEX headers and class/method counts, writes nothing
vortex plugins                          # ids you can pass to --plugin
```

Exit codes are part of the contract:

| Code | Meaning |
| :--- | :--- |
| `0` | The jar was written. |
| `2` | `--strict` was set and something had to be stubbed or failed. The jar is still written. |
| `1` | The translation could not run at all; one line explains why. |

A run prints a report rather than a wall of logs:

```
vortex-dex translation
  input      : classes.dex (DEX)
  output     : out.jar
  classes    : 4 emitted, 0 failed, 4 total
  methods    : 29, 0 replaced by stubs
  verified   : yes
  duration   : 468ms on 4 thread(s)
```

## Using it as a dependency (JitPack)

Every module is built and published from a git tag by [JitPack](https://jitpack.io/#shakinkhan943-source/dex-to-jar),
so there is nothing to release by hand: pick a tag, add the repository, depend on the module.

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.github.shakinkhan943-source.dex-to-jar:vortex-engine:v0.1.0")
}
```

JitPack's coordinates for a multi-module build are `com.github.<user>.<repo>:<module>:<tag>`, where
`<module>` is the Gradle project name — that is the coordinate to use for one module
(`vortex-ir`, `vortex-dex`, `vortex-analysis`, `vortex-emit`, `vortex-plugin`, `vortex-engine`,
`vortex-cli`), and `com.github.shakinkhan943-source:dex-to-jar:v0.1.0` pulls in all of them at once.
Source jars are published alongside the binaries, and the artifacts target JVM 17, so consumers run
on JDK 17 or newer.

## How it works

```
input (.dex, .apk, .jar, .zip)
      │
      ▼  DexIngestion       memory-mapped header probe; classifies the container, lists DEX entries
┌───────────────┐
│  discovery    │
└───────────────┘
      │
      ▼  DexLoader + DexToHir      per DEX entry, in parallel          [coroutines, Dispatchers.IO + Default]
┌───────────────┐
│      HIR      │  HirClass / HirMethod / HirInstruction  (+ addresses, try blocks, debug info)
└───────────────┘
      │
      ▼  merge      (duplicate classes across DEX entries: the first wins, the rest are reported)
      ▼  plugins    (program pass)
┌───────────────┐
│   analysis    │  CfgBuilder → MethodTypeInference → RegisterVersioning → Liveness → RegisterAllocator
└───────────────┘
      │
      ▼  MethodLowering        HIR → LIR (basic blocks of slot-based instructions)
┌───────────────┐
│      LIR      │
└───────────────┘
      │
      ▼  AsmClassEmitter       per class, in parallel, bounded by -j               [ASM ClassWriter]
      ▼  plugins               (bytecode pass)
      ▼  BytecodeVerifier      ASM CheckClassAdapter over the emitted bytes
      ▼  JarWriter             reproducible jar (fixed entry timestamps)
output jar + TranslationReport
```

| Module | Responsibility | Depends on |
| :--- | :--- | :--- |
| `vortex-ir` | Type system (descriptors, primitives, arrays, hierarchy), `Constant`, HIR, LIR, symbol references, access flags. No I/O, no ASM, no dexlib2. | — |
| `vortex-dex` | Container discovery, DEX header parsing, opcode decoding, `DexToHir`. Publishes DEX fixtures as test-jar. | `dexlib2`, `vortex-ir` |
| `vortex-analysis` | CFG construction, abstract interpretation, register versioning, liveness, slot allocation, HIR→LIR lowering, stack-height checking. | `vortex-ir` |
| `vortex-emit` | ASM class/method emission, annotation emission, DEX-aware frame computation, jar writing, bytecode verification. | `vortex-ir`, ASM |
| `vortex-plugin` | Plugin SPI plus the built-in `obfuscation-stripper` and `kotlin-metadata` plugins. | `vortex-ir`, `vortex-emit` |
| `vortex-engine` | The pipeline: discovery → parallel per-DEX loading → merge → plugin passes → parallel per-class analysis and emission → jar → report. | all of the above |
| `vortex-cli` | `vortex translate` / `inspect` / `plugins` (Clikt). | `vortex-engine` |

### Registers → local slots, without guessing

A Dalvik register is "a word". The same register can hold an `int` on one path and a `String` on
another, and DEX itself is ambiguous in places that matter:

* `const/4 v0, #0` is the `int` `0` **and** `null`.
* `const/high16 v0, #0x40200000` is the `int` 1075838976 **and** the `float` `2.5f`.
* `const-wide/high16 v0, #0x4004000000000000` is a `long` **and** the `double` `2.5`.

`MethodTypeInference` runs the worklist fixpoint over the CFG with a lattice built for exactly this
(`AbstractType.Bits` and `AbstractType.Null` are lattice elements, not guesses). `RegisterVersioning`
then gives each register one *version per storage class* and resolves the ambiguous ones from the
first use that demands a type — a field of known type, a callee parameter, a `move-object`, a
`mul-double`. Each version gets its own local slot from `RegisterAllocator`, and the raw constant
payload is *reinterpreted* rather than converted, because that is what the DEX meant.

The rest follows from the same pass: `aget` becomes `iaload`/`caload`/`aaload` according to the
inferred array type, `if-eqz` becomes `ifeq` or `ifnull`, and `RegisterAccess.expectedType` is the
single place that says what an instruction demands from a register.

Other details that a DEX-to-JVM translation lives or dies by:

* **Switch defaults.** A `packed-switch`/`sparse-switch` offset points at the payload *table*; an
  unmatched value falls through to the instruction after the switch. Case offsets are relative to the
  switch instruction, not to the payload.
* **Array literals.** `filled-new-array` and the `fill-array-data` payload both become a
  literal-length allocation followed by per-element stores. The element count and indices are
  constants of the instruction, so no DEX register is invented that the original never had.
* **Debug information.** `LocalVariableTable` and `LineNumberTable` are rebuilt from DEX debug info
  so decompilers print real names and line numbers. Rows are de-duplicated, because HotSpot rejects a
  whole class over two rows that claim the same slot at the same offset.
* **Opcode table.** DEX is decoded with a modern opcode table (see
  `IngestionOptions.MODERN_OPCODES`). `dexlib2`'s default describes API 20, which reads `0xfa` as
  `invoke-super/quick` instead of `invoke-polymorphic` and has no entry at all for
  `const-method-type`, `const-method-handle` or `invoke-custom`.
* **Reproducibility.** Jar entries carry a fixed timestamp, so translating the same input twice
  produces byte-identical output — asserted by a test.

## Verification

Two levels, because they answer different questions.

**Structural, on every run.** `BytecodeVerifier` runs ASM's `CheckClassAdapter` over each emitted
class. Its flow analysis asks a class loader whether one type is assignable to another, and the types
in question exist only in the output being built, so it is given a loader that answers from the DEX
graph: every type the graph knows becomes an empty class carrying the real supertypes and flags. That
is all the checker uses; a type that is neither in the graph nor on the toolpath still fails to load
and is reported instead of being assumed compatible. Frame computation uses the same trick
(`DexAwareClassWriter`), so it never calls `Class.forName` on an `android/*` type.

**Ground truth, in tests.** The test suite defines the emitted classes through a real `ClassLoader`
and invokes them, which runs the JVM's own verifier over the bytecode. A wrong local slot, a missing
stack map frame or an unbalanced stack fails there.

## What is translated, and what is not

Translated: the arithmetic/bitwise/conversion/compare families, all `move` forms, constants
(including method handles and method types), branches and both switch forms, `fill-array-data`,
`filled-new-array` (35c and range), array access, instance/static field access, every invoke form
except `invoke-custom`, `new-instance`/`new-array`, `check-cast`, `instance-of`, monitors, `throw`,
try/catch, `move-exception`, and `nop`.

Deliberately not translated, each for a concrete reason:

| Opcode | Why | What happens |
| :--- | :--- | :--- |
| `invoke-custom`, `invoke-custom/range` | Call sites need bootstrap-method rewriting; the JVM equivalent is a different class-file shape. | Method becomes a stub. |
| `throw-verification-error` | Only meaningful to a Dalvik runtime. | Method becomes a stub. |
| `return-void-no-barrier` | ART bookkeeping with no JVM counterpart. | Method becomes a stub. |
| Payload pseudo-instructions | Data, not code. | Consumed by the instruction that points at them. |

A stubbed method is **present** in the jar, carries the right signature, and throws
`UnsupportedOperationException` naming the opcode when called. The class stays valid, the report lists
the method, and `--fail-fast` (abort) or `--strict` (exit `2`) turn that into a hard signal for a
build pipeline.

## Plugins

`VortexPlugin` has two hooks: a **program pass** that rewrites the merged HIR (renaming, dropping,
whole-program facts) and a **bytecode pass** that rewrites each emitted class. Implementations are
found through `ServiceLoader`, so a plugin on the classpath (`vortex plugins` lists them) is usable
from `--plugin <id>`; an unknown id fails with the list of known ids rather than being ignored.

* `obfuscation-stripper` — gives obfuscated single-character classes deterministic names
  (`VortexClass1`, `VortexClass2`, …) and rewrites every reference, including private member access.
  The plan is a pure function of the input, so re-running produces a byte-identical jar.
* `kotlin-metadata` — keeps `kotlin.Metadata` alive in the output so Kotlin-aware decompilers still
  recognise the original language, counts what it found, and reports it.

## Layout

```
vortex-ir/         type system, constants, HIR, LIR
vortex-dex/        containers, DEX parsing, opcode decoding, DexToHir  (+ test fixtures)
  src/testFixtures/…/DexFixtures.kt    builds real DEX files in memory for tests
vortex-analysis/   CFG, inference, versioning, liveness, allocation, lowering
vortex-emit/       ASM emission, frames, jar writing, verification
vortex-plugin/     plugin SPI and the built-in plugins
vortex-engine/     the pipeline and the translation report
vortex-cli/        the CLI
```

## Releasing

The tag *is* the release. Pushing a `v*` tag runs `.github/workflows/release.yml`, which builds and
tests the tagged commit, attaches every module jar and the packaged CLI to a generated GitHub Release,
and asks JitPack to start building that tag so the first consumer does not wait for it. JitPack
resolves the new version, and the tag name is the version consumers write in their build file.

Bump `version` in the root `build.gradle.kts` before tagging so the POMs and the tag agree.
`.github/workflows/ci.yml` builds and tests every push and pull request.

## Status

Phases 1–5 of the original plan are implemented: the module skeleton and CLI, the HIR and CFG, the
type-inference and allocation engine, the ASM emitter with jar packaging and verification, and the
coroutine pipeline with the plugin SPI.

The end-to-end claim is verified outside the test suite as well: a Java program is compiled with
`javac`, converted to a real DEX with `d8`, translated by `vortex`, and the resulting jar is executed
and diffed against the same program running from `javac` output — for a single `classes.dex` and for
a two-entry multi-dex zip. All outputs match; nothing is stubbed, and every class verifies.
