package io.glyph.web.bench;

import io.glyph.web.core.event.GlyphEventPump;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * M3 end-to-end benchmark of the real emission path: {@link GlyphEventPump#fireLoadChanged}.
 *
 * <p>Unlike {@link SignalBusBenchmark} (which isolates the fan-out loop), this
 * harness exercises the FULL hot path including UTF-8 interning via
 * {@code NativeStringTable}, so it detects allocation regressions introduced by
 * string handling, not just slot iteration.
 *
 * <p>Run with GC profiling to assert the zero-allocation contract:
 * <pre>{@code
 *   java -jar benchmarks.jar EventPumpEmissionBenchmark -prof gc
 * }</pre>
 * Expect {@code gc.alloc.rate.norm} &lt; 64 B/op once the URI is already interned
 * (steady-state page loads reuse the same handful of URIs).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 4, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2)
public class EventPumpEmissionBenchmark {

    private static final byte[] URI_BYTES = "https://glyph.dev/docs".getBytes(StandardCharsets.UTF_8);

    private GlyphEventPump pumpNoListeners;
    private GlyphEventPump pumpThreeListeners;

    @Setup
    public void setup() {
        pumpNoListeners = new GlyphEventPump(0x1234L);
        pumpThreeListeners = new GlyphEventPump(0x5678L);
        for (int i = 0; i < 3; i++) {
            // Method reference target is a static field write — no capture, no boxing.
            pumpThreeListeners.addLoadListener(EventPumpEmissionBenchmark::consume);
        }
        // Pre-warm the intern table so steady state never re-interns.
        pumpThreeListeners.fireLoadChanged(2, URI_BYTES, URI_BYTES.length);
        pumpNoListeners.fireLoadChanged(2, URI_BYTES, URI_BYTES.length);
    }

    @TearDown
    public void tearDown() {
        pumpNoListeners.tearDown();
        pumpThreeListeners.tearDown();
    }

    private static long sink; // prevents DCE of the listener body

    private static void consume(long nativeId, int status, long uriToken) {
        sink = nativeId ^ uriToken ^ status;
    }

    /** Fast bail-out path: zero listeners attached (typical idle view). */
    @Benchmark
    public void fireWithNoListeners() {
        pumpNoListeners.fireLoadChanged(2, URI_BYTES, URI_BYTES.length);
    }

    /** Steady-state path: intern hit + 3 primitive-only invocations. */
    @Benchmark
    public void fireWithThreeListeners() {
        pumpThreeListeners.fireLoadChanged(2, URI_BYTES, URI_BYTES.length);
    }

    /** Worst case: every emission interns a brand-new URI (first paint / redirect storm). */
    @Benchmark
    public void fireWithFreshUri(org.openjdk.jmh.infra.Blackhole bh) {
        // Fresh array per op mirrors the JNI upcall reality: native side hands a
        // new byte[] window on each signal. This is the ONLY allocating path and
        // the -prof gc numbers quantify that cost for capacity planning.
        byte[] fresh = URI_BYTES.clone();
        fresh[fresh.length - 1] ^= 0x01; // toggle last bit -> distinct key each op
        pumpThreeListeners.fireLoadChanged(2, fresh, fresh.length);
        bh.consume(fresh);
    }
}
