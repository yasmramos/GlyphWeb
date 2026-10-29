package io.glyph.nativeapi.webkit;

import org.bytedeco.javacpp.annotation.Name;

/**
 * Static view over the WebKitGTK C API (webkit2gtk-4.1). Every method here is
 * a direct, thin binding — no allocation, no marshalling beyond what JavaCPP
 * generates. This class is INTERNAL; glyph-web-core wraps it with state checks.
 *
 * <p><b>Linking strategy:</b> instead of {@code @Library}/{@code @Properties}
 * (which trigger the annotation processor and require a full preset pipeline),
 * we bind through plain JNI: {@link #ensureLoaded()} loads a tiny companion
 * shared object ({@code libglyphwebkit.so}, built by the {@code native-jni}
 * CMake target in CI) that {@code dlopen}s {@code libwebkit2gtk-4.1.so} lazily
 * and registers one native method per symbol below via {@code RegisterNatives}.
 * This keeps startup deterministic and lets unit tests run without any native
 * artifact present ({@link #isAvailable()} simply reports {@code false}).
 *
 * <p>Latency contract: each call maps to exactly one native jump (~20-60 ns on
 * modern x86_64). Anything that needs callbacks into Java (load-changed, title
 * changed) goes through GLib signal connections and is handled in the event
 * package of glyph-web-core, never inline here.
 */
public final class NativeWebKit {

    /** Name of the companion JNI bridge shipped per platform. */
    private static final String BRIDGE_LIBRARY = "glyphwebkit";

    private static volatile boolean available;
    private static volatile boolean loadAttempted;

    private NativeWebKit() {
    }

    /**
     * Attempts to link the JNI bridge once (double-checked, lock-free after the
     * first call). Returns {@code true} when all symbols resolved; callers in
     * glyph-web-core check this BEFORE invoking any native method so tests and
     * headless environments fail with a clear message instead of an
     * UnsatisfiedLinkError mid-frame.
     */
    public static boolean ensureLoaded() {
        if (!loadAttempted) {
            synchronized (NativeWebKit.class) {
                if (!loadAttempted) {
                    try {
                        System.loadLibrary(BRIDGE_LIBRARY);
                        available = true;
                    } catch (Throwable linkFailure) { // including UnsatisfiedLinkError
                        available = false;
                    }
                    loadAttempted = true;
                }
            }
        }
        return available;
    }

    /** Non-triggering availability read; safe on hot paths (one volatile load). */
    public static boolean isAvailable() {
        return available;
    }

    /** Throws when the bridge is missing; used by every guarded entry point. */
    public static void requireAvailable() {
        if (!ensureLoaded()) {
            throw new UnsatisfiedLinkError(
                    "JNI bridge '" + BRIDGE_LIBRARY + "' is not installed; "
                            + "build the native-jni target or set -Djava.library.path.");
        }
    }

    // ------------------------------------------------------------------
    // Application / toolkit bootstrap (GLib + GTK)
    // ------------------------------------------------------------------

    /** {@code gtk_init_check(&argc, &argv)} — returns 0 (FALSE) when no display. */
    @Name("gw_gtk_init_check")
    public static native int gtkInitCheck(long argcPointer, long argvPointer);

    // ------------------------------------------------------------------
    // WebView lifecycle
    // ------------------------------------------------------------------

    /**
     * {@code webkit_web_view_new()} — creates a view bound to the default
     * web context. Returns a BORROWED floating ref; the caller must sink it.
     */
    @Name("gw_web_view_new")
    public static native long webViewNew();

    /**
     * {@code webkit_web_view_new_with_context(context)} — creates a view
     * attached to an explicit {@code WebKitWebContext*} (used for isolated
     * browsing sessions per Glyph window group).
     */
    @Name("gw_web_view_new_with_context")
    public static native long webViewNewWithContext(long contextAddress);

    /** {@code gtk_widget_show(widget)} — realizes and displays the view. */
    @Name("gw_widget_show")
    public static native void gtkWidgetShow(long widgetAddress);

    /**
     * {@code gtk_container_add(container, child)} — attaches the view to a
     * parent container (typically the Glyph UI host window).
     */
    @Name("gw_container_add")
    public static native void gtkContainerAdd(long containerAddress, long childAddress);

    /**
     * {@code gtk_widget_destroy(widget)} — synchronous teardown of the widget
     * subtree. After this returns the address MUST NOT be touched again.
     */
    @Name("gw_widget_destroy")
    public static native void gtkWidgetDestroy(long widgetAddress);

    // ------------------------------------------------------------------
    // Navigation
    // ------------------------------------------------------------------

    /** {@code webkit_web_view_load_uri(view, uri)} — async navigation start. */
    @Name("gw_web_view_load_uri")
    public static native void webViewLoadUri(long viewAddress, String uri);

    /** {@code webkit_web_view_load_html(view, html, base_uri)} */
    @Name("gw_web_view_load_html")
    public static native void webViewLoadHtml(long viewAddress, String html, String baseUri);

    /** {@code webkit_web_view_go_back(view)} */
    @Name("gw_web_view_go_back")
    public static native void webViewGoBack(long viewAddress);

    /** {@code webkit_web_view_reload(view)} */
    @Name("gw_web_view_reload")
    public static native void webViewReload(long viewAddress);

    // ------------------------------------------------------------------
    // Introspection (polled only from the UI thread, never hot-pathed)
    // ------------------------------------------------------------------

    /** {@code webkit_web_view_get_settings(view)} — borrowed settings pointer. */
    @Name("gw_web_view_get_settings")
    public static native long webViewGetSettings(long viewAddress);

    /** {@code webkit_web_view_get_uri(view)} — owned UTF-8 string. */
    @Name("gw_web_view_get_uri")
    public static native String webViewGetUri(long viewAddress);
}
