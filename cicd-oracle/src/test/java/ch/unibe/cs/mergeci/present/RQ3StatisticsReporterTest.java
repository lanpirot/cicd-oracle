package ch.unibe.cs.mergeci.present;

import ch.unibe.cs.mergeci.BaseTest;
import ch.unibe.cs.mergeci.conflict.MergeStatistics;
import ch.unibe.cs.mergeci.experiment.MergeOutputJSON;
import ch.unibe.cs.mergeci.runner.maven.CompilationResult;
import ch.unibe.cs.mergeci.runner.maven.TestTotal;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for RQ3 fine-grained quality metrics in StatisticsReporter.
 */
class RQ3StatisticsReporterTest extends BaseTest {

    @Test
    void betterBuild_variantCompilesMoreModules() {
        // Baseline: FAILURE, 0 modules. Variant: SUCCESS, 1 module.
        MergeOutputJSON merge = createBrokenBaselineMerge("m1");
        addSuccessVariant(merge, 1, 10, 0, 0);

        Optional<VariantScore> base = VariantResolutionAnalyzer.baselineScore(merge);
        Optional<VariantScore> best = VariantResolutionAnalyzer.bestVariantScore(merge);

        assertTrue(base.isPresent());
        assertTrue(best.isPresent());
        assertTrue(best.get().successfulModules() > base.get().successfulModules());
    }

    @Test
    void betterTests_variantPassesMoreTests() {
        // Baseline: 10 tests, 5 failures. Variant: 10 tests, 0 failures.
        MergeOutputJSON merge = createSuccessBaselineMerge("m1", 10, 5, 0);
        addSuccessVariant(merge, 1, 10, 0, 0);

        Optional<VariantScore> base = VariantResolutionAnalyzer.baselineScore(merge);
        Optional<VariantScore> best = VariantResolutionAnalyzer.bestVariantScore(merge);

        assertTrue(base.isPresent());
        assertTrue(best.isPresent());
        assertEquals(5, base.get().passedTests());
        assertEquals(10, best.get().passedTests());
    }

    @Test
    void buildFromBroken_variantCompilesWhenBaselineFails() {
        MergeOutputJSON merge = createBrokenBaselineMerge("m1");
        assertTrue(merge.isBaselineBroken());

        addSuccessVariant(merge, 1, 5, 0, 0);

        Optional<VariantScore> best = VariantResolutionAnalyzer.bestVariantScore(merge);
        assertTrue(best.isPresent());
        assertTrue(best.get().successfulModules() > 0);
    }

    @Test
    void noImprovement_variantWorse() {
        MergeOutputJSON merge = createSuccessBaselineMerge("m1", 50, 0, 0);
        addSuccessVariant(merge, 1, 50, 10, 0); // more failures

        Optional<VariantScore> base = VariantResolutionAnalyzer.baselineScore(merge);
        Optional<VariantScore> best = VariantResolutionAnalyzer.bestVariantScore(merge);

        assertTrue(base.isPresent());
        assertTrue(best.isPresent());
        assertFalse(best.get().isBetterThan(base.get()));
        assertFalse(best.get().isAtLeastAsGoodAs(base.get()));
    }

    @Test
    void exportRQ3LatexVariables_mixedScenario() {
        // Merge 1: broken baseline, variant compiles and passes tests
        MergeOutputJSON m1 = createBrokenBaselineMerge("m1");
        addSuccessVariant(m1, 1, 10, 0, 0);

        // Merge 2: working baseline (10 pass), variant passes more (15 pass)
        MergeOutputJSON m2 = createSuccessBaselineMerge("m2", 10, 0, 0);
        addSuccessVariant(m2, 1, 15, 0, 0);

        // Merge 3: working baseline (10 pass), variant passes fewer (5 pass)
        MergeOutputJSON m3 = createSuccessBaselineMerge("m3", 10, 0, 0);
        addSuccessVariant(m3, 1, 10, 5, 0);

        // Merge 4: broken baseline, variant also fails (no improvement)
        MergeOutputJSON m4 = createBrokenBaselineMerge("m4");
        addFailureVariant(m4, 1);

        List<MergeOutputJSON> all = List.of(m1, m2, m3, m4);

        // Should not throw — writes to latex CSV
        MergeStatistics stats = MergeStatistics.from(all);
        StatisticsReporter reporter = new StatisticsReporter(stats, "cache_parallel");
        reporter.exportRQ3LatexVariables(all);

        // Verify counts manually:
        // comparable: m1 (base=FAILURE/0mod, best=SUCCESS/1mod), m2, m3 = 3
        //   m4: baseline FAILURE → score(0,0), variant FAILURE → score(0,0) → comparable=YES → 4 total
        // Actually let's check: m4 baseline is FAILURE (not TIMEOUT/null), so baselineScore is present.
        //   m4 variant is FAILURE, so variantScore is present. So comparable = 4.

        // betterBuild: m1 (0→1 module) = 1
        // betterOrEqualBuild: m1 (better), m2 (equal: 1=1), m3 (equal: 1=1), m4 (equal: 0=0) = 4
        // buildFromBroken: m1 (broken + variant builds), m4 (broken but variant doesn't build) = 1
        // betterTests: m1 (0→10), m2 (10→15) = 2
        // betterOrEqualTests: m1 (better), m2 (better), m4 (0=0 equal) = 3
        // better (overall): m1 (modules better), m2 (tests better) = 2
        // betterOrEqual: m1, m2, m4 (both 0 modules, 0 tests) = 3

        // Verify via direct scoring
        assertScores(m1, 0, 0, 1, 10);
        assertScores(m2, 1, 10, 1, 15);
        assertScores(m3, 1, 10, 1, 5);
        assertScores(m4, 0, 0, 0, 0);
    }

