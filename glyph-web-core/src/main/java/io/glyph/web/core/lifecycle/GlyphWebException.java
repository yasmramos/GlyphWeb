package io.glyph.web.core.lifecycle;

/**
 * Unchecked exception for all GlyphWeb failures. Kept single-typed on purpose:
 * checked exceptions would force try/catch noise into every Glyph UI binding
 * site, and the JVM cannot recover from native-layer failures anyway —
 * the correct response is always "stop touching this resource".
 */
public class GlyphWebException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public GlyphWebException(String message) {
        super(message);
    }

    public GlyphWebException(String message, Throwable cause) {
        super(message, cause);
    }
}
