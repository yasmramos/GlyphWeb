package io.glyph.nativeapi.webkit;

/**
 * Opaque handle for {@code WebKitWebView*} — the central native object whose
 * lifecycle GlyphWeb manages end-to-end.
 *
 * <p><b>Ownership:</b> OWNED once attached to a parent widget (the floating
 * GObject ref is sunk by {@code g_object_ref_sink} during creation). Release
 * must happen through {@link NativeWebKit#destroyWebView}, which issues
 * {@code gtk_widget_destroy}; destroying the view cascades the release of the
 * associated {@code WebKitWebPage} / network session in the web process.
 */
public final class WebKitWebViewPointer extends WebKitGTKPointer {

    public WebKitWebViewPointer() {
        super();
    }

    public WebKitWebViewPointer(long address) {
        super(address);
    }
}
