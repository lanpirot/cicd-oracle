package org.example.cicdmergeoracle.cicdMergeTool.service;

import org.example.cicdmergeoracle.cicdMergeTool.model.ChunkKey;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Computes per-chunk pattern distributions across the tied-best variants
 * (those sharing the highest (modules, tests) pair).
 */
public class ChunkConsensus {

    /**
     * For each chunk, returns a map of pattern name to percentage (0-100)
     * across the subset of variants that tie on modules and tests.
     *
     * @param history    all completed variant results
     * @param chunkIndex ordered list of chunk keys matching the global chunk indexing
     * @return map from chunk key to {pattern -> percentage}; empty if no scored variants
     */
    public static Map<ChunkKey, Map<String, Double>> compute(
            List<VariantResult> history, List<ChunkKey> chunkIndex) {

        // Find the best (modules, tests) pair
        List<VariantResult> scored = history.stream()
                .filter(v -> v.score() != null)
                .toList();
        if (scored.isEmpty()) return Collections.emptyMap();

        int bestModules = scored.stream().mapToInt(v -> v.score().successfulModules()).max().orElse(0);
        int bestTests = scored.stream()
                .filter(v -> v.score().successfulModules() == bestModules)
                .mapToInt(v -> v.score().passedTests()).max().orElse(0);

        List<VariantResult> tied = scored.stream()
                .filter(v -> v.score().successfulModules() == bestModules
                        && v.score().passedTests() == bestTests)
                .toList();

        if (tied.isEmpty()) return Collections.emptyMap();

        Map<ChunkKey, Map<String, Double>> result = new LinkedHashMap<>();
        for (int i = 0; i < chunkIndex.size(); i++) {
            ChunkKey key = chunkIndex.get(i);
            int chunkIdx = i;

            // Count pattern occurrences for this chunk across tied variants
            Map<String, Long> counts = tied.stream()
                    .map(v -> getPatternAtGlobalIndex(v, chunkIdx, chunkIndex))
                    .collect(Collectors.groupingBy(p -> p, Collectors.counting()));

            double total = tied.size();
            Map<String, Double> percentages = new LinkedHashMap<>();
            counts.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .forEach(e -> percentages.put(e.getKey(),
                            Math.round(e.getValue() / total * 1000.0) / 10.0));
            result.put(key, percentages);
        }
        return result;
    }

    private static String getPatternAtGlobalIndex(VariantResult variant, int globalIdx,
                                                   List<ChunkKey> chunkIndex) {
        ChunkKey key = chunkIndex.get(globalIdx);
        List<String> patterns = variant.patternAssignment().get(key.filePath());
        if (patterns != null && key.indexWithinFile() < patterns.size()) {
            return patterns.get(key.indexWithinFile());
        }
        return "UNKNOWN";
    }
}
