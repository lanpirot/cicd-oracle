package ch.unibe.cs.mergeci.runner;

import ch.unibe.cs.mergeci.model.patterns.PatternHeuristics;
import ch.unibe.cs.mergeci.model.patterns.PatternStrategy;
import ch.unibe.cs.mergeci.model.patterns.StrategySelector;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Variant generator based on the learned historical pattern heuristic.
 * Delegates to {@link StrategySelector} which performs weighted-random selection
 * from {@code learnt_historical_pattern_distribution.csv}.
 */
public class HeuristicGenerator implements IVariantGenerator {

    private static final String HEURISTICS_RESOURCE =
            "pattern-heuristics/learnt_historical_pattern_distribution.csv";

    private final StrategySelector selector;
    private final int numChunks;

    public HeuristicGenerator(int numChunks) {
        this(numChunks, loadHeuristics());
    }

    HeuristicGenerator(int numChunks, PatternHeuristics heuristics) {
        this.numChunks = numChunks;
        this.selector = new StrategySelector(heuristics);
    }

    @Override
    public Optional<List<String>> nextVariant() {
        PatternStrategy strategy = selector.selectStrategy(numChunks);
        if (strategy == null) {
            return Optional.empty();
        }
        List<String> assignment = selector.generateAssignment(strategy, numChunks);
        return assignment != null ? Optional.of(assignment) : Optional.empty();
    }

    private static PatternHeuristics loadHeuristics() {
        try {
            return PatternHeuristics.loadFromResource(HEURISTICS_RESOURCE);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load pattern heuristics from " + HEURISTICS_RESOURCE, e);
        }
    }
}
