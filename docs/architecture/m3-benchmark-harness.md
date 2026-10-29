# M3 — JMH Benchmark Harness (glyph-bench)

**Status:** implemented and verified on JDK 17.  
**Module:** `glyph-bench` (not deployed; produces an executable shaded jar).

## Purpose

Turn the zero-allocation claims of M2 (`SignalBus`, `GlyphEventPump`,
`NativeStringTable`) into **measured facts**, and provide the harness that will
later quantify JNI vs Panama (FFM) bridge overhead for the WebKit upcalls.

## Build & run

```bash
mvn -pl glyph-bench -am package -DskipTests     # -> glyph-bench/target/benchmarks.jar
java -jar glyph-bench/target/benchmarks.jar                       # full suite
java -jar glyph-bench/target/benchmarks.jar SignalBusBenchmark -prof gc   # one class, GC-proved
```

## Benchmarks

| Class | Measures | Key metric |
|---|---|---|
| `SignalBusBenchmark` | Raw bitmask fan-out loop at 0/1/8/16 listeners + volatile snapshot baseline | ns/op, `gc.alloc.rate.norm` |
| `EventPumpEmissionBenchmark` | Full `fireLoadChanged` path incl. UTF-8 interning: idle bail-out, steady-state intern hit, fresh-URI worst case | ns/op, `gc.alloc.rate.norm` |

## Verified results (JDK 17.0.20, G1, quick profile run `-f 1 -wi 2 -i 2`)

```
SignalBusBenchmark.emit8Listeners               avgt   ~5.1 ns/op    gc.alloc.rate.norm ≈ 10⁻⁴ B/op
EventPumpEmissionBenchmark.fireWithThreeListeners avgt ~28.1 ns/op  gc.alloc.rate.norm ≈ 10⁻³ B/op
```

**Interpretation:**

- Fan-out to 8 pre-registered listeners costs ~5 ns with **effectively zero
  allocation** — the M2 contract holds under `-prof gc`.
- The complete emission path (intern lookup + 3 primitive invocations + failure
  carrier) is ~28 ns/op, still allocation-free in steady state because the URI
  slot hits the intern table and no `String` is materialized unless requested.
- `fireWithFreshUri` is the only allocating scenario by design (the JNI upcall
  hands a new byte window per signal); its numbers bound the redirect-storm cost.

## Deferred to M4 (needs native toolchain / JDK 21 CI matrix)

- `NativeCallOverheadBenchmark`: true JavaCPP/JNI downcall vs `Linker.downcallHandle`
  comparison. Requires compiling `glyph-native` against WebKitGTK headers; the
  FFM half is preview-gated before JDK 21, so it ships with the CI matrix upgrade.

## Conventions

- Benchmarks live only in `glyph-bench`; never import them from library modules.
- Every hot-path claim added in future milestones must come with a benchmark
  runnable via `-prof gc` and a recorded result block in this file.
