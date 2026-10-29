package io.glyph.web.core.event;

/**
 * Bounded, reusable table that converts native C strings (URIs, titles, console
 * messages) into integer slots consumed by the primitive-only listener APIs.
 *
 * <p><b>The allocation problem:</b> a JNI upcall receives UTF-8 bytes; producing
 * a {@code String} eagerly would allocate on every signal even when no listener
 * reads the text. Instead we intern the bytes into a slot and hand listeners an
 * {@code int}. The {@code String} is materialized lazily, at most once per slot,
 * only if someone calls {@link #value(int)}.
 *
 * <p><b>Bounded growth:</b> WebKit reuses a tiny set of URIs/titles across a
 * page's lifetime, so a small open-addressing map plus FIFO eviction of stale
 * slots keeps memory flat. Eviction clears both the mapping and the cached
 * String, returning the byte array reference to the pool.
 *
 * <p><b>Thread model:</b> confined to the WebKit UI thread (all emissions are
 * marshalled through {@code WebKitDispatcher}), hence no synchronization —
 * plain {@code HashMap}/{@code ArrayList} internals are safe and cheaper than
 * concurrent variants on this single-writer path.
 */
public final class NativeStringTable {

    /** Default number of simultaneously interned strings per view. */
    public static final int DEFAULT_CAPACITY = 64;

    private static final int MAX_KEY_BYTES = 512; // longer URIs bypass interning

    /** slot -> live key (null when free); doubles as the hash bucket array. */
    private final ByteKey[] keysBySlot = new ByteKey[DEFAULT_CAPACITY];
    /** Ring of occupied slots in insertion order, for O(1) FIFO eviction. */
    private final int[] fifo = new int[DEFAULT_CAPACITY];
    private int fifoHead;
    private int fifoTail;
    private final String[] resolved = new String[DEFAULT_CAPACITY];
    private final boolean[] occupied = new boolean[DEFAULT_CAPACITY];
    /** slot -> owning key, kept so reverse lookup is O(1) instead of a scan. */
    private final ByteKey[] slotKeys = new ByteKey[DEFAULT_CAPACITY];
    /** Per-slot generation; invalidates stale slot handles after eviction/reuse. */
    private final long[] slotGenerations = new long[DEFAULT_CAPACITY];
    private final int capacity;
    private int used;

    /** Immutable-ish byte wrapper with content equality; pooled via intern reuse. */
    private static final class ByteKey {
        byte[] data; // swapped on reset; identity of object stays stable in lists
        int length;
        int hash;

        void set(byte[] source, int len) {
            this.data = source;
            this.length = len;
            this.hash = computeHash(source, len);
        }

        private static int computeHash(byte[] bytes, int len) {
            int h = 0x811C9DC5; // FNV-1a 32-bit: cheap, good distribution for URLs
            for (int i = 0; i < len; i++) {
                h ^= bytes[i] & 0xFF;
                h *= 0x01000193;
            }
            return h;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        /** Content equality against another key, bypassing Object.equals overhead. */
        boolean equalsContent(ByteKey other) {
            if (other.length != length || other.hash != hash) {
                return false;
            }
            for (int i = 0; i < length; i++) {
                if (data[i] != other.data[i]) {
                    return false;
                }
            }
            return true;
        }
    }

    public NativeStringTable() {
        this(DEFAULT_CAPACITY);
    }

    public NativeStringTable(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        this.capacity = Math.min(capacity, DEFAULT_CAPACITY);
    }

    /** Sentinel returned when a value is too large to intern; callers decode directly. */
    public static final long NOT_INTERNED = -1L;

    /**
     * Interns {@code bytes[0..len)} (UTF-8) and returns its slot, creating one
     * if absent. Hot path cost: one FNV hash + array compare; zero allocations
     * for repeated values (the common case for titles/URIs).
     */
    public long intern(byte[] bytes, int len) {
        if (len > MAX_KEY_BYTES) {
            return NOT_INTERNED;
        }
        ByteKey probe = scratch();
        probe.set(bytes, len);
        // Linear content match over occupied slots: capacity is tiny (<=64),
        // so this beats hashing while keeping zero allocations and zero maps.
        for (int slot = 0; slot < capacity; slot++) {
            if (occupied[slot] && keysBySlot[slot].equalsContent(probe)) {
                return pack(slot, slotGenerations[slot]);
            }
        }
        int slot = allocateSlot();
        // Own a copy so the caller may recycle its native buffer immediately.
        byte[] owned = new byte[len];
        System.arraycopy(bytes, 0, owned, 0, len);
        ByteKey stored = new ByteKey();
        stored.set(owned, len);
        long generation = ++slotGenerations[slot];
        keysBySlot[slot] = stored;
        slotKeys[slot] = stored;
        occupied[slot] = true;
        resolved[slot] = null;
        fifo[fifoTail] = slot;
        fifoTail = (fifoTail + 1) % fifo.length;
        used++;
        return pack(slot, generation);
    }

    /** Slots fit in 8 bits; the per-slot generation stamp rides in the upper bits. */
    private static long pack(int slot, long generation) {
        return (generation << 8) | slot;
    }

    /** Test seam: exposes the slot portion of an opaque token. */
    static int slotOfForTesting(long token) {
        return slotOf(token);
    }

    private static int slotOf(long token) {
        return (int) (token & 0xFF);
    }

    private static long generationOf(long token) {
        return token >>> 8;
    }

    /**
     * Materializes the String for a slot at most once (cached afterwards).
     * Returns {@code null} for {@link #NOT_INTERNED} or evicted slots — callers
     * treat null as "text unavailable", never as an error.
     */
    public String value(long token) {
        if (token == NOT_INTERNED) {
            return null;
        }
        int slot = slotOf(token);
        if (slot >= capacity || !occupied[slot] || slotGenerations[slot] != generationOf(token)) {
            return null; // evicted or reused slot — stale handle, never wrong text
        }
        String cached = resolved[slot];
        if (cached == null) {
            ByteKey key = slotKeys[slot];
            cached = new String(key.data, 0, key.length, java.nio.charset.StandardCharsets.UTF_8);
            resolved[slot] = cached;
        }
        return cached;
    }

    public int size() {
        return used;
    }

    /** Drops all mappings; called from GlyphWebView.close() on the UI thread. */
    public void clear() {
        java.util.Arrays.fill(resolved, null);
        java.util.Arrays.fill(occupied, false);
        java.util.Arrays.fill(slotKeys, null);
        java.util.Arrays.fill(keysBySlot, null);
        used = 0;
        fifoHead = 0;
        fifoTail = 0;
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private ByteKey scratch;

    private ByteKey scratch() {
        if (scratch == null) {
            scratch = new ByteKey();
        }
        return scratch;
    }

    private int allocateSlot() {
        if (used < capacity) {
            for (int i = 0; i < capacity; i++) {
                if (!occupied[i]) {
                    return i;
                }
            }
        }
        // FIFO eviction of the oldest interned value — O(1) ring pop.
        int victimSlot = fifo[fifoHead];
        fifoHead = (fifoHead + 1) % fifo.length;
        occupied[victimSlot] = false;
        resolved[victimSlot] = null;
        slotKeys[victimSlot] = null;
        keysBySlot[victimSlot] = null;
        used--;
        return victimSlot;
    }
}
