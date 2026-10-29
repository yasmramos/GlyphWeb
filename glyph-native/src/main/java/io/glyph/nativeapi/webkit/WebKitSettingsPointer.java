package io.glyph.nativeapi.webkit;

/**
 * Opaque handle for {@code WebKitSettings*}.
 *
 * <p><b>Ownership:</b> BORROWED from the owning {@link WebKitWebViewPointer}.
 * Calling {@code deallocate()} on this pointer would be a use-after-free bug;
 * the core layer enforces this via {@code NativeHandle.acceptsReclamation}.
 */
public final class WebKitSettingsPointer extends WebKitGTKPointer {

    public WebKitSettingsPointer() {
        super();
    }

    public WebKitSettingsPointer(long address) {
        super(address);
    }
}
