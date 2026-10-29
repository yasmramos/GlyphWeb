package io.glyph.web.core.runtime;

import java.util.Set;

/**
 * Immutable capability snapshot produced by {@link WebKitRuntime#initialize()}.
 * Glyph UI reads this once at startup to decide which declarative features to
 * enable (e.g. hardware-accelerated compositing vs. software fallback).
 *
 * @param webkitLibraryPath   absolute path of the loaded shared library
 * @param apiFamily           resolved SONAME family, e.g. {@code "webkit2gtk-4.1"}
 * @param gtkMajor            major GTK version in use (2, 3 or 4)
 * @param capabilities        feature flags; see {@link Feature}
 */
public record WebKitCapabilities(
        String webkitLibraryPath,
        String apiFamily,
        int gtkMajor,
        Set<Feature> capabilities) {

    /** Feature toggles GlyphWeb advertises to Glyph UI. */
    public enum Feature {
        /** Multi-process rendering (one web process per view group). */
        MULTIPROCESS,
        /** Sandbox via bubblewrap; requires unprivileged user namespaces. */
        SANDBOX,
        /** DMA-BUF zero-copy scanout; avoids CPU blit on the frame hot path. */
        HARDWARE_ACCELERATION,
        /** Web Extensions API available (epoll-based extension host). */
        WEB_EXTENSIONS,
        /** Input method editor support for CJK glyph composition. */
        IME
    }

    public boolean supports(Feature feature) {
        return capabilities.contains(feature);
    }
}
