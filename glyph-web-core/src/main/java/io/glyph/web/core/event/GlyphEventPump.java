package io.glyph.web.core.event;

import io.glyph.web.core.lifecycle.GlyphWebException;

/**
 * Per-view event router: owns one {@link SignalBus} per WebKit signal kind and
 * exposes allocation-free emission entry points that the JNI upcall layer calls
 * when a native signal fires.
 *
 * <p><b>Data flow:</b>
 * <pre>
 *   WebKitGTK (C)  --JNI upcall-->  NativeWebKit.onLoadChanged(id, status, uriBytes)
 *        -> GlyphEventPump.fireLoadChanged(...)     [runs on WebKit UI thread]
 *            -> snapshot bitmask, iterate set bits only, invoke listeners
 * </pre>
 *
 * <p><b>Zero-allocation guarantees on the emit path:</b>
 * <ul>
 *   <li>no iterator objects — bitmask iteration via trailing-zero counting;</li>
 *   <li>no boxing — every listener parameter is long/int;</li>
 *   <li>no String construction unless a listener actually resolves a slot
 *       through {@link #stringOf(int)} (lazy interning);</li>
 *   <li>one reusable exception carrier for fault isolation (see below).</li>
 * </ul>
 *
 * <p><b>Fault isolation:</b> a misbehaving listener must not starve the others
 * nor crash the GTK main loop. Each invocation is wrapped; the first throwable
 * per emission is captured in a pre-allocated holder, dispatch continues over
 * remaining slots, and the failure surfaces asynchronously via
 * {@link #lastFailure()} inspection by the dispatcher watchdog.
 */
public final class GlyphEventPump {

    private final long nativeId;
    private final SignalBus<WebLoadListener> loadBus = new SignalBus<>();
    private final SignalBus<WebTitleListener> titleBus = new SignalBus<>();
    private final SignalBus<WebConsoleListener> consoleBus = new SignalBus<>();
    private final NativeStringTable strings = new NativeStringTable();

    /** Reusable failure carrier — mutated, never allocated per event. */
    private Throwable lastFailure;
    private int lastFailureSlot = -1;

    /** Set true once close() ran; emissions after this are silently dropped. */
    private volatile boolean tornDown;

    public GlyphEventPump(long nativeId) {
        this.nativeId = nativeId;
    }

    // ------------------------------------------------------------------
    // Registration API (cold path — setup/teardown only)
    // ------------------------------------------------------------------

    public SignalBus.Subscription addLoadListener(WebLoadListener listener) {
        requireLive();
        return registerChecked(loadBus, listener);
    }

    public SignalBus.Subscription addTitleListener(WebTitleListener listener) {
        requireLive();
        return registerChecked(titleBus, listener);
    }

    public SignalBus.Subscription addConsoleListener(WebConsoleListener listener) {
        requireLive();
        return registerChecked(consoleBus, listener);
    }

    public void removeLoadListener(SignalBus.Subscription subscription) {
        loadBus.unregister(subscription);
    }

    public void removeTitleListener(SignalBus.Subscription subscription) {
        titleBus.unregister(subscription);
    }

    public void removeConsoleListener(SignalBus.Subscription subscription) {
        consoleBus.unregister(subscription);
    }

    // ------------------------------------------------------------------
    // Emission API (hot path — called from JNI upcalls on the UI thread)
    // ------------------------------------------------------------------

    /**
     * Fans out a {@code load-changed} signal. Cost with N active listeners:
     * O(N) interface calls, zero allocations, one volatile mask read.
     */
    public void fireLoadChanged(int status, byte[] uriBytes, int uriLength) {
        if (tornDown || loadBus.isEmpty()) {
            return; // fast bail-out: most views have no load listeners attached
        }
        long uriToken = uriBytes == null ? NativeStringTable.NOT_INTERNED : strings.intern(uriBytes, uriLength);
        long active = loadBus.activeMaskSnapshot();
        while (active != 0L) {
            int slot = Long.numberOfTrailingZeros(active);
            active &= active - 1;
            WebLoadListener listener = loadBus.listenerAt(slot);
            if (listener == null) {
                continue; // unregistered between snapshot and touch (re-entrant case)
            }
            try {
                listener.onLoadChanged(nativeId, status, uriToken);
            } catch (Throwable thrown) {
                captureFailure(slot, thrown);
                loadBus.markFailure(slot);
            }
        }
    }

