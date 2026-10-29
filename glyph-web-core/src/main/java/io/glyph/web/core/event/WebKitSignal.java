package io.glyph.web.core.event;

/**
 * Stable, primitive-only identifiers for WebKit signals routed into Java.
 *
 * <p>Values double as indices into the per-view bus table inside
 * {@code GlyphEventPump}, keeping dispatch a plain array access — no string
 * comparisons, no hash lookups, no virtual dispatch through maps.
 */
public final class WebKitSignal {

    /** {@code load-changed}: page load transitioned (see LoadStatus codes). */
    public static final int LOAD_CHANGED = 0;

    /** {@code notify-uri} / {@code notify-title}: document title mutated. */
    public static final int TITLE_CHANGED = 1;

    /** Console message emitted by page JavaScript. */
    public static final int CONSOLE_MESSAGE = 2;

    /** Navigation policy decision requested before a load starts. */
    public static final int NAVIGATION_POLICY = 3;

    /** Script dialog (alert/confirm/prompt) requested by the page. */
    public static final int DIALOG_REQUESTED = 4;

    /** Number of signal kinds; sizes the bus table. Keep LAST. */
    public static final int COUNT = 5;

    private WebKitSignal() {
    }

    /**
     * Numeric mirror of {@code WebKitLoadEvent} so listeners never depend on
     * generated enum wrappers (which would allocate on every JNI upcall).
     */
    public static final class LoadStatus {
        /** Load started (main resource request issued). */
        public static final int STARTED = 0;
        /** Redirect issued before the committed load. */
        public static final int REDIRECTED = 1;
        /** Main resource committed; DOM construction began. */
        public static final int COMMITTED = 2;
        /** Load finished successfully. */
        public static final int FINISHED = 3;
        /** Load failed; see error details via the failing-load channel. */
        public static final int FAILED = 4;
        /** Load aborted (e.g. user-initiated stop or new navigation). */
        public static final int ABORTED = 5;

        private LoadStatus() {
        }
    }
}
