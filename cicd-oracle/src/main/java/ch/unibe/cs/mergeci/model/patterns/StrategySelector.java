package ch.unibe.cs.mergeci.model.patterns;

import java.util.*;

/**
 * Selects resolution strategies using weighted random selection based on heuristics.
 * Implements probabilistic strategy selection with deduplication and retry logic.
 */
public class StrategySelector {
    private final PatternHeuristics heuristics;
    private final Random random;
    private final Set<String> triedAssignments;
    private final Set<String> exhaustedStrategyKeys;
    private int consecutiveOuterFails;

    private static final int MAX_INNER_RETRIES = 10;
    private static final int MAX_OUTER_FAILS_THRESHOLD = 10;

    public StrategySelector(PatternHeuristics heuristics) {
        this(heuristics, new Random());
    }

    public StrategySelector(PatternHeuristics heuristics, Random random) {
        this.heuristics = heuristics;
        this.random = random;
        this.triedAssignments = new HashSet<>();
        this.exhaustedStrategyKeys = new HashSet<>();
        this.consecutiveOuterFails = 0;
    }

    /**
     * Select a strategy using weighted random selection, skipping exhausted ones.
     * Returns null if all strategies for this chunk count are exhausted.
     *
     * @param chunkCount Number of conflict chunks in the merge
     * @return Selected strategy, or null if all strategies are exhausted
     */
    public PatternStrategy selectStrategy(int chunkCount) {
        List<PatternStrategy> strategies = heuristics.getStrategies(chunkCount).stream()
                .filter(s -> !exhaustedStrategyKeys.contains(s.toString()))
                .toList();

        if (strategies.isEmpty()) {
            return null; // All strategies exhausted for this chunk count
        }

        // Weighted random selection using cumulative probabilities
        double totalWeight = strategies.stream()
                .mapToDouble(PatternStrategy::getWeight)
                .sum();

        double randomValue = random.nextDouble() * totalWeight;
        double cumulative = 0.0;

        for (PatternStrategy strategy : strategies) {
            cumulative += strategy.getWeight();
            if (randomValue < cumulative) {
                return strategy;
            }
        }

        // Fallback to last strategy (should rarely happen due to floating point precision)
        return strategies.getLast();
    }

    /**
     * Generate a chunk assignment for a strategy with deduplication.
     * Implements sampling without replacement with probability adjustment.
     *
     * @param strategy The selected strategy
     * @param numChunks Number of conflict chunks to assign
     * @return Assignment of patterns to chunks (one pattern per chunk)
     */
    public List<String> generateAssignment(PatternStrategy strategy, int numChunks) {
        // Try up to MAX_INNER_RETRIES times to generate a unique assignment
        for (int innerRetry = 0; innerRetry < MAX_INNER_RETRIES; innerRetry++) {
            List<String> assignment = sampleMultinomial(strategy, numChunks);
            String assignmentKey = assignmentToString(assignment);

            if (!triedAssignments.contains(assignmentKey)) {
                triedAssignments.add(assignmentKey);
                consecutiveOuterFails = 0;
                return assignment;
            }
        }

        // Inner retries exhausted - this strategy's assignment space is saturated.
        // Remove it from the pool so selectStrategy never picks it again.
        exhaustedStrategyKeys.add(strategy.toString());
        return null;
    }

    /**
     * Sample N meso-patterns independently (multinomial) from the macro-pattern's
     * sub-pattern percentages, then shuffle the resulting list.
     * <p>
     * Each chunk's meso-pattern is drawn independently — no probability adjustment
     * between draws. After all N draws the list is randomly permuted so that chunk
     * position is unrelated to draw order.
     * <p>
     * NON meso-patterns are replaced by sampling from the full global row (row 0).
     * Compound meso-patterns get a random internal component ordering via
     * {@link PatternFactory#sampleOrdering}.
     */
    private List<String> sampleMultinomial(PatternStrategy strategy, int numChunks) {
        List<PatternStrategy.SubPattern> subPatterns = strategy.getSubPatterns();
        List<String> assignment = new ArrayList<>();

        for (int i = 0; i < numChunks; i++) {
            String meso = sampleFromSubPatterns(subPatterns);
            if ("NON".equals(meso)) {
                assignment.add(PatternFactory.sampleOrdering(selectFromGlobalDistribution(), random));
            } else {
                assignment.add(PatternFactory.sampleOrdering(meso, random));
            }
        }

        Collections.shuffle(assignment, random);
        return assignment;
    }

