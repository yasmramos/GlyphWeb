package io.glyph.nativeapi.webkit;

/**
 * Opaque handle for a top-level GTK window ({@code GtkWindow*}).
 *
 * <p><b>Ownership:</b> borrowed. The window is owned by the hosting toolkit
 * (or by Glyph UI's own windowing layer); GlyphWeb never destroys it.
 */
public final class GtkWindowPointer extends WebKitGTKPointer {

    public GtkWindowPointer() {
        super();
    }

    public GtkWindowPointer(long address) {
        super(address);
    }
}
