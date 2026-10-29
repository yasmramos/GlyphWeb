package io.glyph.web.core.runtime;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * Best-effort detector for a usable WebKitGTK installation.
 *
 * <p>Kept dependency-free (pure JDK) so {@link WebKitRuntime} can probe the
 * environment BEFORE attempting to load JavaCPP-generated bindings — failing
 * early with an actionable message beats an opaque UnsatisfiedLinkError from
 * deep inside generated stub code.
 */
final class NativeLibraryProbe {

    /** SONAME search list, newest API first (4.1 supersedes the deprecated 4.0). */
    private static final List<String> CANDIDATES = List.of(
            "webkit2gtk-4.1", "webkit2gtk-4.0", "webkit2gtk-3.0");

    private static final List<String> SEARCH_DIRS = List.of(
            "/usr/lib", "/usr/lib64", "/usr/lib/x86_64-linux-gnu",
            "/usr/local/lib", "/lib/x86_64-linux-gnu",
            "/usr/lib/aarch64-linux-gnu");

    private NativeLibraryProbe() {
    }

    /** Returns the absolute path of the first candidate found, or {@code null}. */
    static File findWebKitLibrary() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String suffix = os.contains("mac") ? ".dylib" : ".so";
        for (String soname : CANDIDATES) {
            for (String dir : SEARCH_DIRS) {
                File candidate = new File(dir, "lib" + soname + suffix);
                if (candidate.isFile() && candidate.canRead()) {
                    return candidate;
                }
                // Versioned symlinks like libwebkit2gtk-4.1.so.37 also count.
                File versioned = new File(dir, "lib" + soname + suffix + ".37");
                if (versioned.exists()) {
                    return versioned;
                }
            }
        }
        return null;
    }

    /** True when a display server is reachable (X11 or Wayland). */
    static boolean hasDisplay() {
        return System.getenv("DISPLAY") != null || System.getenv("WAYLAND_DISPLAY") != null;
    }
}
