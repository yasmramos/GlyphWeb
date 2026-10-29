package io.glyph.web.core.lifecycle;

/**
 * Registry-side view of a handle's life. Mirrors the client-facing state but
 * adds {@link #ORPHANED}, which flags handles whose Java peer was collected
 * while the native resource was still alive — our leak-tripwire.
 */
public enum ResourceState {
    /** Registered and alive; native pointer valid. */
    ACTIVE,
    /** Explicitly closed by user code; awaiting finalizer drain. */
    RELEASED,
    /** Collected without closing; reclaimed opportunistically by the GC worker. */
    ORPHANED
}
