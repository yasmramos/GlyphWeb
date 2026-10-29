package io.glyph.web.core.lifecycle;

import io.glyph.nativeapi.webkit.GtkWindowPointer;
import io.glyph.nativeapi.webkit.NativeWebKit;
import io.glyph.web.core.runtime.WebKitRuntime;

import java.util.Objects;

/**
 * Safe, lifecycle-managed wrapper around a native {@code WebKitWebView}.
 *
 * <p>This is the primary public entry point consumed by Glyph UI. A typical
 * declarative usage inside a {@code .glyph} template compiles down to:
 *
 * <pre>{@code
 * try (GlyphWebView view = GlyphWebView.create()) {
 *     view.attachToParent(hostWindow);
 *     view.makeVisible();
 *     view.loadUri("https://glyph.dev/welcome");
 * } // close() is deterministic: native destroy happens here, not at GC time
 * }</pre>
 *
 * <p><b>Thread model:</b> every method that crosses the native boundary is
 * marshalled through {@link WebKitDispatcher#invokeAndWait}, so callers may use
 * this class from any thread without risking GTK's single-threaded core.
 * Cheap accessors ({@link #nativeId()}, {@link #state()}) are lock-free reads.
 *
 * <p><b>State machine:</b> transitions are validated against
 * {@link WebViewState}; illegal sequences throw
 * {@link IllegalWebViewStateException} instead of corrupting native memory.
 */
public final class GlyphWebView implements NativeHandle {

    private final WebKitLifecycleManager manager;
    private final WebKitDispatcher dispatcher;
    private final long id;

    /** Volatile: read by liveness probes on other threads, written only under dispatcher. */
    private volatile long address;
    private volatile WebViewState state;
    private volatile boolean closed;

    /**
     * Creates and registers a new detached web view bound to the default web
     * context. The caller owns the returned instance — prefer try-with-resources.
     */
    @GUI
    public static GlyphWebView create() {
        WebKitRuntime runtime = WebKitRuntime.getInstance();
        GlyphWebView view = new GlyphWebView(runtime.lifecycleManager(), runtime.dispatcher());
        view.doCreate();
        return view;
    }

    private GlyphWebView(WebKitLifecycleManager manager, WebKitDispatcher dispatcher) {
        this.manager = Objects.requireNonNull(manager, "manager");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.id = NativeId.next();
        this.state = WebViewState.NEW;
    }

    // ------------------------------------------------------------------
    // Lifecycle transitions
    // ------------------------------------------------------------------

    private void doCreate() {
        transitionTo(WebViewState.CREATED);
        dispatcher.invokeAndWait(() -> {
            NativeWebKit.requireAvailable();
            long created = NativeWebKit.webViewNew();
            if (created == 0L) {
                throw new GlyphWebException("webkit_web_view_new() returned NULL.");
            }
            this.address = created;
        });
        manager.register(this);
    }

    /** Attaches the view to a native parent container (e.g. a GtkBox or window). */
    @GUI
    public void attachToParent(GtkWindowPointer parentContainer) {
        Objects.requireNonNull(parentContainer, "parentContainer");
        requireAlive();
        transitionTo(WebViewState.ATTACHED);
        long parentAddress = parentContainer.address();
        dispatcher.invokeAndWait(
                () -> NativeWebKit.gtkContainerAdd(parentAddress, uncheckedAddress()));
    }

    /** Shows the view; it begins painting after this call returns. */
    @GUI
    public void makeVisible() {
        requireAlive();
        transitionTo(WebViewState.VISIBLE);
        dispatcher.invokeAndWait(() -> NativeWebKit.gtkWidgetShow(uncheckedAddress()));
    }

    /** Navigates asynchronously. Completion is signalled via the event package. */
    public void loadUri(String uri) {
        Objects.requireNonNull(uri, "uri");
        requireAlive();
        dispatcher.invokeAndWait(() -> NativeWebKit.webViewLoadUri(uncheckedAddress(), uri));
    }

    /** Renders an HTML string with an optional base URI for relative resources. */
    public void loadHtml(String html, String baseUri) {
        Objects.requireNonNull(html, "html");
        requireAlive();
        dispatcher.invokeAndWait(
                () -> NativeWebKit.webViewLoadHtml(uncheckedAddress(), html, baseUri));
    }

    /** Current document URI, or {@code null} when nothing is loaded yet. */
    public String currentUri() {
        requireAlive();
        String[] box = new String[1];
        dispatcher.invokeAndWait(() -> box[0] = NativeWebKit.webViewGetUri(uncheckedAddress()));
        return box[0];
    }

    // ------------------------------------------------------------------
    // NativeHandle contract
    // ------------------------------------------------------------------

    @Override
    public long nativeId() {
        return id;
    }

    @Override
    public long nativeAddress() {
        return address;
    }

    /** Current lifecycle state; safe to poll from any thread. */
    public WebViewState state() {
        return state;
    }

    /**
     * Deterministically destroys the native view. Idempotent and blocking:
     * when this method returns, the GTK object is gone and the registry slot
     * is freed. Never called from a finalizer as the primary path — the
     * shutdown hook in {@link WebKitRuntime} is the safety net instead.
     */
    @Override
    public void close() {
        if (closed) {
            return; // idempotent by contract
        }
        closed = true;
        transitionTo(WebViewState.DESTROYING);
        manager.markReleased(id);
        dispatcher.invokeAndWait(() -> {
            long target = address;
            if (target != 0L) {
                NativeWebKit.gtkWidgetDestroy(target);
                address = 0L; // poison our own handle before anyone can re-read it
            }
        });
        transitionTo(WebViewState.DESTROYED);
        manager.unregister(id);
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /** Address read that MUST only run inside dispatcher tasks after requireAlive(). */
    private long uncheckedAddress() {
        long target = address;
        if (target == 0L) {
            throw new IllegalWebViewStateException(
                    "Native pointer vanished for view id=" + id + " (double destroy?).");
        }
        return target;
    }

    private void requireAlive() {
        if (state.isTerminal() || !manager.isAlive(id)) {
            throw new IllegalWebViewStateException(
                    "GlyphWebView id=" + id + " is " + state + "; operation requires a live view.");
        }
    }

    private void transitionTo(WebViewState next) {
        WebViewState current = state;
        if (!current.canTransitionTo(next)) {
            throw new IllegalWebViewStateException(
                    "Illegal transition for view id=" + id + ": " + current + " -> " + next);
        }
        state = next;
    }
}
