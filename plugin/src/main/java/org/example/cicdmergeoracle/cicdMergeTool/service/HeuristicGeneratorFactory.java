package org.example.cicdmergeoracle.cicdMergeTool.service;

import ch.unibe.cs.mergeci.model.patterns.PatternHeuristics;
import ch.unibe.cs.mergeci.model.patterns.PatternStrategy;
import ch.unibe.cs.mergeci.model.patterns.StrategySelector;
import ch.unibe.cs.mergeci.present.VariantScore;
import ch.unibe.cs.mergeci.runner.IVariantGenerator;
import ch.unibe.cs.mergeci.runner.IVariantGeneratorFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Creates heuristic-based variant generators using the pipeline's
 * {@link StrategySelector} and {@link PatternHeuristics}.
 * <p>
 * The factory loads the global pattern distribution from the bundled CSV resource
 * and produces generators that sample assignments according to the learned weights.
 */
public class HeuristicGeneratorFactory implements IVariantGeneratorFactory {

    private final PatternHeuristics heuristics;

    public HeuristicGeneratorFactory() {
        try {
            this.heuristics = PatternHeuristics.loadFromResource(
                    "pattern-heuristics/learnt_historical_pattern_distribution.csv");
        } catch (IOException e) {
            throw new RuntimeException("Failed to load heuristics CSV from classpath", e);
        }
    }

    public HeuristicGeneratorFactory(Path heuristicsFile) {
        try {
            this.heuristics = PatternHeuristics.loadFromFile(heuristicsFile);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load heuristics CSV: " + heuristicsFile, e);
        }
    }

    public Map<String, Double> getGlobalWeightMap() {
        return VariantScore.buildGlobalWeightMap(heuristics);
    }

    @Override
    public IVariantGenerator create(String mergeId, Path repoPath, int numChunks) {
        StrategySelector selector = new StrategySelector(heuristics);
        return new HeuristicGenerator(selector, numChunks);
    }

    private static class HeuristicGenerator implements IVariantGenerator {
        private final StrategySelector selector;
        private final int numChunks;

        HeuristicGenerator(StrategySelector selector, int numChunks) {
            this.selector = selector;
            this.numChunks = numChunks;
        }

        @Override
        public Optional<List<String>> nextVariant() {
            while (!selector.allStrategiesExhausted(numChunks)) {
                PatternStrategy strategy = selector.selectStrategy(numChunks);
                if (strategy == null) break;
                List<String> assignment = selector.generateAssignment(strategy, numChunks);
                if (assignment == null) {
                    selector.recordOuterFailure();
                    continue;
                }
                return Optional.of(assignment);
            }
            return Optional.empty();
        }
    }
}
