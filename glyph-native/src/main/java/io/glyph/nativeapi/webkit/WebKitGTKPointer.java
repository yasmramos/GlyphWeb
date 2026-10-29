package io.glyph.nativeapi.webkit;

import org.bytedeco.javacpp.Pointer;

/**
 * Base type for every opaque WebKitGTK pointer handled by GlyphWeb.
 *
 * <p>Design notes (why JavaCPP and not hand-written JNI):
 * <ul>
 *   <li><b>Zero reflection on hot paths:</b> JavaCPP resolves native methods
 *       through statically generated C++ stubs at build time. Once a class is
 *       loaded, calls such as {@code webkit_web_view_load_uri} compile down to
 *       direct {@code JNI_OnLoad}-registered function pointers.</li>
 *   <li><b>Deterministic deallocation:</b> release is invoked explicitly by the
 *       lifecycle manager in {@code glyph-web-core}; we deliberately do NOT rely
 *       on the Pointer finalizer as the primary release path (finalizers add GC
 *       pressure and have unbounded latency, which is unacceptable for
 *       GPU-backed native buffers owned by WebKit).</li>
 * </ul>
 *
 * <p>This hierarchy mirrors GObject inheritance of the underlying C library:
 * <pre>
 *   Pointer
 *     └── WebKitGTKPointer                  (marker + shared policy)
 *           ├── GtkWindowPointer            (GtkWidget* / GtkWindow*)
 *           ├── WebKitWebViewPointer        (WebKitWebView*)
 *           └── WebKitSettingsPointer       (WebKitSettings*)
 * </pre>
 */
public abstract class WebKitGTKPointer extends Pointer {

    /**
     * The stable native address this handle points to. Kept as our own field
     * because {@link Pointer} exposes only a no-arg {@code address()} getter
     * and its protected re-pointing helper requires an existing allocation.
     * GlyphWeb treats raw addresses ({@code long}) as the canonical currency
     * across the JNI boundary; these wrapper types exist mainly to give the
     * compiler something to hang ownership documentation on.
     */
    protected final long nativeAddress;

    /** Creates a NULL handle (address 0). */
    protected WebKitGTKPointer() {
        super();
        this.nativeAddress = 0L;
    }

    /**
     * Wraps an existing native address obtained from a factory function.
     *
     * @param nativeAddress raw native address; ownership semantics are defined
     *                      by the concrete subclass documentation (owned vs
     *                      borrowed).
     */
    protected WebKitGTKPointer(long nativeAddress) {
        super();
        this.nativeAddress = nativeAddress;
    }

    /** Returns the wrapped native address ({@code 0} when NULL). */
    public final long address() {
        return nativeAddress;
    }

    /**
     * Returns {@code true} when this pointer references no native memory.
     * Kept final so callers never compare against {@code null} pointers
     * incorrectly (a NULL {@link Pointer} is still a valid Java object).
     */
    public final boolean isNull() {
        return nativeAddress == 0L;
    }
}
