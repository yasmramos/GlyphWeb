package io.glyph.web.core.lifecycle;

/**
 * Contract implemented by every object that owns (or borrows) a native pointer.
 *
 * <p>The lifecycle registry ({@link WebKitLifecycleManager}) only ever sees
 * this interface, which keeps the bookkeeping maps free of concrete-type
 * coupling and lets ASM-generated proxies participate without inheritance.
 */
public interface NativeHandle extends AutoCloseable {

    /** Process-unique id issued at construction; never reused after release. */
    long nativeId();

    /** Raw native address, or {@code 0} once released. Volatile-visible. */
    long nativeAddress();

    /**
     * Whether the garbage collector may reclaim the native side-channel when
     * the Java peer becomes unreachable. Owned resources return {@code true};
     * borrowed ones MUST return {@code false} to prevent double-free.
     */
    default boolean acceptsReclamation() {
        return true;
    }

    /**
     * Releases the underlying native resource. Implementations must be
     * idempotent: calling {@code close()} twice is a no-op, not an error.
     * Declared without checked exceptions for ergonomic try-with-resources in
     * Glyph UI templates.
     */
    @Override
    void close();
}
