# JDK 21 Toolchain & FFM API Validation

Date: 2026-10-30
Status: VALIDATED (empirical, on this environment)

## Summary

OpenJDK **21.0.2** was installed alongside the existing JDK 17 and set as the
default via `update-alternatives`. The Foreign Function & Memory (FFM) API was
empirically exercised on JDK 21 to settle the "preview or not" question with
hard evidence instead of assumptions.

## Environment

| Item              | Value                                              |
|-------------------|----------------------------------------------------|
| Installed JDK     | `/opt/java/jdk-21.0.2` (OpenJDK 21.0.2+13-58)      |
| Previous JDK      | OpenJDK 17.0.20 (kept available in `/usr/lib/jvm`) |
| Default switch    | `update-alternatives --set java/javac -> jdk-21`   |
| Project target    | Unchanged: `maven.compiler.release=17`             |
| Build result      | `mvn clean verify` -> BUILD SUCCESS, 33/33 tests   |

## FFM API status per JDK release (verified empirically here)

| JDK  | `java.lang.foreign` status | Evidence from this machine                                                                 |
|------|----------------------------|-------------------------------------------------------------------------------------------|
| 17   | **Does not exist**         | Package absent from `src.zip`; `javac --release 17` rejects the import entirely.          |
| 21   | **Second preview (JEP 442)** | Compiles only with `--enable-preview`; runtime also requires `--enable-preview`.         |
| 22+  | Final (JEP 454)            | Not tested here; documented for planning purposes.                                        |

### Preview-API shape differences observed on JDK 21 (JEP 442)

These are the exact deltas that broke code written against the final (22+) API:

1. `Arena.allocateFrom(String)` does **not** exist yet -> use
   `arena.allocateUtf8String("...")` instead.
2. Downcall handles returned by `Linker.downcallHandle(...)` expect the code
   address as an **extra leading argument**:
   `handle.invokeExact(MemorySegment.ofAddress(addr), arg1, arg2)`
   (in JDK 22+ the address is bound at handle creation and must NOT be passed).

### Working sanity check (JDK 21.0.2)

```java
// Compile: javac --release 21 --enable-preview FfmCheck.java
// Run:   java --enable-preview --enable-native-access=ALL-UNNAMED -cp . FfmCheck
Linker linker = Linker.nativeLinker();
long addr = linker.defaultLookup().find("strcmp").orElseThrow().address();
var handle = linker.downcallHandle(
    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
try (Arena arena = Arena.ofConfined()) {
    MemorySegment a = arena.allocateUtf8String("hello glyph");
    MemorySegment b = arena.allocateUtf8String("hello glyph");
    int cmp = (int) handle.invokeExact(MemorySegment.ofAddress(addr), a, b);
    // prints: FFM strcmp result: 0 -> FFM WORKS on JDK 21 with --enable-preview
}
```

Result: `FFM strcmp result: 0` — native downcall executed successfully.

## Implications for GlyphWeb (M3/M4 benchmarks)

- The user's claim *"the preview package can be enabled"* is **correct for
  JDK 21**: `--enable-preview` works at both compile and run time. It remains
  impossible on JDK 17 because the package simply is not there.
- Caveat of preview APIs: class files are marked as preview-bound to the exact
  minor version; mixing preview artifacts across JDK versions throws
  `UnsupportedClassVersionError`. For CI this means the bench module needs a
  dedicated toolchain profile, e.g.:

  ```xml
  <profile>
    <id>jdk21-ffm-bench</id>
    <activation><jdk>21</jdk></activation>
    <properties><maven.compiler.release>21</maven.compiler.release></properties>
    <!-- compiler args: --enable-preview ; surefire/JMH args: --enable-preview
         --enable-native-access=ALL-UNNAMED -->
  </profile>
  ```

- Recommendation stands: prefer **JDK 22+ (LTS 25)** for the production FFM
  path so no preview flags are needed; keep the M3 JNI baseline on 17.

## Rollback note

To restore JDK 17 as default:
`update-alternatives --set java /usr/lib/jvm/java-17-openjdk-amd64/bin/java`
(and same for `javac`). Both toolchains coexist safely.
