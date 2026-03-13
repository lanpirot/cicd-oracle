package ch.unibe.cs.mergeci.experimentSetup.analysis;

import ch.unibe.cs.mergeci.BaseTest;
import ch.unibe.cs.mergeci.experimentSetup.evaluationCollection.MergeOutputJSON;
import ch.unibe.cs.mergeci.service.projectRunners.maven.JacocoReportFinder;
import ch.unibe.cs.mergeci.service.projectRunners.maven.TestTotal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for VariantRankingAnalyzer - validates variant ranking and grouping logic.
 */
class VariantRankingAnalyzerTest extends BaseTest {

    private VariantRankingAnalyzer analyzer;
    private VariantResolutionAnalyzer resolutionAnalyzer;

    @BeforeEach
    void setUp() {
        resolutionAnalyzer = new VariantResolutionAnalyzer();
        analyzer = new VariantRankingAnalyzer(resolutionAnalyzer);
    }

    @Test
    void testGenerateRanking_EmptyMerges() {
        Map<String, Integer> ranking = analyzer.generateRanking(Collections.emptyList());

        assertEquals(5, ranking.size());
        assertEquals(0, ranking.get("Human"));
        assertEquals(0, ranking.get("Ours"));
        assertEquals(0, ranking.get("Theirs"));
        assertEquals(0, ranking.get("Mixed"));
        assertEquals(0, ranking.get("AtLeastOneNonHuman"));
    }

    @Test
    void testGenerateRanking_HumanBest() {
        // Create merge where human resolution is best (no variants better)
        MergeOutputJSON merge = createMerge("merge1", 50, 0, 0, 2);
        addVariant(merge, 50, 10, 0, Map.of("file1", List.of("OursPattern")));
        addVariant(merge, 50, 20, 0, Map.of("file1", List.of("TheirsPattern")));

        Map<String, Integer> ranking = analyzer.generateRanking(List.of(merge));

        assertEquals(1, ranking.get("Human"), "Human should be best");
        assertEquals(0, ranking.get("Ours"), "Ours not best");
        assertEquals(0, ranking.get("Theirs"), "Theirs not best");
        assertEquals(0, ranking.get("AtLeastOneNonHuman"), "No non-human resolution was best");
    }

    @Test
    void testGenerateRanking_OursBest() {
        // Create merge where Ours pattern is best
        MergeOutputJSON merge = createMerge("merge1", 50, 30, 0, 1);  // 20 passing
        addVariant(merge, 50, 5, 0, Map.of("file1", List.of("OursPattern")));  // 45 passing - BEST
        addVariant(merge, 50, 20, 0, Map.of("file1", List.of("TheirsPattern"))); // 30 passing

        Map<String, Integer> ranking = analyzer.generateRanking(List.of(merge));

        assertEquals(0, ranking.get("Human"), "Human not best");
        assertEquals(1, ranking.get("Ours"), "Ours should be best");
        assertEquals(0, ranking.get("Theirs"), "Theirs not best");
        assertEquals(1, ranking.get("AtLeastOneNonHuman"), "Non-human resolution was best");
    }

    @Test
    void testGenerateRanking_TheirsBest() {
        // Create merge where Theirs pattern is best
        MergeOutputJSON merge = createMerge("merge1", 50, 30, 0, 1);  // 20 passing
        addVariant(merge, 50, 20, 0, Map.of("file1", List.of("OursPattern")));  // 30 passing
        addVariant(merge, 50, 0, 0, Map.of("file1", List.of("TheirsPattern")));  // 50 passing - BEST

        Map<String, Integer> ranking = analyzer.generateRanking(List.of(merge));

        assertEquals(0, ranking.get("Human"));
        assertEquals(0, ranking.get("Ours"));
        assertEquals(1, ranking.get("Theirs"), "Theirs should be best");
        assertEquals(1, ranking.get("AtLeastOneNonHuman"));
    }

    @Test
    void testGenerateRanking_MixedBest() {
        // Create merge where Mixed pattern is best
        MergeOutputJSON merge = createMerge("merge1", 50, 30, 0, 1);  // 20 passing
        addVariant(merge, 50, 0, 0, Map.of(  // 50 passing - BEST, but Mixed
            "file1", List.of("OursPattern"),
            "file2", List.of("TheirsPattern")
        ));

        Map<String, Integer> ranking = analyzer.generateRanking(List.of(merge));

        assertEquals(0, ranking.get("Human"));
        assertEquals(0, ranking.get("Ours"));
        assertEquals(0, ranking.get("Theirs"));
        assertEquals(1, ranking.get("Mixed"), "Mixed should be best");
        assertEquals(1, ranking.get("AtLeastOneNonHuman"));
    }

