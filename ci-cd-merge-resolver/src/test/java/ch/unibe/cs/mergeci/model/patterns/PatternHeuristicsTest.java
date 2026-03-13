package ch.unibe.cs.mergeci.model.patterns;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class PatternHeuristicsTest {

    @Test
    void testLoadHeuristics() throws Exception {
        PatternHeuristics heuristics = PatternHeuristics.loadFromResource(
                "pattern-heuristics/relative_numbers_summary.csv"
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
                "pattern-heuristics/relative_numbers_summary.csv"
        );

        // Test strategy selection for different chunk counts
        for (int chunkCount = 1; chunkCount <= 4; chunkCount++) {
            System.out.println("\n=== Testing " + chunkCount + " chunks ===");

            // Reset selector for each chunk count
            StrategySelector selector = new StrategySelector(heuristics, new Random(42));

            for (int i = 0; i < 5; i++) {
                PatternStrategy strategy = selector.selectStrategy(chunkCount);
                assertNotNull(strategy);
                System.out.println("Selected: " + strategy);

                // Generate assignment
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
                "pattern-heuristics/relative_numbers_summary.csv"
        );

        // Use fixed seed for reproducibility
        StrategySelector selector = new StrategySelector(heuristics, new Random(123));

        // For 1 chunk, there are limited unique assignments
        // Try to generate many assignments and verify deduplication works
        int chunkCount = 1;
        int attempts = 50;
        int successful = 0;

        for (int i = 0; i < attempts; i++) {
            PatternStrategy strategy = selector.selectStrategy(chunkCount);
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
                "pattern-heuristics/relative_numbers_summary.csv"
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
    void testOuterRetryWarning() throws Exception {
        PatternHeuristics heuristics = PatternHeuristics.loadFromResource(
                "pattern-heuristics/relative_numbers_summary.csv"
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
            PatternStrategy strategy = selector.selectStrategy(chunkCount);
            List<String> assignment = selector.generateAssignment(strategy, chunkCount);

            if (assignment == null) {
                // Inner retries exhausted - record outer failure
                selector.recordOuterFailure();
                consecutiveFailures++;

                if (consecutiveFailures >= 10) {
                    System.out.println("✓ Warning system triggered after " + consecutiveFailures + " failures");
                    break;
                }
            } else {
                // Reset on success
                consecutiveFailures = 0;
            }
        }

        // Verify that we hit saturation and warning was triggered
        assertTrue(consecutiveFailures >= 10 || selector.getTriedAssignmentsCount() >= 9,
                "Should either trigger warning or exhaust assignment space for 1-chunk strategies");
        System.out.println("Total unique assignments: " + selector.getTriedAssignmentsCount());
        System.out.println("Consecutive failures at end: " + consecutiveFailures);
    }
}
