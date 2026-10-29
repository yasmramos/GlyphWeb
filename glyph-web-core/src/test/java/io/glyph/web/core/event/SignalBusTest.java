package io.glyph.web.core.event;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the pre-allocated {@link SignalBus} fan-out structure. */
class SignalBusTest {

    @FunctionalInterface
    interface Probe {
        void ping(int value);
    }

    @Test
    void registerAssignsLowestFreeSlotAndTracksSize() {
        SignalBus<Probe> bus = new SignalBus<>(8);
        assertTrue(bus.isEmpty());

        SignalBus.Subscription first = bus.register(v -> { });
        SignalBus.Subscription second = bus.register(v -> { });

        assertEquals(0, first.slot());
        assertEquals(1, second.slot());
        assertEquals(2, bus.size());
    }

    @Test
    void unregisterFreesSlotForReuse() {
        SignalBus<Probe> bus = new SignalBus<>(8);
        SignalBus.Subscription a = bus.register(v -> { });
        SignalBus.Subscription b = bus.register(v -> { });
        assertTrue(bus.unregister(a));

        // Freed slot 0 must be handed out again (lowest-free policy).
        SignalBus.Subscription c = bus.register(v -> { });
        assertEquals(0, c.slot());
        assertEquals(2, bus.size());

        // Double-unregister is a no-op, not an error.
        assertFalse(bus.unregister(a));
        assertFalse(bus.unregister(null));
    }

    @Test
    void saturationReturnsNullSubscription() {
        SignalBus<Probe> bus = new SignalBus<>(4);
        SignalBus.Subscription s0 = bus.register(v -> { });
        SignalBus.Subscription s1 = bus.register(v -> { });
        SignalBus.Subscription s2 = bus.register(v -> { });
        SignalBus.Subscription s3 = bus.register(v -> { });
        assertNull(bus.register(v -> { })); // fifth registration fails: bus full
        assertEquals(4, bus.size());
        assertTrue(bus.unregister(s0));
        assertTrue(bus.unregister(s1));
        assertTrue(bus.unregister(s2));
        assertTrue(bus.unregister(s3));
    }

    @Test
    void bitmaskIterationVisitsOnlyActiveSlotsInOrder() {
        SignalBus<Probe> bus = new SignalBus<>(16);
        AtomicInteger sum = new AtomicInteger();
        bus.register(v -> sum.addAndGet(v));                    // slot 0
        SignalBus.Subscription gone = bus.register(v -> sum.addAndGet(1000)); // slot 1
        bus.register(v -> sum.addAndGet(v * 10));               // slot 2
        bus.unregister(gone);

        long mask = bus.activeMaskSnapshot();
        assertEquals(0b101L, mask); // slot 1 cleared

        for (long active = mask; active != 0L; ) {
            int slot = Long.numberOfTrailingZeros(active);
            active &= active - 1;
            bus.listenerAt(slot).ping(slot == 0 ? 5 : 7);
        }
        assertEquals(5 + 70, sum.get());
    }

    @Test
    void clearRemovesAllListenersAndReferences() {
        SignalBus<Probe> bus = new SignalBus<>(8);
        bus.register(v -> { });
        bus.register(v -> { });
        bus.clear();
        assertTrue(bus.isEmpty());
        assertNull(bus.listenerAt(0));
    }

    @Test
    void failureMaskCountsDrainsCorrectly() {
        SignalBus<Probe> bus = new SignalBus<>(8);
        SignalBus.Subscription a = bus.register(v -> { });
        SignalBus.Subscription b = bus.register(v -> { });
        bus.markFailure(a.slot());
        bus.markFailure(b.slot());
        bus.markFailure(a.slot()); // duplicate mark must not double-count
        assertEquals(2, bus.drainFailures());
        assertEquals(0, bus.drainFailures());
    }

    @Test
    void invalidCapacityIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SignalBus<Probe>(0));
        assertThrows(IllegalArgumentException.class, () -> new SignalBus<Probe>(SignalBus.MAX_CAPACITY + 1));
    }

    @Test
    void maximumCapacityUsesFullBitmaskRange() {
        SignalBus<Probe> bus = new SignalBus<>(SignalBus.MAX_CAPACITY);
        for (int i = 0; i < SignalBus.MAX_CAPACITY; i++) {
            bus.register(v -> { });
        }
        assertEquals(SignalBus.MAX_CAPACITY, bus.size());
        assertNull(bus.register(v -> { }));
    }
}
