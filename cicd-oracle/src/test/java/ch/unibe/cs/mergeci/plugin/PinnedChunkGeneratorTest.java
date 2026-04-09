package ch.unibe.cs.mergeci.plugin;

import ch.unibe.cs.mergeci.runner.IVariantGenerator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the pinned-chunk generator concept: wrap a delegate generator and
 * overwrite specific chunk positions with pinned pattern names.
 * This validates the logic used by the plugin's PinnedChunkGenerator.
 */
class PinnedChunkGeneratorTest {

    /** Minimal pinned-chunk wrapper for testing (mirrors plugin's PinnedChunkGenerator). */
    static class PinnedChunkGenerator implements IVariantGenerator {
        private final IVariantGenerator delegate;
        private final Map<Integer, String> pinnedChunks;

        PinnedChunkGenerator(IVariantGenerator delegate, Map<Integer, String> pinnedChunks) {
            this.delegate = delegate;
            this.pinnedChunks = Map.copyOf(pinnedChunks);
        }

        @Override
        public Optional<List<String>> nextVariant() {
            Optional<List<String>> next = delegate.nextVariant();
            if (next.isEmpty()) return Optional.empty();
            List<String> assignment = new ArrayList<>(next.get());
            for (Map.Entry<Integer, String> pin : pinnedChunks.entrySet()) {
                assignment.set(pin.getKey(), pin.getValue());
            }
            return Optional.of(assignment);
        }
    }

    @Test
    void pinnedPositionsAreOverwritten() {
        IVariantGenerator delegate = () -> Optional.of(List.of("OURS", "THEIRS", "BASE"));
        var gen = new PinnedChunkGenerator(delegate, Map.of(1, "EMPTY"));
        assertEquals(List.of("OURS", "EMPTY", "BASE"), gen.nextVariant().orElseThrow());
    }

    @Test
    void unpinnedPositionsPassThrough() {
        IVariantGenerator delegate = () -> Optional.of(List.of("OURS", "THEIRS", "BASE"));
        var gen = new PinnedChunkGenerator(delegate, Map.of(2, "EMPTY"));
        List<String> result = gen.nextVariant().orElseThrow();
        assertEquals("OURS", result.get(0));
        assertEquals("THEIRS", result.get(1));
        assertEquals("EMPTY", result.get(2));
    }

    @Test
    void returnsEmptyWhenDelegateExhausted() {
        IVariantGenerator delegate = () -> Optional.empty();
        var gen = new PinnedChunkGenerator(delegate, Map.of(0, "OURS"));
        assertTrue(gen.nextVariant().isEmpty());
    }

    @Test
    void zeroPinnedChunksIsPassthrough() {
        IVariantGenerator delegate = () -> Optional.of(List.of("OURS", "THEIRS"));
        var gen = new PinnedChunkGenerator(delegate, Map.of());
        assertEquals(List.of("OURS", "THEIRS"), gen.nextVariant().orElseThrow());
    }

    @Test
    void allChunksPinnedProducesConstantOutput() {
        AtomicInteger callCount = new AtomicInteger(0);
        IVariantGenerator delegate = () -> {
            callCount.incrementAndGet();
            return Optional.of(List.of("OURS", "THEIRS", "BASE"));
        };
        var gen = new PinnedChunkGenerator(delegate,
                Map.of(0, "EMPTY", 1, "EMPTY", 2, "EMPTY"));

        List<String> r1 = gen.nextVariant().orElseThrow();
        List<String> r2 = gen.nextVariant().orElseThrow();
        assertEquals(List.of("EMPTY", "EMPTY", "EMPTY"), r1);
        assertEquals(r1, r2);
        assertEquals(2, callCount.get());
    }

    @Test
    void delegateOutputVariesButPinsStayFixed() {
        AtomicInteger counter = new AtomicInteger(0);
        IVariantGenerator delegate = () -> {
            int c = counter.getAndIncrement();
            return Optional.of(List.of(c % 2 == 0 ? "OURS" : "THEIRS", "BASE", "EMPTY"));
        };
        var gen = new PinnedChunkGenerator(delegate, Map.of(0, "BASE"));

        // Pin at index 0 stays BASE regardless of delegate
        assertEquals("BASE", gen.nextVariant().orElseThrow().get(0));
        assertEquals("BASE", gen.nextVariant().orElseThrow().get(0));
        // Unpinned positions still vary
        // Call 0 (even): delegate[1]=BASE, delegate[2]=EMPTY
        // Call 1 (odd):  delegate[1]=BASE, delegate[2]=EMPTY
        // (In this case they're the same, but the point is pin index 0 is overwritten)
    }

    @Test
    void multiplePinsOnNonAdjacentPositions() {
        IVariantGenerator delegate = () ->
                Optional.of(List.of("A", "B", "C", "D", "E"));
        var gen = new PinnedChunkGenerator(delegate, Map.of(0, "X", 2, "Y", 4, "Z"));

        List<String> result = gen.nextVariant().orElseThrow();
        assertEquals(List.of("X", "B", "Y", "D", "Z"), result);
    }

    @Test
    void delegateExhaustsAfterSomeCalls() {
        AtomicInteger remaining = new AtomicInteger(3);
        IVariantGenerator delegate = () -> {
            if (remaining.decrementAndGet() < 0) return Optional.empty();
            return Optional.of(List.of("OURS", "THEIRS"));
        };
        var gen = new PinnedChunkGenerator(delegate, Map.of(1, "BASE"));

        assertTrue(gen.nextVariant().isPresent());
        assertTrue(gen.nextVariant().isPresent());
        assertTrue(gen.nextVariant().isPresent());
        assertTrue(gen.nextVariant().isEmpty());
    }

    @Test
    void doesNotMutateDelegateAssignment() {
        List<String> original = new ArrayList<>(List.of("OURS", "THEIRS"));
        IVariantGenerator delegate = () -> Optional.of(original);
        var gen = new PinnedChunkGenerator(delegate, Map.of(0, "EMPTY"));

        gen.nextVariant();
        // Original list from delegate should be unchanged (we copy before modifying)
        assertEquals("OURS", original.get(0));
    }
}
