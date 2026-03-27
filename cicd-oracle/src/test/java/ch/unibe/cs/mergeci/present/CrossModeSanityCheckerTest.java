package ch.unibe.cs.mergeci.present;

import ch.unibe.cs.mergeci.BaseTest;
import ch.unibe.cs.mergeci.experiment.MergeOutputJSON;
import ch.unibe.cs.mergeci.runner.maven.CompilationResult;
import ch.unibe.cs.mergeci.runner.maven.TestTotal;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CrossModeSanityCheckerTest extends BaseTest {

    // ---- happy path ----

    @Test
    void consistentVariants_noMismatches() {
        var result = check(
                mode("a", merge("proj", "c1", variant(0, CompilationResult.Status.SUCCESS, 10, 1, 0, 2, false))),
                mode("b", merge("proj", "c1", variant(0, CompilationResult.Status.SUCCESS, 10, 1, 0, 2, false)))
        );
        assertEquals(1, result.variantsCompared());
        assertEquals(0, result.compilationMismatches());
        assertEquals(0, result.testMismatches());
        assertTrue(result.passed());
    }

    @Test
    void multipleVariants_allConsistent() {
        var result = check(
                mode("a",
                        merge("proj", "c1",
                                variant(0, CompilationResult.Status.SUCCESS, 5, 0, 0, 0, false),
                                variant(1, CompilationResult.Status.FAILURE, 0, 0, 0, 0, false))),
                mode("b",
                        merge("proj", "c1",
                                variant(0, CompilationResult.Status.SUCCESS, 5, 0, 0, 0, false),
                                variant(1, CompilationResult.Status.FAILURE, 0, 0, 0, 0, false)))
        );
        assertEquals(2, result.variantsCompared());
        assertEquals(0, result.compilationMismatches());
        assertEquals(0, result.testMismatches());
        assertTrue(result.passed());
    }

    // ---- timed-out exclusion ----

    @Test
    void timedOutInBothModes_excluded() {
        var result = check(
                mode("a", merge("proj", "c1", variant(0, CompilationResult.Status.SUCCESS, 10, 0, 0, 0, true))),
                mode("b", merge("proj", "c1", variant(0, CompilationResult.Status.FAILURE,  5, 1, 0, 0, true)))
        );
        assertEquals(0, result.variantsCompared());
        assertEquals(0, result.compilationMismatches());
        assertTrue(result.passed());
    }

    @Test
    void timedOutInOneMode_excludedFromComparison() {
        // variant 0: mode-a timed out, mode-b did not → only 1 non-timed-out result → skip
        var result = check(
                mode("a", merge("proj", "c1", variant(0, CompilationResult.Status.SUCCESS, 10, 0, 0, 0, true))),
                mode("b", merge("proj", "c1", variant(0, CompilationResult.Status.FAILURE,  5, 1, 0, 0, false)))
        );
        assertEquals(0, result.variantsCompared());
        assertEquals(0, result.compilationMismatches());
        assertTrue(result.passed());
    }

    // ---- compilation mismatch (zero tolerance → FAIL) ----

    @Test
    void compilationMismatch_failsCheck() {
        var result = check(
                mode("a", merge("proj", "c1", variant(0, CompilationResult.Status.SUCCESS, 10, 0, 0, 0, false))),
                mode("b", merge("proj", "c1", variant(0, CompilationResult.Status.FAILURE,   0, 0, 0, 0, false)))
        );
        assertEquals(1, result.variantsCompared());
        assertEquals(1, result.compilationMismatches());
        // test counts also differ (10 vs 0) because the failed build ran no tests
        assertEquals(1, result.testMismatches());
        assertFalse(result.passed());
    }

    @Test
    void compilationMismatch_countedOncePerVariantPosition() {
        // Three modes disagree on variant 0: still counts as 1 position mismatch, not 2
        var byMode = new LinkedHashMap<String, Map<String, MergeOutputJSON>>();
        byMode.put("a", Map.of("proj/c1", mergeObj("proj", "c1",
                variant(0, CompilationResult.Status.SUCCESS, 10, 0, 0, 0, false))));
        byMode.put("b", Map.of("proj/c1", mergeObj("proj", "c1",
                variant(0, CompilationResult.Status.FAILURE,  0, 0, 0, 0, false))));
        byMode.put("c", Map.of("proj/c1", mergeObj("proj", "c1",
                variant(0, CompilationResult.Status.FAILURE,  0, 0, 0, 0, false))));

        var result = CrossModeSanityChecker.checkInMemory(byMode);
        assertEquals(1, result.variantsCompared());
        assertEquals(1, result.compilationMismatches());
        assertFalse(result.passed());
    }

    // ---- test count mismatch (flaky tests) ----

    @Test
    void testCountMismatch_smallDeviation() {
        // 1 mismatch out of 1 variant = 100% rate → exceeds threshold → FAIL
        var result = check(
                mode("a", merge("proj", "c1", variant(0, CompilationResult.Status.SUCCESS, 100, 0, 0, 0, false))),
                mode("b", merge("proj", "c1", variant(0, CompilationResult.Status.SUCCESS,  100, 1, 0, 0, false)))
        );
        assertEquals(1, result.variantsCompared());
        assertEquals(0, result.compilationMismatches());
        assertEquals(1, result.testMismatches());
        // deviation: |Δfail|=1 / max(run)=100 = 1%
        assertEquals(0.01, result.maxTestDeviation(), 1e-9);
        assertEquals(0.01, result.medianTestDeviation(), 1e-9);
        assertFalse(result.passed()); // 1/1 = 100% mismatch rate > 1%
    }

    @Test
    void testMismatchRate_withinThreshold_passes() {
        // 200 variants, 1 has a test mismatch → 0.5% rate < 1% → PASS
        var byMode = new LinkedHashMap<String, Map<String, MergeOutputJSON>>();
        Map<String, MergeOutputJSON> modeA = new LinkedHashMap<>();
        Map<String, MergeOutputJSON> modeB = new LinkedHashMap<>();
        // 1 mismatched variant
        modeA.put("proj/c0", mergeObj("proj", "c0",
                variant(0, CompilationResult.Status.SUCCESS, 50, 0, 0, 0, false)));
        modeB.put("proj/c0", mergeObj("proj", "c0",
                variant(0, CompilationResult.Status.SUCCESS, 50, 1, 0, 0, false)));
        // 199 consistent variants (across multiple merges with 1 variant each)
        for (int i = 1; i <= 199; i++) {
            String key = "proj/c" + i;
            modeA.put(key, mergeObj("proj", "c" + i,
                    variant(0, CompilationResult.Status.SUCCESS, 50, 0, 0, 0, false)));
            modeB.put(key, mergeObj("proj", "c" + i,
                    variant(0, CompilationResult.Status.SUCCESS, 50, 0, 0, 0, false)));
        }
        byMode.put("a", modeA);
        byMode.put("b", modeB);

        var result = CrossModeSanityChecker.checkInMemory(byMode);
        assertEquals(200, result.variantsCompared());
        assertEquals(1, result.testMismatches());
        assertTrue(result.passed());
        // deviation: |Δfail|=1 / 50 = 2%
        assertEquals(0.02, result.maxTestDeviation(), 1e-9);
    }

    @Test
    void testDeviation_magnitude_reported() {
        // Large deviation: 50 tests differ out of 200
        var result = check(
                mode("a", merge("proj", "c1", variant(0, CompilationResult.Status.SUCCESS, 200, 0, 0, 0, false))),
                mode("b", merge("proj", "c1", variant(0, CompilationResult.Status.SUCCESS, 200, 50, 0, 0, false)))
        );
        assertEquals(1, result.testMismatches());
        // deviation: |Δfail|=50 / max(run)=200 = 25%
        assertEquals(0.25, result.maxTestDeviation(), 1e-9);
    }

    // ---- edge cases ----

    @Test
    void singleMode_noComparison() {
        var byMode = Map.of("a", Map.of("proj/c1",
                mergeObj("proj", "c1", variant(0, CompilationResult.Status.SUCCESS, 5, 0, 0, 0, false))));
        var result = CrossModeSanityChecker.checkInMemory(byMode);
        assertEquals(0, result.variantsCompared());
        assertTrue(result.passed());
    }

    @Test
    void mergeOnlyInOneMode_skipped() {
        // merge c1 only in mode-a, merge c2 only in mode-b → nothing to compare
        var result = check(
                mode("a", merge("proj", "c1", variant(0, CompilationResult.Status.SUCCESS, 5, 0, 0, 0, false))),
                mode("b", merge("proj", "c2", variant(0, CompilationResult.Status.SUCCESS, 5, 0, 0, 0, false)))
        );
        assertEquals(0, result.variantsCompared());
        assertTrue(result.passed());
    }

    @Test
    void variantOnlyInOneMode_skipped() {
        // variant 1 only exists in mode-a (budget exhausted in mode-b) → skip variant 1
        var result = check(
                mode("a", merge("proj", "c1",
                        variant(0, CompilationResult.Status.SUCCESS, 5, 0, 0, 0, false),
                        variant(1, CompilationResult.Status.SUCCESS, 5, 0, 0, 0, false))),
                mode("b", merge("proj", "c1",
                        variant(0, CompilationResult.Status.SUCCESS, 5, 0, 0, 0, false)))
        );
        assertEquals(1, result.variantsCompared()); // only variant 0 compared
        assertEquals(0, result.compilationMismatches());
        assertTrue(result.passed());
    }

    // ---- helpers ----

    /** Calls checkInMemory with exactly two named modes. */
    private CrossModeSanityChecker.SanityCheckResult check(
            Map.Entry<String, Map<String, MergeOutputJSON>> modeA,
            Map.Entry<String, Map<String, MergeOutputJSON>> modeB) {
        Map<String, Map<String, MergeOutputJSON>> byMode = new LinkedHashMap<>();
        byMode.put(modeA.getKey(), modeA.getValue());
        byMode.put(modeB.getKey(), modeB.getValue());
        return CrossModeSanityChecker.checkInMemory(byMode);
    }

    private Map.Entry<String, Map<String, MergeOutputJSON>> mode(String name, Map.Entry<String, MergeOutputJSON> mergeEntry) {
        return Map.entry(name, Map.of(mergeEntry.getKey(), mergeEntry.getValue()));
    }

    @SafeVarargs
    private Map.Entry<String, MergeOutputJSON> merge(String project, String commit, MergeOutputJSON.Variant... variants) {
        MergeOutputJSON m = mergeObj(project, commit, variants);
        return Map.entry(project + "/" + commit, m);
    }

    @SafeVarargs
    private MergeOutputJSON mergeObj(String project, String commit, MergeOutputJSON.Variant... variants) {
        MergeOutputJSON m = new MergeOutputJSON();
        m.setProjectName(project);
        m.setMergeCommit(commit);
        m.setVariants(List.of(variants));
        return m;
    }

    private MergeOutputJSON.Variant variant(int idx, CompilationResult.Status status,
                                             int run, int fail, int err, int skip,
                                             boolean timedOut) {
        MergeOutputJSON.Variant v = new MergeOutputJSON.Variant();
        v.setVariantIndex(idx);
        v.setTimedOut(timedOut);
        v.setCompilationResult(CompilationResult.forTest(status, List.of()));

        TestTotal tr = new TestTotal();
        tr.setRunNum(run);
        tr.setFailuresNum(fail);
        tr.setErrorsNum(err);
        tr.setSkippedNum(skip);
        v.setTestResults(tr);

        return v;
    }
}
