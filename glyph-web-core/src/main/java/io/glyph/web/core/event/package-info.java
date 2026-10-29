/**
 * WebKit-to-Java signal fan-out engine (Milestone M2).
 *
 * <p>Architecture: pre-allocated fan-out instead of dynamic collections.
 * <ul>
 *   <li>{@link io.glyph.web.core.event.SignalBus} — fixed-capacity slot array
 *       plus a {@code long} bitmask; emission iterates only over set bits,
 *       zero allocations per event;</li>
 *   <li>{@link io.glyph.web.core.event.GlyphEventPump} — per-view router
 *       invoked by JNI upcalls on the WebKit UI thread, with fault isolation
 *       and lazy string interning;</li>
 *   <li>{@link io.glyph.web.core.event.WebLoadListener} and siblings —
 *       primitive-only functional interfaces (no boxing, no varargs) so
 *       hot-path callbacks never allocate;</li>
 *   <li>{@link io.glyph.web.core.event.NativeStringTable} — interns native
 *       UTF-8 payloads into int slots; Strings materialize lazily only when
 *       consumed.</li>
 * </ul>
 *
 * <p>See {@code docs/architecture/m2-zero-allocation-events.md} for the full
 * design rationale and allocation budget.
 */
package io.glyph.web.core.event;
