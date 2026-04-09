package ch.unibe.cs.mergeci.plugin;

import ch.unibe.cs.mergeci.present.VariantScore;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the chunk consensus algorithm: compute per-chunk pattern distributions
 * across variants that tie on the top two VariantScore keys (modules + tests).
 */
class ChunkConsensusTest {

    record ChunkKey(String filePath, int indexWithinFile) {}

    record VariantResult(int variantIndex, Map<String, List<String>> patternAssignment,
                         VariantScore score) {}

    /** Mirrors the plugin's ChunkConsensus.compute() logic. */
    static Map<ChunkKey, Map<String, Double>> compute(
            List<VariantResult> history, List<ChunkKey> chunkIndex) {
        List<VariantResult> scored = history.stream()
                .filter(v -> v.score != null).toList();
        if (scored.isEmpty()) return Collections.emptyMap();

        int bestModules = scored.stream().mapToInt(v -> v.score.successfulModules()).max().orElse(0);
        int bestTests = scored.stream()
                .filter(v -> v.score.successfulModules() == bestModules)
                .mapToInt(v -> v.score.passedTests()).max().orElse(0);

        List<VariantResult> tied = scored.stream()
                .filter(v -> v.score.successfulModules() == bestModules
                        && v.score.passedTests() == bestTests)
                .toList();
        if (tied.isEmpty()) return Collections.emptyMap();

        Map<ChunkKey, Map<String, Double>> result = new LinkedHashMap<>();
        for (int i = 0; i < chunkIndex.size(); i++) {
            ChunkKey key = chunkIndex.get(i);
            int idx = i;
            Map<String, Long> counts = tied.stream()
                    .map(v -> {
                        List<String> patterns = v.patternAssignment.get(key.filePath);
                        return (patterns != null && key.indexWithinFile < patterns.size())
                                ? patterns.get(key.indexWithinFile) : "UNKNOWN";
                    })
                    .collect(Collectors.groupingBy(p -> p, Collectors.counting()));

            double total = tied.size();
            Map<String, Double> pcts = new LinkedHashMap<>();
            counts.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .forEach(e -> pcts.put(e.getKey(),
                            Math.round(e.getValue() / total * 1000.0) / 10.0));
            result.put(key, pcts);
        }
        return result;
    }

    private static VariantResult makeResult(int modules, int tests, Map<String, List<String>> patterns) {
        return new VariantResult(1, patterns,
                new VariantScore(modules, tests, 0.0, Integer.MAX_VALUE));
    }

    @Test
    void filtersToTiedBestVariants() {
        List<ChunkKey> index = List.of(new ChunkKey("A.java", 0));

        var best1 = makeResult(3, 10, Map.of("A.java", List.of("OURS")));
        var best2 = makeResult(3, 10, Map.of("A.java", List.of("THEIRS")));
        var worse = makeResult(2, 15, Map.of("A.java", List.of("BASE")));

        var consensus = compute(List.of(best1, best2, worse), index);
        var pcts = consensus.get(index.get(0));

        assertEquals(50.0, pcts.get("OURS"));
        assertEquals(50.0, pcts.get("THEIRS"));
        assertNull(pcts.get("BASE"));
    }

    @Test
    void correctPercentagesForThreeVariants() {
        List<ChunkKey> index = List.of(new ChunkKey("F.java", 0));

        var v1 = makeResult(5, 20, Map.of("F.java", List.of("OURS")));
        var v2 = makeResult(5, 20, Map.of("F.java", List.of("OURS")));
        var v3 = makeResult(5, 20, Map.of("F.java", List.of("THEIRS")));

        var consensus = compute(List.of(v1, v2, v3), index);
        var pcts = consensus.get(index.get(0));

        assertEquals(66.7, pcts.get("OURS"), 0.1);
        assertEquals(33.3, pcts.get("THEIRS"), 0.1);
    }

    @Test
    void emptyHistoryReturnsEmpty() {
        assertTrue(compute(Collections.emptyList(),
                List.of(new ChunkKey("X.java", 0))).isEmpty());
    }

    @Test
    void noScoredVariantsReturnsEmpty() {
        var noScore = new VariantResult(1, Map.of(), null);
        assertTrue(compute(List.of(noScore),
                List.of(new ChunkKey("X.java", 0))).isEmpty());
    }

    @Test
    void singleTiedVariantGives100Percent() {
        List<ChunkKey> index = List.of(new ChunkKey("A.java", 0), new ChunkKey("A.java", 1));
        var only = makeResult(1, 5, Map.of("A.java", List.of("BASE", "EMPTY")));

        var consensus = compute(List.of(only), index);
        assertEquals(100.0, consensus.get(index.get(0)).get("BASE"));
        assertEquals(100.0, consensus.get(index.get(1)).get("EMPTY"));
    }

