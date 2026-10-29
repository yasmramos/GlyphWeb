package io.glyph.web.core.event;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the lazy UTF-8 interning table. */
class NativeStringTableTest {

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void repeatedValuesShareOneSlot() {
        NativeStringTable table = new NativeStringTable();
        byte[] uri = utf8("https://glyph.dev");
        long first = table.intern(uri, uri.length);
        long second = table.intern(utf8("https://glyph.dev"), "https://glyph.dev".length());
        assertEquals(first, second);
        assertEquals(1, table.size());
        assertEquals("https://glyph.dev", table.value(first));
    }

    @Test
    void distinctValuesGetDistinctSlots() {
        NativeStringTable table = new NativeStringTable();
        byte[] a = utf8("alpha");
        byte[] b = utf8("beta");
        long slotA = table.intern(a, a.length);
        long slotB = table.intern(b, b.length);
        assertTrue(slotA != slotB);
        assertEquals("alpha", table.value(slotA));
        assertEquals("beta", table.value(slotB));
    }

    @Test
    void oversizedPayloadsBypassInterning() {
        NativeStringTable table = new NativeStringTable();
        byte[] huge = new byte[600];
        assertEquals(NativeStringTable.NOT_INTERNED, table.intern(huge, huge.length));
        assertEquals(0, table.size());
    }

    @Test
    void evictionMakesRoomForNewValuesAndInvalidatesOldOnes() {
        NativeStringTable table = new NativeStringTable();
        long[] slots = new long[NativeStringTable.DEFAULT_CAPACITY];
        for (int i = 0; i < slots.length; i++) {
            byte[] value = utf8("value-" + i);
            slots[i] = table.intern(value, value.length);
        }
        // Table is now full; one more intern must evict "value-0" (FIFO).
        byte[] extra = utf8("eviction-trigger");
        long extraSlot = table.intern(extra, extra.length);
        assertNotNull(table.value(extraSlot));
        assertNull(table.value(slots[0]), "evicted slot must resolve to null");
        assertEquals("value-1", table.value(slots[1]));
        assertEquals(NativeStringTable.DEFAULT_CAPACITY, table.size());
    }

    @Test
    void clearResetsEverything() {
        NativeStringTable table = new NativeStringTable();
        byte[] value = utf8("temp");
        long slot = table.intern(value, value.length);
        table.clear();
        assertEquals(0, table.size());
        assertNull(table.value(slot));
        // Re-interning after clear yields a fresh token (slot 0, generation bumped).
        long freshToken = table.intern(value, value.length);
        assertEquals(0, NativeStringTable.slotOfForTesting(freshToken));
        assertEquals("temp", table.value(freshToken));
    }

    @Test
    void invalidCapacityRejected() {
        assertThrows(IllegalArgumentException.class, () -> new NativeStringTable(0));
    }

    @Test
    void unicodePayloadsRoundTripThroughUtf8() {
        NativeStringTable table = new NativeStringTable();
        String title = "Ñoño — 日本語 ✓";
        byte[] bytes = utf8(title);
        long slot = table.intern(bytes, bytes.length);
        assertEquals(title, table.value(slot));
    }
}