    /**
     * Weighted random draw from a list of sub-patterns using their percentages.
     */
    private String sampleFromSubPatterns(List<PatternStrategy.SubPattern> subPatterns) {
        double total = subPatterns.stream().mapToDouble(PatternStrategy.SubPattern::getPercentage).sum();
        double r = random.nextDouble() * total;
        double cumulative = 0.0;
        for (PatternStrategy.SubPattern sp : subPatterns) {
            cumulative += sp.getPercentage();
            if (r < cumulative) return sp.getPattern();
        }
        return subPatterns.getLast().getPattern();
    }

    /**
     * Select a pattern from the global distribution (row 0).
     * Used as fallback when NON pattern is encountered.
     * Filters out NON patterns to avoid infinite recursion.
     *
     * @return Pattern name selected from global strategies (never NON)
     */
    private String selectFromGlobalDistribution() {
        List<PatternStrategy> globalStrategies = heuristics.getGlobalStrategies();

        if (globalStrategies.isEmpty()) {
            // Ultimate fallback if no global strategies exist
            return "OURS";
        }

        // Filter out strategies that are 100% NON
        List<PatternStrategy> nonNonStrategies = globalStrategies.stream()
                .filter(s -> {
                    if (s.getSubPatterns().isEmpty()) return false;
                    String pattern = s.getSubPatterns().getFirst().getPattern();
                    return !"NON".equals(pattern);
                })
                .toList();

        // If all global strategies are NON (shouldn't happen), use ultimate fallback
        if (nonNonStrategies.isEmpty()) {
            return "OURS";
        }

        // Use weighted random selection to pick a global strategy (excluding NON)
        PatternStrategy globalStrategy = selectStrategyFromList(nonNonStrategies);

        // Return the first (dominant) pattern from the selected global strategy
        // For global strategies, typically they are 100% single patterns
        if (!globalStrategy.getSubPatterns().isEmpty()) {
            String pattern = globalStrategy.getSubPatterns().getFirst().getPattern();
            // Double-check we didn't get NON (paranoid check)
            return "NON".equals(pattern) ? "OURS" : pattern;
        }

        return "OURS"; // Ultimate fallback
    }

    /**
     * Select a strategy from a list using weighted random selection.
     *
     * @param strategies List of strategies with weights
     * @return Selected strategy
     */
    private PatternStrategy selectStrategyFromList(List<PatternStrategy> strategies) {
        double totalWeight = strategies.stream()
                .mapToDouble(PatternStrategy::getWeight)
                .sum();

        double randomValue = random.nextDouble() * totalWeight;
        double cumulative = 0.0;

        for (PatternStrategy strategy : strategies) {
            cumulative += strategy.getWeight();
            if (randomValue < cumulative) {
                return strategy;
            }
        }

        // Fallback to last strategy
        return strategies.getLast();
    }

    /**
     * Convert assignment to string for deduplication.
     */
    private String assignmentToString(List<String> assignment) {
        return String.join(",", assignment);
    }

    /**
     * Reset the set of tried assignments and exhausted strategies (e.g., when starting a new merge).
     */
    public void reset() {
        triedAssignments.clear();
        exhaustedStrategyKeys.clear();
        consecutiveOuterFails = 0;
    }

    /**
     * Check if all strategies are exhausted for a given chunk count.
     * Callers can use this to stop the outer retry loop early.
     */
    public boolean allStrategiesExhausted(int chunkCount) {
        return heuristics.getStrategies(chunkCount).stream()
                .allMatch(s -> exhaustedStrategyKeys.contains(s.toString()));
    }

    /**
     * Record an outer failure (when inner retries are exhausted).
     * Reports if too many consecutive failures occur.
     */
    public void recordOuterFailure() {
        consecutiveOuterFails++;
        if (consecutiveOuterFails >= MAX_OUTER_FAILS_THRESHOLD) {
            System.err.println("⚠️ WARNING: " + consecutiveOuterFails + " consecutive outer strategy failures!");
            System.err.println("   Tried assignments: " + triedAssignments.size());
            System.err.println("   This may indicate insufficient strategy diversity or a bug.");
        }
    }

    /**
     * Get the number of unique assignments tried so far.
     */
    public int getTriedAssignmentsCount() {
        return triedAssignments.size();
    }
}
