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
        return new HeuristicGenerator(heuristics, numChunks);
    }

    /**
     * Heuristic-based generator with a resettable enumeration.
     * Calling {@link #restart()} replaces the underlying {@link StrategySelector}
     * so the generator re-emits assignments from the start — used when the user
     * pins a MANUAL chunk so previously-skipped pattern combinations get explored
     * against the new manual text. Dedup at submission time prevents already-done
     * variants from being re-run.
     */
    public static class HeuristicGenerator implements IVariantGenerator {
        private final PatternHeuristics heuristics;
        private final int numChunks;
        private StrategySelector selector;

        HeuristicGenerator(PatternHeuristics heuristics, int numChunks) {
            this.heuristics = heuristics;
            this.numChunks = numChunks;
            this.selector = new StrategySelector(heuristics);
        }

        public synchronized void restart() {
            this.selector = new StrategySelector(heuristics);
        }

        @Override
        public synchronized Optional<List<String>> nextVariant() {
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
