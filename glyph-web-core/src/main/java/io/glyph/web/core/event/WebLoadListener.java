package io.glyph.web.core.event;

/**
 * Receives page load state transitions for one {@code GlyphWebView}.
 *
 * <p>Signature rule for ALL GlyphWeb listener interfaces: parameters must be
 * primitives ({@code long}/{@code int}/{@code double}) or pre-allocated value
 * carriers. Never boxed types, never varargs, never generic collections —
 * those force an allocation on every native upcall and defeat the zero-allocation
 * fan-out design of {@link SignalBus}.
 *
 * <p>{@code status} carries a {@link WebKitSignal.LoadStatus} code; {@code uriSlot}
 * is a handle into the view's interned URI table (resolved lazily via
 * {@code GlyphEventPump.uriOf(...)}), so this callback itself never allocates a
 * String even when nobody consumes the URI.
 */
@FunctionalInterface
public interface WebLoadListener {
    void onLoadChanged(long nativeId, int status, long uriToken);
}
