package io.glyph.web.core.lifecycle;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Central registry tracking every live native resource created by GlyphWeb.
 *
 * <p><b>Why ConcurrentHashMap:</b> registration happens on the UI thread,
 * unregistration can be triggered from GC worker threads and from user code on
 * arbitrary threads. A single {@code ConcurrentHashMap<Long, Entry>} gives us
 * lock-free reads (the hot path: {@link #isAlive(long)}) and striped writes,
 * avoiding one global mutex that would serialize view creation across windows.
 *
 * <p><b>Leak accounting:</b> {@link #liveCount()} is the tripwire used by tests
 * and by Glyph UI's devtools overlay — it must return to zero after a window
 * closes. Handles registered with {@code acceptsReclamation() == true} that are
 * still ACTIVE at shutdown are force-released in reverse creation order
 * (children before parents) inside {@link #shutdownAll()}.
 */
public final class WebKitLifecycleManager {

    /** Bookkeeping record per managed handle. Immutable except for {@code state}. */
    private static final class Entry {
        final NativeHandle handle;
        final long sequence; // creation order, used for reverse teardown
        volatile ResourceState state;

        Entry(NativeHandle handle, long sequence) {
            this.handle = handle;
            this.sequence = sequence;
            this.state = ResourceState.ACTIVE;
        }
    }

    private final ConcurrentMap<Long, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicInteger creationCounter = new AtomicInteger();
    private volatile boolean shutdown;

    public WebKitLifecycleManager() {
    }

    /**
     * Registers a freshly created handle. Called from constructors AFTER the
     * native pointer exists; a failed registration means the caller leaked the
     * native object, so we throw rather than swallow.
     */
    public void register(NativeHandle handle) {
        if (shutdown) {
            throw new GlyphWebException(
                    "Cannot register resource id=" + handle.nativeId()
                            + ": the WebKit runtime has already shut down.");
        }
        Entry previous = entries.putIfAbsent(
                handle.nativeId(), new Entry(handle, creationCounter.incrementAndGet()));
        if (previous != null) {
            throw new GlyphWebException("Duplicate native id registration: " + handle.nativeId());
        }
    }

    /** Marks the handle RELEASED without touching the map entry (kept for audit). */
    public void markReleased(long nativeId) {
        Entry entry = entries.get(nativeId);
        if (entry != null) {
            entry.state = ResourceState.RELEASED;
        }
    }

    /** Removes a fully released handle from the registry. Idempotent. */
    public void unregister(long nativeId) {
        entries.remove(nativeId);
    }

    /** Lock-free liveness probe used by every operation before touching native memory. */
    public boolean isAlive(long nativeId) {
        Entry entry = entries.get(nativeId);
        return entry != null && entry.state == ResourceState.ACTIVE;
    }

    /** Number of handles currently ACTIVE. Must converge to 0 after window teardown. */
    public int liveCount() {
        int count = 0;
        for (Entry entry : entries.values()) {
            if (entry.state == ResourceState.ACTIVE) {
                count++;
            }
        }
        return count;
    }

    /** Snapshot of live ids for diagnostics; allocation-heavy, never call on hot paths. */
    public Map<Long, ResourceState> snapshot() {
        var result = new java.util.HashMap<Long, ResourceState>(entries.size());
        entries.forEach((id, entry) -> result.put(id, entry.state));
        return java.util.Collections.unmodifiableMap(result);
    }

    /**
     * Releases every remaining owned handle in reverse creation order and marks
     * the manager permanently closed. Safe to call multiple times; concurrent
     * callers may observe partially drained state but never double-free because
     * {@link NativeHandle#close()} is contractually idempotent.
     */
    public synchronized void shutdownAll() {
        if (shutdown) {
            return;
        }
        shutdown = true;
        entries.values().stream()
                .filter(entry -> entry.state == ResourceState.ACTIVE)
                .sorted((a, b) -> Long.compare(b.sequence, a.sequence))
                .forEach(entry -> {
                    try {
                        if (entry.handle.acceptsReclamation()) {
                            entry.handle.close();
                        }
                        entry.state = ResourceState.ORPHANED;
                    } catch (RuntimeException failure) {
                        // Never let one bad teardown abort the rest; log-and-continue.
                        System.err.println("[GlyphWeb] teardown failed for id="
                                + entry.handle.nativeId() + ": " + failure.getMessage());
                    }
                });
        entries.clear();
    }

    /** True after {@link #shutdownAll()} completed. */
    public boolean isShutdown() {
        return shutdown;
    }
}