    /** Fans out a document-title change. Same profile as {@link #fireLoadChanged}. */
    public void fireTitleChanged(byte[] titleBytes, int titleLength) {
        if (tornDown || titleBus.isEmpty()) {
            return;
        }
        long titleToken = titleBytes == null
                ? NativeStringTable.NOT_INTERNED
                : strings.intern(titleBytes, titleLength);
        long active = titleBus.activeMaskSnapshot();
        while (active != 0L) {
            int slot = Long.numberOfTrailingZeros(active);
            active &= active - 1;
            WebTitleListener listener = titleBus.listenerAt(slot);
            if (listener == null) {
                continue;
            }
            try {
                listener.onTitleChanged(nativeId, titleToken);
            } catch (Throwable thrown) {
                captureFailure(slot, thrown);
                titleBus.markFailure(slot);
            }
        }
    }

    /** Fans out a JavaScript console message. */
    public void fireConsoleMessage(int severity, int sourceLine, byte[] messageBytes, int messageLength) {
        if (tornDown || consoleBus.isEmpty()) {
            return;
        }
        long messageToken = messageBytes == null
                ? NativeStringTable.NOT_INTERNED
                : strings.intern(messageBytes, messageLength);
        long active = consoleBus.activeMaskSnapshot();
        while (active != 0L) {
            int slot = Long.numberOfTrailingZeros(active);
            active &= active - 1;
            WebConsoleListener listener = consoleBus.listenerAt(slot);
            if (listener == null) {
                continue;
            }
            try {
                listener.onConsoleMessage(nativeId, severity, sourceLine, messageToken);
            } catch (Throwable thrown) {
                captureFailure(slot, thrown);
                consoleBus.markFailure(slot);
            }
        }
    }

    // ------------------------------------------------------------------
    // Lazy string resolution (called by listeners that need text)
    // ------------------------------------------------------------------

    /** Resolves an interned slot to its String, materializing at most once. */
    public String stringOf(long token) {
        return strings.value(token);
    }

    // ------------------------------------------------------------------
    // Failure reporting & teardown
    // ------------------------------------------------------------------

    /** The first throwable captured during the most recent emission, or null. */
    public Throwable lastFailure() {
        return lastFailure;
    }

    public int lastFailureSlot() {
        return lastFailureSlot;
    }

    /** Clears failure state after it has been logged/handled upstream. */
    public void clearFailure() {
        lastFailure = null;
        lastFailureSlot = -1;
    }

    /** Number of listener failures drained across all buses since last check. */
    public int drainFailureCounts() {
        int total = loadBus.drainFailures() + titleBus.drainFailures() + consoleBus.drainFailures();
        return total;
    }

    /**
     * Detaches every listener and clears the string table. Must run on the
     * WebKit UI thread BEFORE the native object is destroyed, so no emission
     * can observe a half-torn-down pump. Idempotent.
     */
    public void tearDown() {
        if (tornDown) {
            return;
        }
        tornDown = true;
        loadBus.clear();
        titleBus.clear();
        consoleBus.clear();
        strings.clear();
        clearFailure();
    }

    public boolean isTornDown() {
        return tornDown;
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private void requireLive() {
        if (tornDown) {
            throw new GlyphWebException("Cannot attach listeners to a disposed web view.");
        }
    }

    private static <L> SignalBus.Subscription registerChecked(SignalBus<L> bus, L listener) {
        if (listener == null) {
            throw new NullPointerException("listener");
        }
        SignalBus.Subscription subscription = bus.register(listener);
        if (subscription == null) {
            throw new GlyphWebException(
                    "SignalBus saturated (" + SignalBus.DEFAULT_CAPACITY + " listeners max). "
                            + "This indicates leaked registrations in the declarative layer.");
        }
        return subscription;
    }

    private void captureFailure(int slot, Throwable thrown) {
        if (lastFailure == null) {
            lastFailure = thrown;
            lastFailureSlot = slot;
        }
    }
}
