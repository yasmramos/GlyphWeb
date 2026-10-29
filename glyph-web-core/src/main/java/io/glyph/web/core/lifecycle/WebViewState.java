package io.glyph.web.core.lifecycle;

/**
 * Explicit state machine for a {@link GlyphWebView}. Modeled as an enum with
 * ordinal-ordered transitions so the lifecycle manager can validate moves with
 * a single integer comparison on the hot path (no EnumSet allocation).
 *
 * <pre>
 *   NEW ──▶ CREATED ──▶ ATTACHED ──▶ VISIBLE ──▶ DESTROYING ──▶ DESTROYED
 *              │                        ▲            ▲
 *              └────────── close() ─────┴────────────┘  (any non-DESTROYED state)
 * </pre>
 */
public enum WebViewState {

    /** Java object exists; no native resources allocated yet. */
    NEW(0),

    /** {@code webkit_web_view_new()} succeeded; native pointer is live but detached. */
    CREATED(1),

    /** View added to a GTK container; parent owns placement, we own destruction. */
    ATTACHED(2),

    /** {@code gtk_widget_show} issued; view is painting. */
    VISIBLE(3),

    /** Teardown started. Native pointer already destroyed; draining callbacks. */
    DESTROYING(4),

    /** Terminal state. All native references released; instance is inert. */
    DESTROYED(5);

    private final int order;

    WebViewState(int order) {
        this.order = order;
    }

    /** True when the native pointer behind this state is safe to dereference. */
    public boolean isNativeAlive() {
        return order >= CREATED.order && order <= VISIBLE.order;
    }

    /** True when lifecycle transitions out of this state are still possible. */
    public boolean isTerminal() {
        return this == DESTROYED;
    }

    /**
     * Validates a transition against the state machine. Kept branchless-ish:
     * callers pass the target enum constant; illegal backward jumps throw.
     */
    public boolean canTransitionTo(WebViewState next) {
        return switch (this) {
            case NEW -> next == CREATED || next == DESTROYING || next == DESTROYED;
            case CREATED -> next == ATTACHED || next == VISIBLE || next == DESTROYING;
            case ATTACHED -> next == VISIBLE || next == DESTROYING;
            case VISIBLE -> next == DESTROYING;
            case DESTROYING -> next == DESTROYED;
            case DESTROYED -> false;
        };
    }
}
