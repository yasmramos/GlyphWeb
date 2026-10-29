package io.glyph.web.core.lifecycle;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Documents that the annotated method may only be invoked from the UI thread.
 *
 * <p><b>Why an annotation instead of runtime checks everywhere:</b> a runtime
 * {@code Thread.holdsLock}-style guard on every call would add a volatile read
 * plus a branch to hot paths (event dispatch, navigation). We keep enforcement
 * in exactly two places — {@link WebKitDispatcher#assertOnUIThread()} inside
 * native-boundary operations and static analysis via tooling that understands
 * this marker. Zero cost when the JIT inlines unchecked accessors.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.TYPE})
public @interface GUI {

    /** Human-readable rule shown in analyzer reports. */
    String value() default "Must be called from the WebKit UI thread.";
}