    @Test
    void multipleFilesMultipleChunks() {
        List<ChunkKey> index = List.of(
                new ChunkKey("A.java", 0),
                new ChunkKey("A.java", 1),
                new ChunkKey("B.java", 0));

        var v1 = makeResult(3, 10, Map.of("A.java", List.of("OURS", "THEIRS"), "B.java", List.of("BASE")));
        var v2 = makeResult(3, 10, Map.of("A.java", List.of("OURS", "OURS"),   "B.java", List.of("THEIRS")));
        var v3 = makeResult(3, 10, Map.of("A.java", List.of("THEIRS", "OURS"), "B.java", List.of("BASE")));

        var consensus = compute(List.of(v1, v2, v3), index);

        // A.java chunk 0: OURS=2/3, THEIRS=1/3
        assertEquals(66.7, consensus.get(index.get(0)).get("OURS"), 0.1);
        assertEquals(33.3, consensus.get(index.get(0)).get("THEIRS"), 0.1);

        // A.java chunk 1: OURS=2/3, THEIRS=1/3
        assertEquals(66.7, consensus.get(index.get(1)).get("OURS"), 0.1);
        assertEquals(33.3, consensus.get(index.get(1)).get("THEIRS"), 0.1);

        // B.java chunk 0: BASE=2/3, THEIRS=1/3
        assertEquals(66.7, consensus.get(index.get(2)).get("BASE"), 0.1);
        assertEquals(33.3, consensus.get(index.get(2)).get("THEIRS"), 0.1);
    }

    @Test
    void tiedOnModulesButDifferentTestsSelectsBestTests() {
        List<ChunkKey> index = List.of(new ChunkKey("A.java", 0));

        // All have 5 modules, but v1 has most tests
        var v1 = makeResult(5, 20, Map.of("A.java", List.of("OURS")));
        var v2 = makeResult(5, 20, Map.of("A.java", List.of("THEIRS")));
        var v3 = makeResult(5, 10, Map.of("A.java", List.of("BASE")));  // same modules, fewer tests

        var consensus = compute(List.of(v1, v2, v3), index);
        var pcts = consensus.get(index.get(0));

        // Only v1 and v2 tie at (5, 20); v3 has fewer tests so excluded
        assertEquals(50.0, pcts.get("OURS"));
        assertEquals(50.0, pcts.get("THEIRS"));
        assertNull(pcts.get("BASE"));
    }

    @Test
    void mixOfScoredAndUnscoredVariants() {
        List<ChunkKey> index = List.of(new ChunkKey("X.java", 0));

        var scored = makeResult(2, 5, Map.of("X.java", List.of("OURS")));
        var unscored = new VariantResult(2, Map.of("X.java", List.of("THEIRS")), null);

        var consensus = compute(List.of(scored, unscored), index);
        // Only the scored one counts
        assertEquals(100.0, consensus.get(index.get(0)).get("OURS"));
    }

    @Test
    void fourWaySplitProducesCorrectPercentages() {
        List<ChunkKey> index = List.of(new ChunkKey("A.java", 0));

        var v1 = makeResult(3, 5, Map.of("A.java", List.of("OURS")));
        var v2 = makeResult(3, 5, Map.of("A.java", List.of("THEIRS")));
        var v3 = makeResult(3, 5, Map.of("A.java", List.of("BASE")));
        var v4 = makeResult(3, 5, Map.of("A.java", List.of("EMPTY")));

        var consensus = compute(List.of(v1, v2, v3, v4), index);
        var pcts = consensus.get(index.get(0));

        assertEquals(25.0, pcts.get("OURS"));
        assertEquals(25.0, pcts.get("THEIRS"));
        assertEquals(25.0, pcts.get("BASE"));
        assertEquals(25.0, pcts.get("EMPTY"));
    }

    @Test
    void consensusOrderedByFrequencyDescending() {
        List<ChunkKey> index = List.of(new ChunkKey("A.java", 0));

        var v1 = makeResult(1, 1, Map.of("A.java", List.of("THEIRS")));
        var v2 = makeResult(1, 1, Map.of("A.java", List.of("OURS")));
        var v3 = makeResult(1, 1, Map.of("A.java", List.of("OURS")));
        var v4 = makeResult(1, 1, Map.of("A.java", List.of("OURS")));

        var consensus = compute(List.of(v1, v2, v3, v4), index);
        var pcts = consensus.get(index.get(0));

        // First key should be OURS (75%) since we sort descending
        var keys = List.copyOf(pcts.keySet());
        assertEquals("OURS", keys.get(0));
        assertEquals("THEIRS", keys.get(1));
    }
}