    @Test
    void testGenerateRanking_MultipleBest() {
        // Create merge where multiple strategies tie for best
        MergeOutputJSON merge = createMerge("merge1", 50, 10, 0, 1);  // 40 passing (human)
        addVariant(merge, 50, 10, 0, Map.of("file1", List.of("OursPattern")));  // 40 passing - TIED
        addVariant(merge, 50, 10, 0, Map.of("file1", List.of("TheirsPattern"))); // 40 passing - TIED

        Map<String, Integer> ranking = analyzer.generateRanking(List.of(merge));

        assertEquals(1, ranking.get("Human"), "Human tied for best");
        assertEquals(1, ranking.get("Ours"), "Ours tied for best");
        assertEquals(1, ranking.get("Theirs"), "Theirs tied for best");
        assertEquals(1, ranking.get("AtLeastOneNonHuman"), "Non-human resolutions were among best");
    }

    @Test
    void testGenerateRanking_MultipleMerges() {
        // Merge 1: Ours best
        MergeOutputJSON merge1 = createMerge("merge1", 50, 30, 0, 1);
        addVariant(merge1, 50, 0, 0, Map.of("file1", List.of("OursPattern")));

        // Merge 2: Theirs best
        MergeOutputJSON merge2 = createMerge("merge2", 50, 30, 0, 1);
        addVariant(merge2, 50, 0, 0, Map.of("file1", List.of("TheirsPattern")));

        // Merge 3: Human best
        MergeOutputJSON merge3 = createMerge("merge3", 50, 0, 0, 1);
        addVariant(merge3, 50, 10, 0, Map.of("file1", List.of("OursPattern")));

        Map<String, Integer> ranking = analyzer.generateRanking(List.of(merge1, merge2, merge3));

        assertEquals(1, ranking.get("Human"));
        assertEquals(1, ranking.get("Ours"));
        assertEquals(1, ranking.get("Theirs"));
        assertEquals(2, ranking.get("AtLeastOneNonHuman"));
    }

    @Test
    void testGenerateSortedRanking() {
        // Create merges with different best resolutions
        MergeOutputJSON merge1 = createMerge("merge1", 50, 30, 0, 1);
        addVariant(merge1, 50, 0, 0, Map.of("file1", List.of("OursPattern")));

        MergeOutputJSON merge2 = createMerge("merge2", 50, 30, 0, 1);
        addVariant(merge2, 50, 0, 0, Map.of("file1", List.of("OursPattern")));

        MergeOutputJSON merge3 = createMerge("merge3", 50, 30, 0, 1);
        addVariant(merge3, 50, 0, 0, Map.of("file1", List.of("TheirsPattern")));

        Map<String, Integer> ranking = analyzer.generateSortedRanking(List.of(merge1, merge2, merge3));

        // Check ordering (highest first)
        List<String> keys = new ArrayList<>(ranking.keySet());
        assertEquals("AtLeastOneNonHuman", keys.get(0), "Should be sorted by value descending");
        assertEquals(3, ranking.get("AtLeastOneNonHuman"));

        assertTrue(ranking instanceof LinkedHashMap, "Should preserve insertion order");
    }

    @Test
    void testRankByCoverageThresholds() {
        // Create merges with different coverage levels
        MergeOutputJSON lowCoverage = createMergeWithCoverage("merge1", 0.1f, 50, 30, 0, 1);
        addVariant(lowCoverage, 50, 0, 0, Map.of("file1", List.of("OursPattern")));

        MergeOutputJSON mediumCoverage = createMergeWithCoverage("merge2", 0.5f, 50, 30, 0, 1);
        addVariant(mediumCoverage, 50, 0, 0, Map.of("file1", List.of("TheirsPattern")));

        MergeOutputJSON highCoverage = createMergeWithCoverage("merge3", 0.8f, 50, 0, 0, 1);
        addVariant(highCoverage, 50, 10, 0, Map.of("file1", List.of("OursPattern")));

        Map<String, Map<String, Integer>> rankings = analyzer.rankByCoverageThresholds(
                List.of(lowCoverage, mediumCoverage, highCoverage)
        );

        // Check coverage ranges
        assertTrue(rankings.containsKey("[0.00, 0.15)"), "Should have low coverage range");
        assertTrue(rankings.containsKey("[0.40, 0.60)"), "Should have medium coverage range");
        assertTrue(rankings.containsKey("[0.60, 1.00)"), "Should have high coverage range");

        // Verify rankings within ranges
        assertEquals(1, rankings.get("[0.00, 0.15)").get("Ours"));
        assertEquals(1, rankings.get("[0.40, 0.60)").get("Theirs"));
        assertEquals(1, rankings.get("[0.60, 1.00)").get("Human"));
    }

