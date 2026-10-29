package io.glyph.web.core.lifecycle;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies the single-writer guarantee of the dispatcher without any JNI. */
final class WebKitDispatcherTest {

    private WebKitDispatcher dispatcher = new WebKitDispatcher();

    @AfterEach
    void tearDown() {
        dispatcher.close();
        dispatcher.close(); // idempotency check
    }

    @Test
    void invokeAndWaitRunsOnWorkerThreadOnly() {
        AtomicReference<String> workerName = new AtomicReference<>();
        dispatcher.invokeAndWait(() -> workerName.set(Thread.currentThread().getName()));
        assertEquals("glyph-webkit-ui", workerName.get());
        assertFalse(dispatcher.isOnUIThread(), "test thread must NOT be considered UI");
    }

    @Test
    void reentrantInvokeAndWaitExecutesInline() {
        dispatcher.invokeAndWait(() -> {
            assertTrue(dispatcher.isOnUIThread());
            AtomicReference<Boolean> ranInline = new AtomicReference<>(false);
            dispatcher.invokeAndWait(() -> ranInline.set(true)); // would deadlock if queued
            assertTrue(ranInline.get());
        });
    }

    @Test
    void exceptionsPropagateAcrossTheBoundary() {
        GlyphWebException thrown = assertThrows(GlyphWebException.class,
                () -> dispatcher.invokeAndWait(() -> {
                    throw new GlyphWebException("native exploded");
                }));
        assertEquals("native exploded", thrown.getMessage());
    }

    @Test
    void assertOnUIThreadFailsFromCallerThread() {
        assertThrows(IllegalStateException.class, () -> dispatcher.assertOnUIThread());
    }
}
