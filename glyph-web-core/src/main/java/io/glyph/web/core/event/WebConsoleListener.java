package io.glyph.web.core.event;

/**
 * Receives console output raised by page JavaScript.
 *
 * <p>Severity mirrors {@code WebKitJavascriptErrorLevel} ordinals (0=error …
 * 5=debug) without importing the generated wrapper class into hot paths.
 * Message text travels through the interned slot table like titles/URIs.
 */
@FunctionalInterface
public interface WebConsoleListener {
    void onConsoleMessage(long nativeId, int severity, int sourceLine, long messageToken);
}
