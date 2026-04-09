package ch.unibe.cs.mergeci.plugin;

import ch.unibe.cs.mergeci.model.patterns.PatternFactory;
import ch.unibe.cs.mergeci.model.patterns.PatternHeuristics;
import ch.unibe.cs.mergeci.model.patterns.PatternStrategy;
import ch.unibe.cs.mergeci.model.patterns.StrategySelector;
import ch.unibe.cs.mergeci.runner.IVariantGenerator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests heuristic variant generation using the pipeline's StrategySelector
 * and PatternHeuristics — the same approach used by the plugin's
 * HeuristicGeneratorFactory.
 */
class HeuristicGeneratorTest {

    private static IVariantGenerator createGenerator(int numChunks) throws IOException {
        PatternHeuristics heuristics = PatternHeuristics.loadFromResource(
                "pattern-heuristics/learnt_historical_pattern_distribution.csv");
        StrategySelector selector = new StrategySelector(heuristics);
        return new IVariantGenerator() {
            @Override
            public Optional<List<String>> nextVariant() {
                PatternStrategy strategy = selector.selectStrategy(numChunks);
                if (strategy == null) return Optional.empty();
                List<String> assignment = selector.generateAssignment(strategy, numChunks);
                if (assignment == null) return Optional.empty();
                return Optional.of(assignment);
            }
        };
    }

    @Test
    void producesAssignmentsWithCorrectLength() throws IOException {
        var gen = createGenerator(4);
        List<String> assignment = gen.nextVariant().orElseThrow();
        assertEquals(4, assignment.size());
    }

    @Test
    void assignmentContainsValidPatternNames() throws IOException {
        var gen = createGenerator(3);
        List<String> assignment = gen.nextVariant().orElseThrow();
        for (String name : assignment) {
            assertDoesNotThrow(() -> PatternFactory.fromName(name),
                    "Pattern name '" + name + "' should be parseable by PatternFactory");
        }
    }

    @Test
    void singleChunkProducesValidAssignment() throws IOException {
        var gen = createGenerator(1);
        List<String> assignment = gen.nextVariant().orElseThrow();
        assertEquals(1, assignment.size());
        assertDoesNotThrow(() -> PatternFactory.fromName(assignment.get(0)));
    }

    @Test
    void generatorEventuallyExhausts() throws IOException {
        var gen = createGenerator(1);
        int count = 0;
        while (gen.nextVariant().isPresent()) {
            count++;
            if (count > 10_000) {
                fail("Generator did not exhaust after 10000 variants");
            }
        }
        assertTrue(count > 0, "Should have produced at least one variant before exhausting");
    }

    @Test
    void multipleCallsProduceDifferentAssignments() throws IOException {
        var gen = createGenerator(3);
        Set<List<String>> seen = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            Optional<List<String>> next = gen.nextVariant();
            if (next.isEmpty()) break;
            seen.add(next.get());
        }
        assertTrue(seen.size() > 1,
                "Expected diverse assignments but got only " + seen.size());
    }

    @Test
    void largeChunkCountStillProducesAssignments() throws IOException {
        var gen = createGenerator(20);
        List<String> assignment = gen.nextVariant().orElseThrow();
        assertEquals(20, assignment.size());
        for (String name : assignment) {
            assertDoesNotThrow(() -> PatternFactory.fromName(name));
        }
    }
}
