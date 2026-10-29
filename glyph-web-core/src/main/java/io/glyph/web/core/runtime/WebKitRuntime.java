package io.glyph.web.core.runtime;

import io.glyph.web.core.lifecycle.GlyphWebException;
import io.glyph.web.core.lifecycle.WebKitDispatcher;
import io.glyph.web.core.lifecycle.WebKitLifecycleManager;

import java.io.File;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Process-wide singleton owning the WebKitGTK bootstrap sequence:
 *
 * <ol>
 *   <li><b>Probe</b> — locate {@code libwebkit2gtk} on disk (pure JDK, no JNI)</li>
 *   <li><b>Bind</b> — load the JavaCPP-generated stubs for the resolved SONAME</li>
 *   <li><b>Init</b> — run {@code gtk_init_check} ON THE DISPATCHER THREAD ONLY</li>
 *   <li><b>Register shutdown hook</b> — drain the lifecycle manager last resort</li>
 * </ol>
 *
 * <p>{@link #initialize()} is idempotent and thread-safe (CAS-guarded). Glyph UI
 * calls it once from {@code main()}; every other GlyphWeb API implicitly
 * requires it and throws an actionable error otherwise.
 */
public final class WebKitRuntime {

    private static final WebKitRuntime INSTANCE = new WebKitRuntime();

    private final AtomicBoolean initialized = new AtomicBoolean();
    private volatile WebKitDispatcher dispatcher;
    private volatile WebKitLifecycleManager lifecycleManager;
    private volatile WebKitCapabilities capabilities;

    private WebKitRuntime() {
    }

    /** The one and only runtime instance. */
    public static WebKitRuntime getInstance() {
        return INSTANCE;
    }

    /**
     * Performs the four-step bootstrap. Safe to call concurrently; exactly one
     * caller wins and the rest block until initialization finished or failed.
     *
     * @throws GlyphWebException when WebKitGTK cannot be found or GTK init fails
     */
    public synchronized void initialize() {
        if (initialized.get()) {
            return;
        }
        File library = NativeLibraryProbe.findWebKitLibrary();
        if (library == null) {
            throw new GlyphWebException(
                    "No WebKitGTK runtime found (searched webkitgtk-6.0, then legacy "
                            + "webkit2gtk 4.1/4.0/3.0). Install the latest package "
                            + "'libwebkitgtk-6.0-dev' (Debian/Ubuntu, WebKitGTK >= 2.50, "
                            + "GTK 4 based) or its 'libwebkit2gtk-4.1-dev' predecessor, "
                            + "or set -Dglyph.webkit.library.path=/absolute/path/to/lib.");
        }
        if (!NativeLibraryProbe.hasDisplay()) {
            throw new GlyphWebException(
                    "No DISPLAY/WAYLAND_DISPLAY environment variable found; "
                            + "WebKitGTK cannot create a rendering context headless. "
                            + "Use xvfb-run for CI environments.");
        }

        WebKitDispatcher newDispatcher = new WebKitDispatcher();
        WebKitLifecycleManager newManager = new WebKitLifecycleManager();

        // gtk_init MUST happen on the same thread that will own all GTK calls.
        newDispatcher.invokeAndWait(() -> {
            io.glyph.nativeapi.webkit.NativeWebKit.requireAvailable();
            int ok = io.glyph.nativeapi.webkit.NativeWebKit.gtkInitCheck(0L, 0L);
            if (ok == 0) {
                throw new GlyphWebException("gtk_init_check() failed for library " + library);
            }
        });

        this.dispatcher = newDispatcher;
        this.lifecycleManager = newManager;
        this.capabilities = buildCapabilities(library);

        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "glyph-webkit-shutdown"));
        initialized.set(true);
    }

    private static WebKitCapabilities buildCapabilities(File library) {
        String name = library.getName();
        String family = name.replaceFirst("^lib", "").replaceFirst("\\.so.*$", "")
                .replaceFirst("\\.dylib.*$", "");
        Set<WebKitCapabilities.Feature> features = EnumSet.of(
                WebKitCapabilities.Feature.MULTIPROCESS,
                WebKitCapabilities.Feature.WEB_EXTENSIONS,
                WebKitCapabilities.Feature.IME);
        // Sandbox (bubblewrap) and DMA-BUF acceleration are available on every
        // modern family: the GTK4 webkitgtk-6.0 ABI and the libsoup3 4.1 ABI.
        if (family.startsWith("webkitgtk-6.0") || family.endsWith("4.1") || family.endsWith("4.0")) {
            features.add(WebKitCapabilities.Feature.SANDBOX);
            features.add(WebKitCapabilities.Feature.HARDWARE_ACCELERATION);
        }
        int gtkMajor = NativeLibraryProbe.gtkMajorForFamily(family);
        return new WebKitCapabilities(library.getAbsolutePath(), family, gtkMajor, features);
    }

    /** Dispatcher used by every component; throws if the runtime is not up yet. */
    public WebKitDispatcher dispatcher() {
        requireInitialized();
        return dispatcher;
    }

    /** Lifecycle registry bound to this runtime. */
    public WebKitLifecycleManager lifecycleManager() {
        requireInitialized();
        return lifecycleManager;
    }

    /** Detected feature matrix. */
    public WebKitCapabilities capabilities() {
        requireInitialized();
        return capabilities;
    }

    public boolean isInitialized() {
        return initialized.get();
    }

    private void requireInitialized() {
        if (!initialized.get()) {
            throw new GlyphWebException(
                    "WebKitRuntime.initialize() has not been called. "
                            + "Bootstrap the runtime before creating any GlyphWebView.");
        }
    }

    /** Idempotent teardown wired to the JVM shutdown hook. */
    private void shutdown() {
        if (!initialized.compareAndSet(true, false)) {
            return;
        }
        try {
            lifecycleManager.shutdownAll(); // views first...
        } finally {
            dispatcher.close();            // ...then stop the thread that owns them
        }
    }
}
