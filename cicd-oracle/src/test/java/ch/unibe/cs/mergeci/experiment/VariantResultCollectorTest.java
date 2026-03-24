package ch.unibe.cs.mergeci.experiment;

import ch.unibe.cs.mergeci.BaseTest;
import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.runner.ExperimentTiming;
import ch.unibe.cs.mergeci.runner.VariantProjectBuilder;
import ch.unibe.cs.mergeci.runner.maven.CompilationResult;
import ch.unibe.cs.mergeci.runner.maven.TestTotal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests VariantResultCollector: verifies that variant JSONs contain no
 * human-baseline data and that human_baseline JSONs are built correctly.
 */
class VariantResultCollectorTest extends BaseTest {

    private static final String PROJECT_NAME = "test-project";
    private static final long BASELINE_SECONDS = 70L;
    private static final double VARIANT_FINISH_SECONDS = 45.0;

    /**
     * Build a minimal ProcessedMerge with one variant (no actual files needed).
     */
    private MergeExperimentRunner.ProcessedMerge buildProcessedMerge(
            boolean includeVariant,
            Map<String, Double> variantFinishSeconds) throws Exception {

        // MergeInfo via setters
        DatasetReader.MergeInfo info = new DatasetReader.MergeInfo();
        info.setMergeCommit("abc123");
        info.setParent1("p1");
        info.setParent2("p2");
        info.setNumConflictFiles(1);
        info.setNumJavaFiles(1);

        // TestTotal for baseline
        TestTotal baselineTests = new TestTotal();
        baselineTests.setRunNum(100);
        baselineTests.setFailuresNum(5);
        baselineTests.setErrorsNum(0);
        baselineTests.setElapsedTime(12.5f);
        baselineTests.setHasData(true);

        // compilationResults: always has baseline; optionally one variant
        Map<String, CompilationResult> compilationResults = new TreeMap<>();
        compilationResults.put(PROJECT_NAME, null); // null is acceptable for baseline CR in tests

        Map<String, TestTotal> testResults = new TreeMap<>();
        testResults.put(PROJECT_NAME, baselineTests);

        if (includeVariant) {
            compilationResults.put(PROJECT_NAME + "_0", null);
            TestTotal variantTests = new TestTotal();
            variantTests.setRunNum(90);
            variantTests.setFailuresNum(2);
            variantTests.setHasData(true);
            testResults.put(PROJECT_NAME + "_0", variantTests);
        }

        // ExperimentTiming with known baseline duration
        ExperimentTiming timing = new ExperimentTiming();
        timing.setHumanBaselineExecutionTime(Duration.ofSeconds(BASELINE_SECONDS));
        timing.setVariantsExecutionTime(Duration.ofSeconds(120));

        // VariantProjectBuilder — constructor only stores paths; conflictPatterns is set to empty list
        Path fakePath = Paths.get("/tmp", PROJECT_NAME);
        VariantProjectBuilder analyzer = new VariantProjectBuilder(fakePath, AppConfig.TEST_TMP_DIR, AppConfig.TEST_TMP_DIR);
        if (includeVariant) {
            // Supply one entry so buildVariants can call getConflictPatterns().get(0)
            analyzer.setConflictPatterns(List.of(Map.of("Foo.java", List.of("OursPattern"))));
        }

        MergeExperimentRunner.MergeAnalysisResult result = new MergeExperimentRunner.MergeAnalysisResult(
                analyzer, compilationResults, testResults, 200L, timing, null, variantFinishSeconds,
                null, false, null, 0
        );

        return MergeExperimentRunner.ProcessedMerge.completed(info, 2, result);
    }

    // ── collectResults (variant modes) ────────────────────────────────────────

    @Test
    void collectResults_hasNoTopLevelBaselineFields() throws Exception {
        MergeExperimentRunner.ProcessedMerge processed = buildProcessedMerge(false, Map.of());
        MergeOutputJSON output = new VariantResultCollector().collectResults(processed);

        // budgetBasisSeconds populated
        assertEquals(BASELINE_SECONDS, output.getBudgetBasisSeconds());

        // variants list is empty (no variants, only baseline was in compilationResults)
        assertNotNull(output.getVariants());
        assertTrue(output.getVariants().isEmpty());

        // backward-compat getter returns null (no index-0 variant in variant-mode JSON)
        assertNull(output.getCompilationResult(),
                "variant-mode JSON must not expose a compilationResult via baseline lookup");
        assertNull(output.getTestResults(),
                "getTestResults() must return null when no baseline variant present");
    }

    @Test
    void collectResults_variantHasFinishTime() throws Exception {
        Map<String, Double> finishTimes = Map.of(PROJECT_NAME + "_0", VARIANT_FINISH_SECONDS);
        MergeExperimentRunner.ProcessedMerge processed = buildProcessedMerge(true, finishTimes);
        MergeOutputJSON output = new VariantResultCollector().collectResults(processed);

        List<MergeOutputJSON.Variant> variants = output.getVariants();
        assertEquals(1, variants.size());

        MergeOutputJSON.Variant variant = variants.get(0);
        assertNotNull(variant.getOwnExecutionSeconds());
        assertEquals(VARIANT_FINISH_SECONDS, variant.getOwnExecutionSeconds(), 0.001);
    }

    @Test
    void collectResults_serialisedJsonHasNoTopLevelCompilationOrTestResults() throws Exception {
        MergeExperimentRunner.ProcessedMerge processed = buildProcessedMerge(false, Map.of());
        MergeOutputJSON output = new VariantResultCollector().collectResults(processed);

        String json = new ObjectMapper().writeValueAsString(output);
        assertFalse(json.contains("\"compilationResult\""),
                "variant-mode JSON must not serialise top-level compilationResult");
        // testResults is not a real field anymore — only the @JsonIgnore method exists
        assertTrue(json.contains("\"budgetBasisSeconds\""),
                "variant-mode JSON must contain budgetBasisSeconds");
    }

    // ── collectBaselineResult (human_baseline mode) ───────────────────────────

    @Test
    void collectBaselineResult_hasSingleBaselineVariant() throws Exception {
        MergeExperimentRunner.ProcessedMerge processed = buildProcessedMerge(false, Map.of());
        MergeOutputJSON output = new VariantResultCollector().collectBaselineResult(processed);

        assertEquals(BASELINE_SECONDS, output.getBudgetBasisSeconds());

        List<MergeOutputJSON.Variant> variants = output.getVariants();
        assertEquals(1, variants.size());

        MergeOutputJSON.Variant variant = variants.get(0);
        assertEquals(0, variant.getVariantIndex());
        assertEquals(BASELINE_SECONDS, variant.getOwnExecutionSeconds(), 0.001);
    }

    @Test
    void collectBaselineResult_backwardCompatGettersWork() throws Exception {
        MergeExperimentRunner.ProcessedMerge processed = buildProcessedMerge(false, Map.of());
        MergeOutputJSON output = new VariantResultCollector().collectBaselineResult(processed);

        // getTestResults() finds the index-0 variant and returns its TestTotal
        TestTotal tests = output.getTestResults();
        assertNotNull(tests);
        assertEquals(100, tests.getRunNum());
        assertEquals(5, tests.getFailuresNum());
    }

    @Test
    void collectBaselineResult_executionTimeMatchesBaseline() throws Exception {
        MergeExperimentRunner.ProcessedMerge processed = buildProcessedMerge(false, Map.of());
        MergeOutputJSON output = new VariantResultCollector().collectBaselineResult(processed);

        assertEquals(BASELINE_SECONDS, output.getVariantsExecutionTimeSeconds());
    }
}
