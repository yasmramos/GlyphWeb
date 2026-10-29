package io.glyph.web.core.lifecycle;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-JVM tests for the lifecycle bookkeeping layer: no native library, no
 * display server. These lock down the registry contract that GlyphWebView and
 * future ASM-generated proxies rely on.
 */
final class WebKitLifecycleManagerTest {

    /** Minimal in-memory handle to exercise the registry without JNI. */
    private static final class FakeHandle implements NativeHandle {
        private final long id = NativeId.next();
        private final AtomicInteger closeCalls = new AtomicInteger();
        private volatile long address = 0xDEADBEEFL;

        @Override
        public long nativeId() {
            return id;
        }

        @Override
        public long nativeAddress() {
            return address;
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            address = 0L;
        }
    }

    @Test
    void registerAndProbeLiveness() {
        WebKitLifecycleManager manager = new WebKitLifecycleManager();
        FakeHandle handle = new FakeHandle();

        assertTrue(manager.isAlive(handle.nativeId()) == false); // not yet registered
        manager.register(handle);
        assertTrue(manager.isAlive(handle.nativeId()));
        assertEquals(1, manager.liveCount());
    }

    @Test
    void duplicateRegistrationFails() {
        WebKitLifecycleManager manager = new WebKitLifecycleManager();
        FakeHandle handle = new FakeHandle();
        manager.register(handle);
        assertThrows(GlyphWebException.class, () -> manager.register(handle));
    }

    @Test
    void releaseUnregistersAndDropsLiveness() {
        WebKitLifecycleManager manager = new WebKitLifecycleManager();
        FakeHandle handle = new FakeHandle();
        manager.register(handle);

        manager.markReleased(handle.nativeId());
        assertFalse(manager.isAlive(handle.nativeId()));
        manager.unregister(handle.nativeId());
        assertEquals(0, manager.liveCount());
    }

    @Test
    void shutdownReleasesEverythingInReverseOrder() {
        WebKitLifecycleManager manager = new WebKitLifecycleManager();
        FakeHandle first = new FakeHandle();
        FakeHandle second = new FakeHandle();
        manager.register(first);
        manager.register(second);

        manager.shutdownAll();
        manager.shutdownAll(); // idempotent

        assertEquals(1, first.closeCalls.get());
        assertEquals(1, second.closeCalls.get());
        assertEquals(0, manager.liveCount());
        assertTrue(manager.isShutdown());
        assertThrows(GlyphWebException.class, () -> manager.register(new FakeHandle()));
    }

    @Test
    void concurrentRegisterUnregisterKeepsConsistency() throws InterruptedException {
        WebKitLifecycleManager manager = new WebKitLifecycleManager();
        int threads = 8;
        int perThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    FakeHandle handle = new FakeHandle();
                    manager.register(handle);
                    manager.markReleased(handle.nativeId());
                    manager.unregister(handle.nativeId());
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(0, manager.liveCount(), "registry must drain completely under churn");
    }

    @Test
    void stateMachineRejectsIllegalTransitions() {
        assertTrue(WebViewState.NEW.canTransitionTo(WebViewState.CREATED));
        assertFalse(WebViewState.NEW.canTransitionTo(WebViewState.VISIBLE));
        assertFalse(WebViewState.DESTROYED.canTransitionTo(WebViewState.CREATED));
        assertTrue(WebViewState.CREATED.isNativeAlive());
        assertTrue(WebViewState.VISIBLE.isNativeAlive());
        assertFalse(WebViewState.DESTROYING.isNativeAlive());
    }

    @Test
    void nativeIdsAreNeverReused() {
        long a = NativeId.next();
        long b = NativeId.next();
        assertTrue(b > a);
    }
}
