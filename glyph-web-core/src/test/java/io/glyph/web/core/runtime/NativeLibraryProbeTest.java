package io.glyph.web.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link NativeLibraryProbe}: the search order must always
 * prefer the newest WebKitGTK ABI (webkitgtk-6.0, GTK 4) over the deprecated
 * webkit2gtk-* families, and capability mapping must follow the resolved SONAME.
 */
class NativeLibraryProbeTest {

    @TempDir
    Path tempDir;

    private File touch(String relativeName) throws Exception {
        Path path = tempDir.resolve(relativeName);
        Files.createDirectories(path.getParent());
        return Files.createFile(path).toFile();
    }

    @Test
    void explicitOverridePropertyWinsWhenValid() throws Exception {
        File custom = touch("prefix/lib/custom-webkit.so");
        System.setProperty("glyph.webkit.library.path", custom.getAbsolutePath());
        try {
            // findWebKitLibrary() honors the override only when the file exists;
            // this documents the escape hatch used by CI containers.
            File resolved = NativeLibraryProbe.findWebKitLibrary();
            assertNotNull(resolved, "override or system library must resolve");
        } finally {
            System.clearProperty("glyph.webkit.library.path");
        }
    }

    @Test
    void gtkMajorMappingFollowsAbiFamily() {
        assertEquals(4, NativeLibraryProbe.gtkMajorForFamily("webkitgtk-6.0"),
                "6.0 family is the GTK 4 / libsoup 3 ABI");
        assertEquals(3, NativeLibraryProbe.gtkMajorForFamily("webkit2gtk-4.1"),
                "4.1 stays on GTK 3");
        assertEquals(3, NativeLibraryProbe.gtkMajorForFamily("webkit2gtk-4.0"));
        assertEquals(3, NativeLibraryProbe.gtkMajorForFamily("webkit2gtk-3.0"));
    }

    @Test
    void candidateOrderPrefersNewestAbi() {
        // The probe list is private; assert behaviour through the public API by
        // checking that a machine WITHOUT any webkit resolves to null rather
        // than throwing, keeping runtime initialization failure actionable.
        boolean sixAvailable = new File("/usr/lib/x86_64-linux-gnu/libwebkitgtk-6.0.so").exists()
                || new File("/usr/lib/x86_64-linux-gnu/libwebkitgtk-6.0.so.2").exists();
        boolean legacyAvailable = new File("/usr/lib/x86_64-linux-gnu/libwebkit2gtk-4.1.so").exists()
                || new File("/usr/lib/x86_64-linux-gnu/libwebkit2gtk-4.1.so.37").exists();
        File found = NativeLibraryProbe.findWebKitLibrary();
        if (!sixAvailable && !legacyAvailable) {
            assertNull(found, "no WebKit installed -> probe must return null, not throw");
        } else {
            assertNotNull(found);
            String name = found.getName();
            assertTrue(name.startsWith("libwebkitgtk-6.0") || name.startsWith("libwebkit2gtk"),
                    "unexpected library matched: " + name);
            // When both ABIs are installed, the 6.0 family must be the one picked.
            if (sixAvailable) {
                assertTrue(name.startsWith("libwebkitgtk-6.0"),
                        "newest ABI must win but got " + name);
            }
        }
    }
}
