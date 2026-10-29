package io.glyph.web.core.lifecycle;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Single-writer execution context that owns every call into WebKitGTK.
 *
 * <p><b>Why a dedicated thread:</b> GTK is not thread-safe; creation,
 * navigation and destruction must all run on one thread (traditionally the
 * process main thread). We encapsulate that contract behind an owned
 * single-thread executor so the rest of GlyphWeb stays synchronous-friendly:
 * {@link #invokeAndWait} blocks until the native side confirms, giving
 * deterministic ordering for declarative UI reconciliation without leaking
 * blocking semantics into widgets.
 *
 * <p><b>Allocation profile:</b> {@code post()} allocates one lambda per task
 * (~16 bytes, young-gen only — collected without promotion). {@code invokeAndWait}
 * additionally allocates a latch plus a boxed throwable slot. Neither is on the
 * per-frame hot path; frame-critical work (event fan-out) uses pre-allocated
 * listener arrays in the event package instead.
 *
 * <p><b>Future work:</b> when embedded in apps that already run the GTK main
 * loop, this class will grow an {@code attachToExternalLoop(...)} mode routing
 * tasks through {@code g_idle_add}. The public API is designed so the swap is
 * invisible to callers.
 */
public final class WebKitDispatcher implements AutoCloseable {

    /** A unit of work executed on the WebKit UI thread. Checked exceptions allowed. */
    @FunctionalInterface
    public interface NativeTask {
        void run() throws Exception;
    }

    private final ExecutorService executor;
    private volatile Thread uiThread; // captured by the first task that runs on the worker
    private volatile boolean running = true;

    public WebKitDispatcher() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "glyph-webkit-ui");
            thread.setDaemon(true); // never keep the JVM alive by itself
            return thread;
        };
        this.executor = Executors.newSingleThreadExecutor(factory);
    }

    /** True when the caller IS the UI thread. One volatile read + reference compare. */
    public boolean isOnUIThread() {
        return Thread.currentThread() == uiThread;
    }

    /** Throws unless called from the UI thread; invoked at every native boundary. */
    public void assertOnUIThread() {
        if (!isOnUIThread()) {
            throw new IllegalStateException(
                    "Operation marked @GUI invoked from '" + Thread.currentThread().getName()
                            + "' instead of the 'glyph-webkit-ui' thread.");
        }
    }

    /** Fire-and-forget scheduling; used for events arriving FROM native callbacks. */
    public void post(NativeTask task) {
        Objects.requireNonNull(task, "task");
        if (!running) {
            return; // dropping work after shutdown is intentional and silent
        }
        try {
            executor.execute(() -> {
                uiThread = Thread.currentThread();
                runChecked(task);
            });
        } catch (RejectedExecutionException racedShutdown) {
            // Lost a race with close(); treat exactly like the !running branch.
        }
    }

    /**
     * Schedules a task and blocks until it completed or failed. Backbone of the
     * synchronous lifecycle transitions (create/attach/destroy) so subsequent
     * property reads can never observe a half-initialized native object.
     */
    public void invokeAndWait(NativeTask task) {
        Objects.requireNonNull(task, "task");
        if (!running) {
            throw new GlyphWebException("The WebKit dispatcher has already shut down.");
        }
        if (isOnUIThread()) {
            runChecked(task); // re-entrant: run inline instead of self-deadlocking
            return;
        }
        CountDownLatch done = new CountDownLatch(1);
        Throwable[] failure = new Throwable[1];
        try {
            executor.execute(() -> {
                uiThread = Thread.currentThread();
                try {
                    task.run();
                } catch (Throwable t) {
                    failure[0] = t;
                } finally {
                    done.countDown();
                }
            });
        } catch (RejectedExecutionException racedShutdown) {
            throw new GlyphWebException("The WebKit dispatcher has already shut down.", racedShutdown);
        }
        try {
            done.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new GlyphWebException("Interrupted while awaiting the UI thread.", interrupted);
        }
        if (failure[0] != null) {
            rethrow(failure[0]);
        }
    }

    private void runChecked(NativeTask task) {
        try {
            task.run();
        } catch (RuntimeException | Error propagate) {
            throw propagate;
        } catch (Exception checked) {
            throw new GlyphWebException("Native task failed.", checked);
        }
    }

    private static void rethrow(Throwable t) {
        if (t instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (t instanceof Error error) {
            throw error;
        }
        throw new GlyphWebException("Native task failed.", t);
    }

    /** Stops accepting work, drains queued tasks, joins the worker. Idempotent. */
    @Override
    public void close() {
        if (!running) {
            return;
        }
        running = false;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
