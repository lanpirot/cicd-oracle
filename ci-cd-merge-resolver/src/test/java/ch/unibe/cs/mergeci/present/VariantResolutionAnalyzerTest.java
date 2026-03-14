package ch.unibe.cs.mergeci.present;

import ch.unibe.cs.mergeci.BaseTest;
import ch.unibe.cs.mergeci.experiment.MergeOutputJSON;
import ch.unibe.cs.mergeci.runner.maven.TestTotal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for VariantResolutionAnalyzer - validates variant resolution analysis and pattern extraction.
 */
class VariantResolutionAnalyzerTest extends BaseTest {

    private VariantResolutionAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new VariantResolutionAnalyzer();
    }

    @Test
    void testCountSuccessfulTests() {
        TestTotal testTotal = createTestTotal(100, 10, 5);

        int successful = analyzer.countSuccessfulTests(testTotal);

        assertEquals(85, successful, "Should be 100 - 10 - 5 = 85 successful tests");
    }

    @Test
    void testCountSuccessfulTests_AllPass() {
        TestTotal testTotal = createTestTotal(50, 0, 0);

        int successful = analyzer.countSuccessfulTests(testTotal);

        assertEquals(50, successful, "All tests should be successful");
    }

    @Test
    void testCountSuccessfulTests_AllFail() {
        TestTotal testTotal = createTestTotal(50, 25, 25);

        int successful = analyzer.countSuccessfulTests(testTotal);

        assertEquals(0, successful, "No tests should be successful");
    }

    @Test
    void testFindMergesWithAtLeastOneResolution_NoMerges() {
        List<MergeOutputJSON> merges = Collections.emptyList();

        List<MergeOutputJSON> result = analyzer.findMergesWithAtLeastOneResolution(merges);

        assertTrue(result.isEmpty(), "Should return empty list for no merges");
    }

    @Test
    void testFindMergesWithAtLeastOneResolution_VariantEqualToBaseline() {
        // Create merge with baseline: 50 passing tests
        MergeOutputJSON merge = createMerge("merge1", 50, 0, 0);

        // Add variant with same success rate
        addVariant(merge, "variant1", 50, 0, 0, Map.of("file1", List.of("OURS")));

        List<MergeOutputJSON> result = analyzer.findMergesWithAtLeastOneResolution(List.of(merge));

        assertEquals(1, result.size(), "Should find merge where variant equals baseline");
        assertEquals("merge1", result.get(0).getMergeCommit());
    }

    @Test
    void testFindMergesWithAtLeastOneResolution_VariantBetterThanBaseline() {
        // Create merge with baseline: 40 passing tests
        MergeOutputJSON merge = createMerge("merge1", 50, 10, 0);

        // Add variant with better success: 45 passing tests
        addVariant(merge, "variant1", 50, 5, 0, Map.of("file1", List.of("THEIRS")));

        List<MergeOutputJSON> result = analyzer.findMergesWithAtLeastOneResolution(List.of(merge));

        assertEquals(1, result.size(), "Should find merge where variant is better");
    }

    @Test
    void testFindMergesWithAtLeastOneResolution_NoVariantsBetter() {
        // Create merge with baseline: 50 passing tests
        MergeOutputJSON merge = createMerge("merge1", 50, 0, 0);

        // Add variant with worse success: 40 passing tests
        addVariant(merge, "variant1", 50, 10, 0, Map.of("file1", List.of("BASE")));

        List<MergeOutputJSON> result = analyzer.findMergesWithAtLeastOneResolution(List.of(merge));

        assertTrue(result.isEmpty(), "Should not find merge where all variants are worse");
    }

    @Test
    void testFindMergesThatPerformBetter_StrictlyBetter() {
        // Create merge with baseline: 40 passing tests
        MergeOutputJSON merge = createMerge("merge1", 50, 10, 0);

        // Add variant with strictly better success: 45 passing tests
        addVariant(merge, "variant1", 50, 5, 0, Map.of("file1", List.of("OURS")));

        List<MergeOutputJSON> result = analyzer.findMergesThatPerformBetter(List.of(merge));

        assertEquals(1, result.size(), "Should find merge where variant performs strictly better");
    }

    @Test
    void testFindMergesThatPerformBetter_EqualNotIncluded() {
        // Create merge with baseline: 50 passing tests
        MergeOutputJSON merge = createMerge("merge1", 50, 0, 0);

        // Add variant with equal success
        addVariant(merge, "variant1", 50, 0, 0, Map.of("file1", List.of("OURS")));

        List<MergeOutputJSON> result = analyzer.findMergesThatPerformBetter(List.of(merge));

        assertTrue(result.isEmpty(), "Should NOT find merge where variant only equals baseline (not strictly better)");
    }

    @Test
    void testExtractUniformPatterns_SinglePattern() {
        // Create merge with variant using uniform pattern (all OURS)
        MergeOutputJSON merge = createMerge("merge1", 40, 10, 0);
        addVariant(merge, "variant1", 50, 0, 0, Map.of(
            "file1", List.of("OURS"),
            "file2", List.of("OURS"),
            "file3", List.of("OURS")
        ));

        List<String> patterns = analyzer.extractUniformPatterns(List.of(merge));

        assertEquals(1, patterns.size(), "Should extract one uniform pattern");
        assertEquals("OURS", patterns.get(0));
    }

    @Test
    void testExtractUniformPatterns_MixedPatternNotIncluded() {
        // Create merge with variant using mixed patterns
        MergeOutputJSON merge = createMerge("merge1", 40, 10, 0);
        addVariant(merge, "variant1", 50, 0, 0, Map.of(
            "file1", List.of("OURS"),
            "file2", List.of("THEIRS"),
            "file3", List.of("BASE")
        ));

        List<String> patterns = analyzer.extractUniformPatterns(List.of(merge));

        assertTrue(patterns.isEmpty(), "Should not extract mixed patterns");
    }

    @Test
    void testExtractUniformPatterns_MultipleVariants() {
        // Create merge with two successful uniform variants
        MergeOutputJSON merge = createMerge("merge1", 40, 10, 0);
        addVariant(merge, "variant1", 50, 0, 0, Map.of("file1", List.of("OURS")));
        addVariant(merge, "variant2", 45, 5, 0, Map.of("file1", List.of("THEIRS")));

        List<String> patterns = analyzer.extractUniformPatterns(List.of(merge));

        assertEquals(2, patterns.size(), "Should extract patterns from both variants");
        assertTrue(patterns.contains("OURS"));
        assertTrue(patterns.contains("THEIRS"));
    }

    @Test
    void testGroupPatternsByCount_EmptyList() {
        Map<String, Integer> grouped = analyzer.groupPatternsByCount(Collections.emptyList());

        assertTrue(grouped.isEmpty(), "Should return empty map for empty pattern list");
    }

    @Test
    void testGroupPatternsByCount_SinglePattern() {
        List<String> patterns = List.of("OURS", "OURS", "OURS", "THEIRS");

        Map<String, Integer> grouped = analyzer.groupPatternsByCount(patterns);

        assertEquals(2, grouped.size());
        assertEquals(3, grouped.get("OURS"));
        assertEquals(1, grouped.get("THEIRS"));
    }

    @Test
    void testGroupPatternsByCount_MultiplePatterns() {
        List<String> patterns = List.of("OURS", "THEIRS", "BASE", "OURS", "THEIRS", "OURS");

        Map<String, Integer> grouped = analyzer.groupPatternsByCount(patterns);

        assertEquals(3, grouped.size());
        assertEquals(3, grouped.get("OURS"));
        assertEquals(2, grouped.get("THEIRS"));
        assertEquals(1, grouped.get("BASE"));
    }

    @Test
    void testIdentifyUniformPattern_Uniform() {
        Map<String, List<String>> conflictPatterns = Map.of(
            "file1", List.of("OURS"),
            "file2", List.of("OURS"),
            "file3", List.of("OURS")
        );

        String pattern = analyzer.identifyUniformPattern(conflictPatterns);

        assertEquals("OURS", pattern, "Should identify uniform pattern");
    }

    @Test
    void testIdentifyUniformPattern_Mixed() {
        Map<String, List<String>> conflictPatterns = Map.of(
            "file1", List.of("OURS"),
            "file2", List.of("THEIRS"),
            "file3", List.of("BASE")
        );

        String pattern = analyzer.identifyUniformPattern(conflictPatterns);

        assertEquals("Mixed", pattern, "Should return 'Mixed' for non-uniform patterns");
    }

    @Test
    void testIdentifyUniformPattern_EmptyPatterns() {
        Map<String, List<String>> conflictPatterns = Collections.emptyMap();

        String pattern = analyzer.identifyUniformPattern(conflictPatterns);

        assertEquals("Mixed", pattern, "Should return 'Mixed' for empty patterns");
    }

    @Test
    void testIdentifyUniformPattern_MultiplePatternsSameFile() {
        Map<String, List<String>> conflictPatterns = Map.of(
            "file1", List.of("OURS", "THEIRS")
        );

        String pattern = analyzer.identifyUniformPattern(conflictPatterns);

        assertEquals("Mixed", pattern, "Should return 'Mixed' when file has multiple patterns");
    }

    @Test
    void testComplexScenario_MultiplemergesWithVariants() {
        // Merge 1: baseline fails, variant1 succeeds with OURS, variant2 succeeds with THEIRS
        MergeOutputJSON merge1 = createMerge("merge1", 50, 50, 0);
        addVariant(merge1, "variant1", 50, 0, 0, Map.of("file1", List.of("OURS")));
        addVariant(merge1, "variant2", 50, 0, 0, Map.of("file1", List.of("THEIRS")));

        // Merge 2: baseline succeeds, variant1 succeeds better with BASE
        MergeOutputJSON merge2 = createMerge("merge2", 100, 10, 0);
        addVariant(merge2, "variant1", 100, 5, 0, Map.of("file1", List.of("BASE")));

        // Merge 3: baseline succeeds, no variants better
        MergeOutputJSON merge3 = createMerge("merge3", 50, 0, 0);
        addVariant(merge3, "variant1", 50, 25, 0, Map.of("file1", List.of("OURS")));

        List<MergeOutputJSON> allMerges = List.of(merge1, merge2, merge3);

        // Test findMergesWithAtLeastOneResolution
        List<MergeOutputJSON> withResolution = analyzer.findMergesWithAtLeastOneResolution(allMerges);
        assertEquals(2, withResolution.size(), "Should find merge1 and merge2");

        // Test findMergesThatPerformBetter
        List<MergeOutputJSON> performBetter = analyzer.findMergesThatPerformBetter(allMerges);
        assertEquals(2, performBetter.size(), "Should find merge1 and merge2");

        // Test extractUniformPatterns
        List<String> patterns = analyzer.extractUniformPatterns(allMerges);
        assertEquals(3, patterns.size(), "Should extract OURS, THEIRS, and BASE");

        // Test groupPatternsByCount
        Map<String, Integer> grouped = analyzer.groupPatternsByCount(patterns);
        assertEquals(1, grouped.get("OURS"));
        assertEquals(1, grouped.get("THEIRS"));
        assertEquals(1, grouped.get("BASE"));
    }

    // Helper methods to create test data

    private TestTotal createTestTotal(int runNum, int failuresNum, int errorsNum) {
        TestTotal testTotal = new TestTotal();
        testTotal.setRunNum(runNum);
        testTotal.setFailuresNum(failuresNum);
        testTotal.setErrorsNum(errorsNum);
        testTotal.setSkippedNum(0);
        testTotal.setElapsedTime(0.0f);
        return testTotal;
    }

    private MergeOutputJSON createMerge(String commitHash, int runNum, int failuresNum, int errorsNum) {
        MergeOutputJSON merge = new MergeOutputJSON();
        merge.setMergeCommit(commitHash);
        MergeOutputJSON.Variant baselineVariant = new MergeOutputJSON.Variant();
        baselineVariant.setVariantName("human_baseline");
        baselineVariant.setTestResults(createTestTotal(runNum, failuresNum, errorsNum));
        List<MergeOutputJSON.Variant> allVariants = new ArrayList<>();
        allVariants.add(baselineVariant);
        merge.setVariantsExecution(new MergeOutputJSON.VariantsExecution(allVariants));
        return merge;
    }

    private void addVariant(MergeOutputJSON merge, String variantName, int runNum, int failuresNum, int errorsNum,
                           Map<String, List<String>> conflictPatterns) {
        MergeOutputJSON.Variant variant = new MergeOutputJSON.Variant();
        variant.setTestResults(createTestTotal(runNum, failuresNum, errorsNum));
        variant.setConflictPatterns(conflictPatterns);

        if (merge.getVariantsExecution().getVariants() == null) {
            merge.getVariantsExecution().setVariants(new ArrayList<>());
        }
        merge.getVariantsExecution().getVariants().add(variant);
    }
}
