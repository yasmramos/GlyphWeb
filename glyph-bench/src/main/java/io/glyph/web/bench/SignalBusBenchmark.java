package io.glyph.web.bench;

import io.glyph.web.core.event.SignalBus;
import io.glyph.web.core.event.WebLoadListener;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * M3 benchmark: validates the zero-allocation claim of {@link SignalBus} fan-out.
 *
 * <p>Run with the GC profiler to verify allocation neutrality:
 * <pre>{@code
 *   java -jar benchmarks.jar SignalBusBenchmark -prof gc
 * }</pre>
 * Expected result: gc.alloc.rate.norm near 0 B/op for steady-state emission
 * (only the fixed listener objects created at setup allocate).
 *
 * <p>The emission loop below mirrors GlyphEventPump's dispatch: snapshot the
 * volatile mask once, then iterate set bits with trailing-zero counting. No
 * iterator object, no bounds copy, no boxing.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 4, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-XX:+UseG1GC"})
public class SignalBusBenchmark {

    /** Stateless listener: field writes only, so dispatch never allocates. */
    private static final class NoopLoadListener implements WebLoadListener {
        @Override
        public void onLoadChanged(long nativeId, int status, long uriToken) {
            // Intentionally empty: the Blackhole at the call site keeps it live.
        }
    }

    private SignalBus<WebLoadListener> busEmpty;
    private SignalBus<WebLoadListener> busOne;
    private SignalBus<WebLoadListener> busEight;
    private SignalBus<WebLoadListener> busSixteen;

    @Setup
    public void setup() {
        busEmpty = new SignalBus<>();
        busOne = registerAll(new SignalBus<>(), 1);
        busEight = registerAll(new SignalBus<>(), 8);
        busSixteen = registerAll(new SignalBus<>(), 16);
    }

    private static SignalBus<WebLoadListener> registerAll(SignalBus<WebLoadListener> b, int n) {
        NoopLoadListener l = new NoopLoadListener();
        for (int i = 0; i < n; i++) {
            if (b.register(l) == null) {
                throw new IllegalStateException("bus saturated during setup");
            }
        }
        return b;
    }

    /**
     * Core hot-path shape used by every emission in GlyphEventPump.
     * Blackhole prevents dead-code elimination of the listener calls.
     */
    private void emitOnce(SignalBus<WebLoadListener> bus, Blackhole bh) {
        long mask = bus.activeMaskSnapshot();
        while (mask != 0L) {
            int slot = Long.numberOfTrailingZeros(mask);
            WebLoadListener l = bus.listenerAt(slot);
            l.onLoadChanged(0x7FFF_0000_1234L, slot, 7L);
            bh.consume(l);
            mask &= mask - 1; // clear lowest set bit — branch-free advance
        }
    }

    @Benchmark
    public void emit0Listeners(Blackhole bh) {
        emitOnce(busEmpty, bh);
    }

    @Benchmark
    public void emit1Listener(Blackhole bh) {
        emitOnce(busOne, bh);
    }

    @Benchmark
    public void emit8Listeners(Blackhole bh) {
        emitOnce(busEight, bh);
    }

    @Benchmark
    public void emit16Listeners(Blackhole bh) {
        emitOnce(busSixteen, bh);
    }

    /** Baseline cost of the snapshot read alone (one volatile load). */
    @Benchmark
    public long maskSnapshotOnly() {
        return busSixteen.activeMaskSnapshot();
    }
}
