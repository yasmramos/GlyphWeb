# M2 — Zero-Allocation Event System

## Goal

Fan out WebKitGTK signals (load-changed, title changed, console messages) to
Java listeners with **zero allocations on the emission hot path** and no
boxing, so a busy page (60 fps of scroll/console traffic) adds zero young-gen
pressure.

## Architecture

```
WebKitGTK (C) ──JNI upcall──▶ GlyphEventPump.fireXxx(...)   [WebKit UI thread]
                                 │
                                 ├─ snapshot SignalBus bitmask (1 volatile read)
                                 ├─ iterate set bits via Long.numberOfTrailingZeros
                                 ├─ invoke specialized primitive-only listeners
                                 └─ fault-isolate: capture first Throwable, keep going
```

### Components (`io.glyph.web.core.event`)

| Class | Role | Allocation profile |
|---|---|---|
| `SignalBus<L>` | Fixed-capacity slot array + `long` bitmask (max 64 slots, default 16) | Emission: 0 allocs. Registration: 1 record token (cold path). |
| `GlyphEventPump` | Per-view router; one bus per signal kind; entry point for JNI upcalls | Emission: 0 allocs; early bail-out when bus empty. |
| `WebLoadListener` / `WebTitleListener` / `WebConsoleListener` | `@FunctionalInterface`, primitive-only signatures | No boxing by construction. |
| `NativeStringTable` | Interns native UTF-8 payloads (URI/title/console) into opaque `long` tokens | Repeated values: 0 allocs. First sighting: 1 byte[] + 1 key (amortized). |
| `WebKitSignal` | Stable int ids + numeric mirror of `WebKitLoadEvent` | Compile-time constants only. |

## Key decisions & rationale

1. **Bitmask fan-out instead of `ArrayList<Listener>`.**
   Iterating an `ArrayList` risks an `Iterator` allocation per emission and
   touches null/gap entries. A `volatile long publishedMask` gives an atomic
   snapshot of live slots; dispatch walks only set bits
   (`active &= active - 1`). GC impact: events no longer generate garbage at
   all, so young-GC frequency stays tied to real UI work, not callbacks.

2. **Primitive-only listener signatures.**
   `void onLoadChanged(long nativeId, int status, long uriToken)` — every
   parameter is a primitive. JNI upcalls therefore never construct boxed
   `Integer`/`Long` objects and never build event DTOs.

3. **Lazy string interning with generation tokens.**
   Native text (URIs/titles) is interned into a bounded table (≤64 entries,
   FIFO eviction). Listeners receive a `long` token packing `slot | generation<<8`.
   The String materializes only if `stringOf(token)` is called, and a stale
   token (slot reused after eviction) resolves to `null` instead of returning
   another page's title — same use-after-free defense as `NativeId` in M1,
   applied to text handles.

4. **Fault isolation without rethrow cost.**
   Each listener call is wrapped; the first throwable per emission is stored in
   pre-allocated fields (`lastFailure`), remaining slots still fire. This
   protects the GTK main loop from a misbehaving declarative binding.

5. **Thread confinement.**
   All emissions run on the single WebKit UI thread (M1 dispatcher contract),
   so `NativeStringTable` needs no locking. `SignalBus` registration uses a
   `ReadWriteLock` because Glyph UI reconciliation may attach/detach listeners
   from other threads; emission reads only the volatile mask.

## Capacity limits

- 16 listeners per signal per view (configurable 1–64; hard ceiling = bitmask width).
- Saturation throws `GlyphWebException` — treated as a leaked-subscription bug
  in the declarative layer, never silently dropped.

## Benchmarks (planned for M3 harness)

Target: JMH `alloc/sec == 0 B/op` for `fireLoadChanged` with 4 attached
listeners and repeated URIs; < 1 alloc for first-sighting interning.
