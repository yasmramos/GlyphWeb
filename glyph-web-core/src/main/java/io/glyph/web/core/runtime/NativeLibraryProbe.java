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
 *
 * <p><b>API family priority:</b> the search order always prefers the newest
 * available ABI. As of WebKitGTK 2.50+ the only modern family is
 * {@code webkitgtk-6.0} (GTK 4 / libsoup 3); every {@code webkit2gtk-*}
 * variant is deprecated upstream and exists here solely as a fallback for
 * older distributions. The unversioned dev symlink ({@code libX.so}) is tried
 * before versioned runtime SONAMEs so a {@code -dev} package wins over a bare
 * runtime library.
 */
final class NativeLibraryProbe {

    /**
     * SONAME search list, newest API family first. {@code webkitgtk-6.0} is
     * the current GTK4 ABI (libsoup 3); 4.1 targets libsoup 3 under the legacy
     * GTK3 ABI; 4.0/3.0 are kept only for legacy distros.
     */
    private static final List<String> CANDIDATES = List.of(
            "webkitgtk-6.0", "webkit2gtk-4.1", "webkit2gtk-4.0", "webkit2gtk-3.0");

    private static final List<String> SEARCH_DIRS = List.of(
            "/usr/lib", "/usr/lib64", "/usr/lib/x86_64-linux-gnu",
            "/usr/local/lib", "/lib/x86_64-linux-gnu",
            "/usr/lib/aarch64-linux-gnu");

    /** Common linker-script locations used by pkg-config multiarch layouts. */
    private static final List<String> PKGCONFIG_DIRS = List.of(
            "/usr/lib/x86_64-linux-gnu/pkgconfig",
            "/usr/lib/aarch64-linux-gnu/pkgconfig",
            "/usr/lib64/pkgconfig",
            "/usr/local/lib/pkgconfig");

    private NativeLibraryProbe() {
    }

    /** Returns the absolute path of the first candidate found, or {@code null}. */
    static File findWebKitLibrary() {
        // Explicit override first: lets CI containers and custom prefixes pin a
        // specific build (e.g. a locally compiled WebKitGTK 2.50 nightly).
        String override = System.getProperty("glyph.webkit.library.path");
        if (override != null && !override.isBlank()) {
            File pinned = new File(override);
            if (isUsableFile(pinned)) {
                return pinned;
            }
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String suffix = os.contains("mac") ? ".dylib" : ".so";
        for (String soname : CANDIDATES) {
            for (String dir : SEARCH_DIRS) {
                // Unversioned dev symlink first (present when -dev is installed).
                File candidate = new File(dir, "lib" + soname + suffix);
                if (isUsableFile(candidate)) {
                    return candidate;
                }
                // Then the common versioned runtime SONAMEs (e.g. .so.37 for
                // webkit2gtk, .so.2 for webkitgtk-6.0).
                for (int revision : new int[] {37, 2}) {
                    File versioned = new File(dir, "lib" + soname + suffix + "." + revision);
                    if (isUsableFile(versioned)) {
                        return versioned;
                    }
                }
            }
        }
        // Last resort: ask pkg-config where the 6.0 family actually lives.
        File fromPkgConfig = queryPkgConfig();
        return fromPkgConfig != null ? fromPkgConfig : null;
    }

    /** True for regular readable files AND for valid symlinks to them. */
    private static boolean isUsableFile(File file) {
        return file.exists() && file.canRead() && !file.isDirectory();
    }

    /**
     * Parses {@code pkg-config --variable=libdir webkitgtk-6.0} output without
     * spawning a subprocess (reads the .pc file directly), so probing stays
     * cheap and side-effect free.
     */
    private static File queryPkgConfig() {
        for (String dir : PKGCONFIG_DIRS) {
            File pc = new File(dir, "webkitgtk-6.0.pc");
            if (!pc.isFile()) {
                continue;
            }
            try {
                for (String line : java.nio.file.Files.readAllLines(pc.toPath())) {
                    if (line.startsWith("libdir=")) {
                        String libdir = line.substring("libdir=".length()).trim();
                        File so = new File(libdir, "libwebkitgtk-6.0.so");
                        if (isUsableFile(so)) {
                            return so;
                        }
                    }
                }
            } catch (java.io.IOException ignored) {
                // Unreadable .pc file — keep searching other prefixes.
            }
        }
        return null;
    }

    /** Maps a resolved SONAME family to the major GTK version it requires. */
    static int gtkMajorForFamily(String family) {
        // webkitgtk-6.0 links against GTK 4; every webkit2gtk-* variant uses GTK 3.
        return family.startsWith("webkitgtk-6.0") ? 4 : 3;
    }

    /** True when a display server is reachable (X11 or Wayland). */
    static boolean hasDisplay() {
        return System.getenv("DISPLAY") != null || System.getenv("WAYLAND_DISPLAY") != null;
    }
}
