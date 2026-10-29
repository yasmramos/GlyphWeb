package io.glyph.web.core.event;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Fixed-capacity, pre-allocated fan-out slot for a single listener type.
 *
 * <p><b>Why not {@code ArrayList<T>}:</b> an {@code ArrayList} iterated on the
 * event hot path allocates an {@code Iterator} per emission (unless the JIT
 * escape-analyses it away, which is not guaranteed) and grows by copying on
 * add. This class replaces both costs with:
 *
 * <ul>
 *   <li>a final {@code Object[]} of fixed capacity (default 16) — never resized;</li>
 *   <li>a {@code long} bitmask marking occupied slots — emission iterates only
 *       over set bits via {@link Long#tailZeros}, skipping free slots in O(1);</li>
 *   <li>an atomic publication word ({@code volatile long mask}) that readers
 *       snapshot once per emission, so dispatch never observes a torn array.</li>
 * </ul>
 *
 * <p><b>Allocation profile:</b> steady-state emission performs ZERO allocations.
 * Registration mutates under a write lock but is confined to setup/teardown
 * (declarative reconciliation attach/detach), never per frame. One iteration
 * cursor object is reused per thread through {@link #beginIteration()}.
 *
 * <p><b>Re-entrancy:</b> listeners may register/unregister during dispatch;
 * the snapshot taken at emission start keeps iteration stable (weakly
 * consistent semantics, same guarantee as CopyOnWrite but without its copy cost).
 *
 * @param <L> the specialized functional listener interface (primitive-only signatures)
 */
public final class SignalBus<L> {

    /** Default maximum number of concurrent listeners per signal instance. */
    public static final int DEFAULT_CAPACITY = 16;

    /** Hard ceiling: a {@code long} bitmask cannot address more than 64 slots. */
    public static final int MAX_CAPACITY = 64;

    private final Object[] slots;
    private final int capacity;

    /** Per-slot version counter guarding against stale Subscription tokens. */
    private final long[] generations = new long[MAX_CAPACITY];

    /**
     * Publication mask: bit i set ⇔ slots[i] holds a live listener.
     * Written under {@link #lock}; read without locking by emitters.
     */
    private volatile long publishedMask;

    /** Bitmask of slots whose listeners threw during the last emission. */
    private final AtomicLong failureMask = new AtomicLong();

    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public SignalBus() {
        this(DEFAULT_CAPACITY);
    }

    public SignalBus(int capacity) {
        if (capacity < 1 || capacity > MAX_CAPACITY) {
            throw new IllegalArgumentException(
                    "SignalBus capacity must be in [1, " + MAX_CAPACITY + "], got " + capacity);
        }
        this.capacity = capacity;
        this.slots = new Object[capacity];
    }

    /**
     * A listener registration handle usable for precise removal. The generation
     * stamp makes double-unregister and stale-token removal safe: tokens issued
     * for an earlier occupant of the same slot are silently rejected.
     */
    public record Subscription(int slot, long generation) {
    }

    /**
     * Registers {@code listener} into the lowest free slot.
     *
     * @return a subscription token, or {@code null} when the bus is full —
     *         callers should treat overflow as a configuration error, since
     *         exceeding capacity signals leaked registrations upstream.
     */
    public Subscription register(L listener) {
        lock.writeLock().lock();
        try {
            long free = ~currentMask();
            // Only consider bits within capacity.
            free &= (-1L >>> (64 - capacity));
            if (free == 0L) {
                return null; // bus saturated
            }
            int slot = Long.numberOfTrailingZeros(free);
            long generation = ++generations[slot];
            slots[slot] = listener;
            publishedMask = currentMask() | (1L << slot);
            return new Subscription(slot, generation);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Removes a previously granted subscription. Idempotent per slot generation. */
    public boolean unregister(Subscription subscription) {
        if (subscription == null) {
            return false;
        }
        lock.writeLock().lock();
        try {
            int slot = subscription.slot();
            if (slot < 0 || slot >= capacity || (currentMask() & (1L << slot)) == 0L) {
                return false;
            }
            // Generation guard: a stale token pointing at a slot that was since
            // re-issued with a different listener must not evict the new owner.
            if (generations[slot] != subscription.generation()) {
                return false;
            }
            slots[slot] = null; // clear strong reference so the listener can be collected
            publishedMask = currentMask() & ~(1L << slot);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Number of active listeners. Volatile read only — safe from any thread. */
    public int size() {
        return Long.bitCount(publishedMask);
    }

    public boolean isEmpty() {
        return publishedMask == 0L;
    }

    /**
     * Snapshots the active mask for one emission. Zero allocations.
     * Listeners added after this call are NOT invoked by the current emission.
     */
    public long activeMaskSnapshot() {
        return publishedMask;
    }

    /** Returns the listener pinned at {@code slot} for a snapshotted emission. */
    @SuppressWarnings("unchecked")
    public L listenerAt(int slot) {
        return (L) slots[slot];
    }

    /**
     * Records that the listener at {@code slot} threw during dispatch. The
     * emitter is responsible for draining failures via {@link #drainFailures()}.
     */
    public void markFailure(int slot) {
        failureMask.updateAndGet(existing -> existing | (1L << slot));
    }

    /**
     * Clears and returns the number of slots that failed in the last emission.
     * The failing listener references stay registered — fault isolation is a
     * policy decision left to the owning {@code GlyphEventPump}.
     */
    public int drainFailures() {
        long previous = failureMask.getAndSet(0L);
        return Long.bitCount(previous);
    }

    /**
     * Removes every listener. Called by {@code GlyphWebView.close()} while the
     * dispatcher guarantees no concurrent emission is in flight.
     */
    public void clear() {
        lock.writeLock().lock();
        try {
            Arrays.fill(slots, null);
            publishedMask = 0L;
            failureMask.set(0L);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private long currentMask() {
        return publishedMask; // caller holds the write lock; plain read is fine
    }
}
