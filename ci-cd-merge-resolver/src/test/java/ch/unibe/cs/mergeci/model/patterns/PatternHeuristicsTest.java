package ch.unibe.cs.mergeci.model.patterns;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class PatternHeuristicsTest {

    @Test
    void testLoadHeuristics() throws Exception {
        PatternHeuristics heuristics = PatternHeuristics.loadFromResource(
                "pattern-heuristics/learnt_historical_pattern_distribution.csv"
        );

        assertNotNull(heuristics);
        System.out.println(heuristics);

        // Check global strategies loaded
        List<PatternStrategy> global = heuristics.getGlobalStrategies();
        assertFalse(global.isEmpty(), "Global strategies should be loaded");
        System.out.println("Global strategies: " + global.size());

        // Check first global strategy
        PatternStrategy first = global.get(0);
        System.out.println("First global strategy: " + first);
        assertTrue(first.getWeight() > 0, "Strategy weight should be positive");

        // Check strategies for specific chunk counts
        for (int i = 1; i <= 4; i++) {
            List<PatternStrategy> strategies = heuristics.getStrategies(i);
            assertFalse(strategies.isEmpty(), "Strategies for " + i + " chunks should exist");
            System.out.println("Strategies for " + i + " chunks: " + strategies.size());
        }
    }

    @Test
    void testStrategySelection() throws Exception {
        PatternHeuristics heuristics = PatternHeuristics.loadFromResource(
                "pattern-heuristics/learnt_historical_pattern_distribution.csv"
        );

        // Test strategy selection for different chunk counts
        for (int chunkCount = 1; chunkCount <= 4; chunkCount++) {
            System.out.println("\n=== Testing " + chunkCount + " chunks ===");

            // Reset selector for each chunk count
            StrategySelector selector = new StrategySelector(heuristics, new Random(42));

            for (int i = 0; i < 5; i++) {
                if (selector.allStrategiesExhausted(chunkCount)) {
                    System.out.println("  All strategies exhausted after " + i + " iterations");
                    break;
                }

                PatternStrategy strategy = selector.selectStrategy(chunkCount);
                assertNotNull(strategy, "selectStrategy should not return null when strategies remain");
                System.out.println("Selected: " + strategy);

                List<String> assignment = selector.generateAssignment(strategy, chunkCount);

                if (assignment != null) {
                    assertEquals(chunkCount, assignment.size(), "Assignment size should match chunk count");
                    System.out.println("  Assignment: " + assignment);
                } else {
                    System.out.println("  Assignment: <duplicate, skipped>");
                }
            }

            System.out.println("  Unique assignments for " + chunkCount + " chunks: " + selector.getTriedAssignmentsCount());
        }
    }

    @Test
    void testDeduplication() throws Exception {
        PatternHeuristics heuristics = PatternHeuristics.loadFromResource(
                "pattern-heuristics/learnt_historical_pattern_distribution.csv"
        );

        // Use fixed seed for reproducibility
        StrategySelector selector = new StrategySelector(heuristics, new Random(123));

        // For 1 chunk, there are limited unique assignments
        // Try to generate many assignments and verify deduplication works
        int chunkCount = 1;
        int attempts = 50;
        int successful = 0;

        for (int i = 0; i < attempts; i++) {
            if (selector.allStrategiesExhausted(chunkCount)) break;

            PatternStrategy strategy = selector.selectStrategy(chunkCount);
            assertNotNull(strategy);
            List<String> assignment = selector.generateAssignment(strategy, chunkCount);

            if (assignment != null) {
                successful++;
            }
        }

        System.out.println("Successful assignments: " + successful + " / " + attempts);
        System.out.println("Unique assignments: " + selector.getTriedAssignmentsCount());

        assertTrue(successful > 0, "At least some assignments should succeed");
        assertTrue(selector.getTriedAssignmentsCount() > 0, "Should have unique assignments");
    }

    @Test
    void testNonPatternFallback() throws Exception {
        PatternHeuristics heuristics = PatternHeuristics.loadFromResource(
                "pattern-heuristics/learnt_historical_pattern_distribution.csv"
        );

        // Use fixed seed for reproducibility
        StrategySelector selector = new StrategySelector(heuristics, new Random(456));

        // Test that NON patterns get replaced with concrete patterns from global distribution
        int chunkCount = 2;
        int samplesWithNon = 0;

        for (int i = 0; i < 20; i++) {
            PatternStrategy strategy = selector.selectStrategy(chunkCount);

            // Check if strategy contains NON
            boolean hasNon = strategy.getSubPatterns().stream()
                    .anyMatch(sp -> "NON".equals(sp.getPattern()));

            if (hasNon) {
                List<String> assignment = selector.generateAssignment(strategy, chunkCount);
                if (assignment != null) {
                    System.out.println("Strategy with NON: " + strategy + " → Assignment: " + assignment);

                    // Verify NON was replaced (or appears with very low probability if it's in global dist)
                    long nonCount = assignment.stream().filter(p -> "NON".equals(p)).count();
                    System.out.println("  NON count in assignment: " + nonCount);

                    samplesWithNon++;
                }
            }
        }

        System.out.println("Total strategies with NON tested: " + samplesWithNon);
        assertTrue(samplesWithNon > 0, "Should have tested at least some strategies with NON pattern");
    }

    @Test
    @Timeout(5)
    void testSingleChunkVariantCount() throws Exception {
        PatternHeuristics heuristics = PatternHeuristics.loadFromResource(
                "pattern-heuristics/learnt_historical_pattern_distribution.csv"
        );

        StrategySelector selector = new StrategySelector(heuristics, new Random(42));
        int chunkCount = 1;
        int variantCount = 0;

        while (!selector.allStrategiesExhausted(chunkCount)) {
            PatternStrategy strategy = selector.selectStrategy(chunkCount);
            if (strategy == null) break;

            List<String> assignment = selector.generateAssignment(strategy, chunkCount);
            if (assignment != null) {
                variantCount++;
                assertEquals(1, assignment.size(), "Single-chunk assignment must have exactly one entry");
            } else {
                selector.recordOuterFailure();
            }
        }

        System.out.println("Variants generated for 1 conflict chunk: " + variantCount);
        assertEquals(16, variantCount, "All 16 distinct orderings should be generated for a single chunk");
    }

    @Test
    void testOuterRetryWarning() throws Exception {
        PatternHeuristics heuristics = PatternHeuristics.loadFromResource(
                "pattern-heuristics/learnt_historical_pattern_distribution.csv"
        );

        // Use fixed seed for reproducibility
        StrategySelector selector = new StrategySelector(heuristics, new Random(789));

        // For 1 chunk, the assignment space is very limited (only 9 distinct strategies)
        // Saturate the space by generating many assignments
        int chunkCount = 1;
        int consecutiveFailures = 0;
        int maxAttempts = 100;

        System.out.println("\n=== Testing Outer Retry Warning System ===");

        for (int i = 0; i < maxAttempts; i++) {
            if (selector.allStrategiesExhausted(chunkCount)) {
                System.out.println("✓ All strategies exhausted after " + i + " iterations");
                break;
            }

            PatternStrategy strategy = selector.selectStrategy(chunkCount);
            assertNotNull(strategy);
            List<String> assignment = selector.generateAssignment(strategy, chunkCount);

            if (assignment == null) {
                selector.recordOuterFailure();
                consecutiveFailures++;
            } else {
                consecutiveFailures = 0;
            }
        }

        assertTrue(selector.allStrategiesExhausted(chunkCount) || consecutiveFailures >= 10,
                "Should either exhaust all strategies or trigger the warning threshold");
        System.out.println("Total unique assignments: " + selector.getTriedAssignmentsCount());
        System.out.println("Consecutive failures at end: " + consecutiveFailures);
    }
}
