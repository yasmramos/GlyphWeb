package io.glyph.web.core.lifecycle;

/**
 * Thrown when an operation violates the {@link WebViewState} contract, e.g.
 * navigating a destroyed view or attaching a view twice.
 */
public final class IllegalWebViewStateException extends GlyphWebException {

    private static final long serialVersionUID = 1L;

    IllegalWebViewStateException(String message) {
        super(message);
    }
}