    @Test
    void testRankByConflictChunks() {
        // Create merges with different conflict chunk counts
        MergeOutputJSON merge1 = createMerge("merge1", 50, 30, 0, 1);
        addVariant(merge1, 50, 0, 0, Map.of("file1", List.of("OursPattern")));

        MergeOutputJSON merge2 = createMerge("merge2", 50, 30, 0, 2);
        addVariant(merge2, 50, 0, 0, Map.of("file1", List.of("TheirsPattern")));

        MergeOutputJSON merge3 = createMerge("merge3", 50, 30, 0, 2);
        addVariant(merge3, 50, 0, 0, Map.of("file1", List.of("OursPattern")));

        Map<Integer, Map<String, Integer>> rankings = analyzer.rankByConflictChunks(
                List.of(merge1, merge2, merge3)
        );

        // Check grouping by conflict chunks
        assertEquals(2, rankings.size(), "Should have 2 groups (1 chunk and 2 chunks)");
        assertTrue(rankings.containsKey(1));
        assertTrue(rankings.containsKey(2));

        // Verify rankings within groups
        assertEquals(1, rankings.get(1).get("Ours"));
        assertEquals(1, rankings.get(2).get("Theirs"));
        assertEquals(1, rankings.get(2).get("Ours"));
    }

    @Test
    void testCoverageRankingClass() {
        Map<String, Integer> ranking = Map.of("Ours", 5, "Theirs", 3);
        VariantRankingAnalyzer.CoverageRanking coverageRanking =
                new VariantRankingAnalyzer.CoverageRanking("[0.00, 0.50)", ranking, 8);

        assertEquals("[0.00, 0.50)", coverageRanking.getRange());
        assertEquals(ranking, coverageRanking.getRanking());
        assertEquals(8, coverageRanking.getTotalMerges());
    }

    @Test
    void testConflictChunksRankingClass() {
        Map<String, Integer> ranking = Map.of("Ours", 5, "Theirs", 3);
        VariantRankingAnalyzer.ConflictChunksRanking chunksRanking =
                new VariantRankingAnalyzer.ConflictChunksRanking(3, ranking, 8);

        assertEquals(3, chunksRanking.getNumConflictChunks());
        assertEquals(ranking, chunksRanking.getRanking());
        assertEquals(8, chunksRanking.getTotalMerges());
    }

    // Helper methods

    private TestTotal createTestTotal(int runNum, int failuresNum, int errorsNum) {
        TestTotal testTotal = new TestTotal();
        testTotal.setRunNum(runNum);
        testTotal.setFailuresNum(failuresNum);
        testTotal.setErrorsNum(errorsNum);
        testTotal.setSkippedNum(0);
        testTotal.setElapsedTime(0.0f);
        return testTotal;
    }

    private MergeOutputJSON createMerge(String commitHash, int runNum, int failuresNum, int errorsNum, int conflictChunks) {
        MergeOutputJSON merge = new MergeOutputJSON();
        merge.setMergeCommit(commitHash);
        merge.setTestResults(createTestTotal(runNum, failuresNum, errorsNum));
        merge.setVariantsExecution(new MergeOutputJSON.VariantsExecution());
        merge.setNumConflictChunks(conflictChunks);
        return merge;
    }

    private MergeOutputJSON createMergeWithCoverage(String commitHash, float lineCoverage,
                                                     int runNum, int failuresNum, int errorsNum, int conflictChunks) {
        MergeOutputJSON merge = createMerge(commitHash, runNum, failuresNum, errorsNum, conflictChunks);
        merge.setCoverage(new JacocoReportFinder.CoverageDTO(lineCoverage, lineCoverage));
        return merge;
    }

    private void addVariant(MergeOutputJSON merge, int runNum, int failuresNum, int errorsNum,
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
