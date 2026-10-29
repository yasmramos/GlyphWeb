package io.glyph.web.core.event;

/**
 * Receives document title mutations for one {@code GlyphWebView}.
 *
 * @see WebLoadListener for the primitive-signature contract shared by all
 *      GlyphWeb event interfaces.
 */
@FunctionalInterface
public interface WebTitleListener {
    /** {@code titleSlot} indexes the interned-string table in GlyphEventPump. */
    void onTitleChanged(long nativeId, long titleToken);
}