    private void assertScores(MergeOutputJSON merge,
                              int expectedBaseModules, int expectedBaseTests,
                              int expectedBestModules, int expectedBestTests) {
        Optional<VariantScore> base = VariantResolutionAnalyzer.baselineScore(merge);
        Optional<VariantScore> best = VariantResolutionAnalyzer.bestVariantScore(merge);
        assertTrue(base.isPresent(), "baseline should be scoreable for " + merge.getMergeCommit());
        assertTrue(best.isPresent(), "best variant should be scoreable for " + merge.getMergeCommit());
        assertEquals(expectedBaseModules, base.get().successfulModules(),
                merge.getMergeCommit() + " baseline modules");
        assertEquals(expectedBaseTests, base.get().passedTests(),
                merge.getMergeCommit() + " baseline tests");
        assertEquals(expectedBestModules, best.get().successfulModules(),
                merge.getMergeCommit() + " best variant modules");
        assertEquals(expectedBestTests, best.get().passedTests(),
                merge.getMergeCommit() + " best variant tests");
    }

    // --- Helpers ---

    private MergeOutputJSON createBrokenBaselineMerge(String commitHash) {
        MergeOutputJSON merge = new MergeOutputJSON();
        merge.setMergeCommit(commitHash);
        merge.setBaselineBroken(true);
        merge.setIsMultiModule(false);
        merge.setNumConflictChunks(1);

        MergeOutputJSON.Variant baseline = new MergeOutputJSON.Variant();
        baseline.setVariantIndex(0);
        baseline.setCompilationResult(
                CompilationResult.forTest(CompilationResult.Status.FAILURE, List.of()));
        baseline.setTestResults(emptyTestTotal());

        List<MergeOutputJSON.Variant> variants = new ArrayList<>();
        variants.add(baseline);
        merge.setVariants(variants);
        return merge;
    }

    private MergeOutputJSON createSuccessBaselineMerge(String commitHash,
                                                       int runNum, int failures, int errors) {
        MergeOutputJSON merge = new MergeOutputJSON();
        merge.setMergeCommit(commitHash);
        merge.setBaselineBroken(false);
        merge.setIsMultiModule(false);
        merge.setNumConflictChunks(1);

        MergeOutputJSON.Variant baseline = new MergeOutputJSON.Variant();
        baseline.setVariantIndex(0);
        baseline.setCompilationResult(
                CompilationResult.forTest(CompilationResult.Status.SUCCESS, List.of()));
        baseline.setTestResults(testTotal(runNum, failures, errors));

        List<MergeOutputJSON.Variant> variants = new ArrayList<>();
        variants.add(baseline);
        merge.setVariants(variants);
        return merge;
    }

    private void addSuccessVariant(MergeOutputJSON merge, int index,
                                   int runNum, int failures, int errors) {
        MergeOutputJSON.Variant v = new MergeOutputJSON.Variant();
        v.setVariantIndex(index);
        v.setCompilationResult(
                CompilationResult.forTest(CompilationResult.Status.SUCCESS, List.of()));
        v.setTestResults(testTotal(runNum, failures, errors));
        v.setConflictPatterns(Map.of("f", List.of("OURS")));
        merge.getVariants().add(v);
    }

    private void addFailureVariant(MergeOutputJSON merge, int index) {
        MergeOutputJSON.Variant v = new MergeOutputJSON.Variant();
        v.setVariantIndex(index);
        v.setCompilationResult(
                CompilationResult.forTest(CompilationResult.Status.FAILURE, List.of()));
        v.setTestResults(emptyTestTotal());
        v.setConflictPatterns(Map.of("f", List.of("THEIRS")));
        merge.getVariants().add(v);
    }

    private TestTotal testTotal(int runNum, int failures, int errors) {
        TestTotal tt = new TestTotal();
        tt.setRunNum(runNum);
        tt.setFailuresNum(failures);
        tt.setErrorsNum(errors);
        tt.setSkippedNum(0);
        tt.setHasData(true);
        return tt;
    }

    private TestTotal emptyTestTotal() {
        TestTotal tt = new TestTotal();
        tt.setHasData(false);
        return tt;
    }
}
