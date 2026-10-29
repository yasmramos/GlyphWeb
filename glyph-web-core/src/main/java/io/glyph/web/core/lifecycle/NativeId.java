package io.glyph.web.core.lifecycle;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-unique identifier for every native-backed GlyphWeb resource.
 *
 * <p>Design rationale: raw pointers ({@code long address}) are NOT stable ids —
 * the allocator reuses addresses after free, which turns TOCTOU races into
 * silent use-after-free corruption. By keying all bookkeeping maps on an opaque,
 * monotonically increasing id and validating the id→pointer mapping under a
 * per-resource lock, we make stale handles fail loudly instead of corrupting
 * memory. Address reuse detection is therefore impossible by construction.
 *
 * <p>Allocation cost: one {@link AtomicLong#getAndIncrement} (~5 ns uncontended,
 * lock-free CAS). Ids are cached inside each handle object, so repeated lookups
 * never touch the counter again.
 */
public final class NativeId {

    private static final AtomicLong COUNTER = new AtomicLong(1L);

    private NativeId() {
    }

    /** Issues a fresh, never-reused identifier. */
    public static long next() {
        return COUNTER.getAndIncrement();
    }
}
