package io.glyph.web.core.event;

import io.glyph.web.core.lifecycle.GlyphWebException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the per-view {@link GlyphEventPump} router. */
class GlyphEventPumpTest {

    private GlyphEventPump pump;

    @BeforeEach
    void setUp() {
        pump = new GlyphEventPump(42L);
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void loadFanOutReachesEveryRegisteredListenerWithPrimitives() {
        AtomicInteger hits = new AtomicInteger();
        pump.addLoadListener((id, status, uriSlot) -> {
            hits.incrementAndGet();
            assertEquals(42L, id);
            assertEquals(WebKitSignal.LoadStatus.FINISHED, status);
            assertEquals("https://glyph.dev", pump.stringOf(uriSlot));
        });
        pump.addLoadListener((id, status, uriSlot) -> hits.incrementAndGet());

        byte[] uri = utf8("https://glyph.dev");
        pump.fireLoadChanged(WebKitSignal.LoadStatus.FINISHED, uri, uri.length);

        assertEquals(2, hits.get());
    }

    @Test
    void titleAndConsoleSignalsRouteToTheirOwnBusesOnly() {
        AtomicInteger titleHits = new AtomicInteger();
        AtomicInteger consoleHits = new AtomicInteger();
        pump.addTitleListener((id, slot) -> titleHits.incrementAndGet());
        pump.addConsoleListener((id, severity, line, slot) -> consoleHits.incrementAndGet());

        byte[] title = utf8("Hello");
        pump.fireTitleChanged(title, title.length);
        byte[] msg = utf8("boom");
        pump.fireConsoleMessage(0, 12, msg, msg.length);

        assertEquals(1, titleHits.get());
        assertEquals(1, consoleHits.get());
    }

    @Test
    void failingListenerDoesNotStarveTheRest() {
        AtomicInteger survivors = new AtomicInteger();
        pump.addLoadListener((id, status, slot) -> {
            throw new RuntimeException("listener exploded");
        });
        pump.addLoadListener((id, status, slot) -> survivors.incrementAndGet());
        pump.addLoadListener((id, status, slot) -> survivors.incrementAndGet());

        byte[] uri = utf8("about:blank");
        pump.fireLoadChanged(WebKitSignal.LoadStatus.STARTED, uri, uri.length);

        assertEquals(2, survivors.get(), "dispatch must continue past a failing slot");
        assertNotNull(pump.lastFailure());
        assertEquals("listener exploded", pump.lastFailure().getMessage());
        assertEquals(0, pump.lastFailureSlot());
        assertEquals(1, pump.drainFailureCounts());
        pump.clearFailure();
        assertNull(pump.lastFailure());
    }

    @Test
    void unregisteredListenerStopsReceivingEvents() {
        AtomicInteger hits = new AtomicInteger();
        SignalBus.Subscription sub = pump.addLoadListener((id, status, slot) -> hits.incrementAndGet());

        pump.fireLoadChanged(WebKitSignal.LoadStatus.COMMITTED, null, 0);
        pump.removeLoadListener(sub);
        pump.fireLoadChanged(WebKitSignal.LoadStatus.COMMITTED, null, 0);

        assertEquals(1, hits.get());
    }

    @Test
    void tearDownDropsLaterEmissionsAndBlocksNewRegistrations() {
        AtomicInteger hits = new AtomicInteger();
        pump.addLoadListener((id, status, slot) -> hits.incrementAndGet());

        pump.tearDown();

        pump.fireLoadChanged(WebKitSignal.LoadStatus.FINISHED, null, 0);
        assertEquals(0, hits.get());
        assertTrue(pump.isTornDown());
        assertThrows(GlyphWebException.class, () -> pump.addLoadListener((id, s, u) -> { }));
        pump.tearDown(); // idempotent
    }

    @Test
    void saturatedBusSurfacesConfigurationError() {
        for (int i = 0; i < SignalBus.DEFAULT_CAPACITY; i++) {
            pump.addLoadListener((id, status, slot) -> { });
        }
        GlyphWebException overflow = assertThrows(GlyphWebException.class,
                () -> pump.addLoadListener((id, status, slot) -> { }));
        assertTrue(overflow.getMessage().contains("saturated"));
    }

    @Test
    void nullUriYieldsNotInternedSlotAndNullResolution() {
        java.util.concurrent.atomic.AtomicLong seen = new java.util.concurrent.atomic.AtomicLong(-99L);
        pump.addLoadListener((id, status, slot) -> {
            seen.set(slot);
            assertNull(pump.stringOf(slot));
        });
        pump.fireLoadChanged(WebKitSignal.LoadStatus.STARTED, null, 0);
        assertEquals(NativeStringTable.NOT_INTERNED, seen.get());
    }
}
