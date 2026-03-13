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
        this.consecutiveOuterFails = 0;
    }

    /**
     * Select a strategy using weighted random selection.
     * Strategies are selected based on their success rate weights.
     *
     * @param chunkCount Number of conflict chunks in the merge
     * @return Selected strategy
     */
    public PatternStrategy selectStrategy(int chunkCount) {
        List<PatternStrategy> strategies = heuristics.getStrategies(chunkCount);

        if (strategies.isEmpty()) {
            throw new IllegalStateException("No strategies available for chunk count: " + chunkCount);
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
        return strategies.get(strategies.size() - 1);
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
            List<String> assignment = sampleWithoutReplacement(strategy, numChunks);
            String assignmentKey = assignmentToString(assignment);

            if (!triedAssignments.contains(assignmentKey)) {
                triedAssignments.add(assignmentKey);
                consecutiveOuterFails = 0;
                return assignment;
            }
        }

        // Inner retries exhausted - this assignment space is saturated
        return null;
    }

    /**
     * Sample patterns for chunks using weighted random selection without replacement.
     * Implements true sequential sampling with dynamic probability adjustment.
     * After each selection, the selected pattern's probability is reduced and
     * redistributed proportionally to remaining patterns.
     *
     * Special handling for NON pattern: falls back to global distribution.
     *
     * @param strategy Strategy containing sub-patterns with percentages
     * @param numChunks Number of chunks to assign
     * @return List of pattern names (one per chunk)
     */
    private List<String> sampleWithoutReplacement(PatternStrategy strategy, int numChunks) {
        List<PatternStrategy.SubPattern> subPatterns = strategy.getSubPatterns();

        // If only one pattern, check for NON fallback or just repeat it
        if (subPatterns.size() == 1) {
            List<String> assignment = new ArrayList<>();
            String pattern = subPatterns.get(0).getPattern();

            // NON pattern: fall back to global distribution
            if ("NON".equals(pattern)) {
                for (int i = 0; i < numChunks; i++) {
                    assignment.add(selectFromGlobalDistribution());
                }
            } else {
                for (int i = 0; i < numChunks; i++) {
                    assignment.add(pattern);
                }
            }
            return assignment;
        }

        // Create mutable probability distribution
        Map<String, Double> probabilities = new HashMap<>();
        for (PatternStrategy.SubPattern sp : subPatterns) {
            probabilities.put(sp.getPattern(), sp.getPercentage());
        }

        List<String> assignment = new ArrayList<>();

        // Sequential sampling with probability adjustment
        for (int i = 0; i < numChunks; i++) {
            // Select pattern using weighted random selection
            String selectedPattern = selectPatternWeighted(probabilities);

            // NON pattern: fall back to global distribution for this sub-pattern
            if ("NON".equals(selectedPattern)) {
                assignment.add(selectFromGlobalDistribution());
            } else {
                assignment.add(selectedPattern);
            }

            // Adjust probabilities for next selection (sampling without replacement)
            if (i < numChunks - 1) { // Don't adjust after last selection
                adjustProbabilities(probabilities, selectedPattern, numChunks - i);
            }
        }

        return assignment;
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
                    String pattern = s.getSubPatterns().get(0).getPattern();
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
            String pattern = globalStrategy.getSubPatterns().get(0).getPattern();
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
        return strategies.get(strategies.size() - 1);
    }

    /**
     * Select a pattern using weighted random selection from current probability distribution.
     *
     * @param probabilities Current probability distribution (percentages that sum to 100)
     * @return Selected pattern name
     */
    private String selectPatternWeighted(Map<String, Double> probabilities) {
        double totalWeight = probabilities.values().stream().mapToDouble(Double::doubleValue).sum();
        double randomValue = random.nextDouble() * totalWeight;
        double cumulative = 0.0;

        for (Map.Entry<String, Double> entry : probabilities.entrySet()) {
            cumulative += entry.getValue();
            if (randomValue < cumulative) {
                return entry.getKey();
            }
        }

        // Fallback to last pattern (should rarely happen due to floating point precision)
        return probabilities.keySet().iterator().next();
    }

    /**
     * Adjust probabilities after selecting a pattern.
     * The selected pattern's share decreases, and the difference is redistributed
     * proportionally to other patterns.
     *
     * @param probabilities Mutable probability map
     * @param selectedPattern Pattern that was just selected
     * @param remainingChunks Number of chunks still to be assigned (including current)
     */
    private void adjustProbabilities(Map<String, Double> probabilities, String selectedPattern, int remainingChunks) {
        double selectedProb = probabilities.get(selectedPattern);

        // Calculate how much to reduce from selected pattern
        // After selecting once, this pattern should appear in (original% - 1/numChunks) of remaining slots
        double reductionAmount = 100.0 / remainingChunks;
        double newSelectedProb = Math.max(0, selectedProb - reductionAmount);

        // Calculate redistribution amount
        double redistribution = selectedProb - newSelectedProb;

        // Get total weight of other patterns for proportional redistribution
        double otherPatternsTotal = 0;
        for (Map.Entry<String, Double> entry : probabilities.entrySet()) {
            if (!entry.getKey().equals(selectedPattern)) {
                otherPatternsTotal += entry.getValue();
            }
        }

        // Redistribute proportionally to other patterns
        if (otherPatternsTotal > 0) {
            for (Map.Entry<String, Double> entry : probabilities.entrySet()) {
                String pattern = entry.getKey();
                if (pattern.equals(selectedPattern)) {
                    probabilities.put(pattern, newSelectedProb);
                } else {
                    double currentProb = entry.getValue();
                    double share = currentProb / otherPatternsTotal;
                    probabilities.put(pattern, currentProb + redistribution * share);
                }
            }
        } else {
            // Only one pattern remains - set it to 100%
            probabilities.put(selectedPattern, 100.0);
        }
    }

    /**
     * Convert assignment to string for deduplication.
     */
    private String assignmentToString(List<String> assignment) {
        return String.join(",", assignment);
    }

    /**
     * Reset the set of tried assignments (e.g., when starting a new merge).
     */
    public void reset() {
        triedAssignments.clear();
        consecutiveOuterFails = 0;
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
