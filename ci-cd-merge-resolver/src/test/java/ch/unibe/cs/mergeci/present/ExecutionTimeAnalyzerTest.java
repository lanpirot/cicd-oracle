package ch.unibe.cs.mergeci.present;

import ch.unibe.cs.mergeci.BaseTest;
import ch.unibe.cs.mergeci.experiment.MergeOutputJSON;
import ch.unibe.cs.mergeci.runner.maven.CompilationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ExecutionTimeAnalyzer - validates execution time metrics calculation.
 */
public class ExecutionTimeAnalyzerTest extends BaseTest {

    private ExecutionTimeAnalyzer analyzer;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        analyzer = new ExecutionTimeAnalyzer();
    }

    @Test
    void testCalculateAverageExecutionTimeRatio_SingleMerge() {
        // Baseline: 10s, Variant: 20s -> Ratio: 2.0
        MergeOutputJSON merge = createMerge(10.0f, 20.0f, 1);

        double avgRatio = analyzer.calculateAverageExecutionTimeRatio(List.of(merge));

        assertEquals(2.0, avgRatio, 0.01, "Ratio should be variantTime/baselineTime = 20/10 = 2.0");
    }

    @Test
    void testCalculateAverageExecutionTimeRatio_MultipleMerges() {
        // Merge 1: 10s baseline, 20s variant -> ratio 2.0
        // Merge 2: 20s baseline, 30s variant -> ratio 1.5
        // Average: (2.0 + 1.5) / 2 = 1.75
        MergeOutputJSON merge1 = createMerge(10.0f, 20.0f, 1);
        MergeOutputJSON merge2 = createMerge(20.0f, 30.0f, 1);

        double avgRatio = analyzer.calculateAverageExecutionTimeRatio(List.of(merge1, merge2));

        assertEquals(1.75, avgRatio, 0.01, "Average ratio should be (2.0 + 1.5) / 2 = 1.75");
    }

    @Test
    void testCalculateAverageExecutionTimeRatio_ZeroBaselineTime() {
        // Baseline: 0s (skip this merge)
        MergeOutputJSON merge1 = createMerge(0.0f, 20.0f, 1);
        // Valid merge
        MergeOutputJSON merge2 = createMerge(10.0f, 20.0f, 1);

        double avgRatio = analyzer.calculateAverageExecutionTimeRatio(List.of(merge1, merge2));

        assertEquals(2.0, avgRatio, 0.01, "Should skip merges with zero baseline time");
    }

    @Test
    void testCalculateAverageExecutionTimeRatio_AllZeroBaseline() {
        MergeOutputJSON merge1 = createMerge(0.0f, 20.0f, 1);
        MergeOutputJSON merge2 = createMerge(0.0f, 30.0f, 1);

        double avgRatio = analyzer.calculateAverageExecutionTimeRatio(List.of(merge1, merge2));

        assertEquals(0.0, avgRatio, 0.01, "Should return 0.0 when no valid ratios");
    }

    @Test
    void testCalculateAverageExecutionTimeRatio_EmptyList() {
        double avgRatio = analyzer.calculateAverageExecutionTimeRatio(Collections.emptyList());

        assertEquals(0.0, avgRatio, 0.01, "Should return 0.0 for empty list");
    }

    @Test
    void testCalculateAverageExecutionTimeRatio_VariantFasterThanBaseline() {
        // Baseline: 20s, Variant: 10s -> Ratio: 0.5 (variant is faster)
        MergeOutputJSON merge = createMerge(20.0f, 10.0f, 1);

        double avgRatio = analyzer.calculateAverageExecutionTimeRatio(List.of(merge));

        assertEquals(0.5, avgRatio, 0.01, "Ratio should be 0.5 when variant is faster");
    }

    @Test
    void testCalculateExecutionTimeDistribution_SingleConflictChunk() {
        // All merges have 1 conflict chunk
        MergeOutputJSON merge1 = createMerge(10.0f, 20.0f, 1);
        MergeOutputJSON merge2 = createMerge(15.0f, 30.0f, 1);

        Map<Integer, Double> distribution = analyzer.calculateExecutionTimeDistribution(
                List.of(merge1, merge2)
        );

        assertEquals(1, distribution.size(), "Should have one entry for conflict chunk count");
        assertTrue(distribution.containsKey(1), "Should have entry for 1 conflict chunk");
        assertEquals(2.0, distribution.get(1), 0.01,
                "Average ratio for 1 chunk: (20/10 + 30/15)/2 = (2.0 + 2.0)/2 = 2.0");
    }

    @Test
    void testCalculateExecutionTimeDistribution_MultipleConflictChunks() {
        // 1 chunk: 10s -> 20s (ratio 2.0)
        MergeOutputJSON merge1 = createMerge(10.0f, 20.0f, 1);
        // 2 chunks: 20s -> 40s (ratio 2.0)
        MergeOutputJSON merge2 = createMerge(20.0f, 40.0f, 2);
        // 3 chunks: 30s -> 90s (ratio 3.0)
        MergeOutputJSON merge3 = createMerge(30.0f, 90.0f, 3);

        Map<Integer, Double> distribution = analyzer.calculateExecutionTimeDistribution(
                List.of(merge1, merge2, merge3)
        );

        assertEquals(3, distribution.size(), "Should have three entries");
        assertEquals(2.0, distribution.get(1), 0.01);
        assertEquals(2.0, distribution.get(2), 0.01);
        assertEquals(3.0, distribution.get(3), 0.01);
    }

    @Test
    void testCalculateExecutionTimeDistribution_GroupsCorrectly() {
        // Multiple merges with same conflict chunk count
        MergeOutputJSON merge1 = createMerge(10.0f, 30.0f, 2);  // ratio 3.0
        MergeOutputJSON merge2 = createMerge(20.0f, 40.0f, 2);  // ratio 2.0
        MergeOutputJSON merge3 = createMerge(10.0f, 20.0f, 3);  // ratio 2.0

        Map<Integer, Double> distribution = analyzer.calculateExecutionTimeDistribution(
                List.of(merge1, merge2, merge3)
        );

        assertEquals(2, distribution.size(), "Should have two groups");
        assertEquals(2.5, distribution.get(2), 0.01, "Average for 2 chunks: (3.0 + 2.0)/2 = 2.5");
        assertEquals(2.0, distribution.get(3), 0.01, "Average for 3 chunks: 2.0");
    }

    @Test
    void testCalculateExecutionTimeDistribution_EmptyList() {
        Map<Integer, Double> distribution = analyzer.calculateExecutionTimeDistribution(
                Collections.emptyList()
        );

        assertTrue(distribution.isEmpty(), "Should return empty map for empty list");
    }

    @Test
    void testAnalyzeExecutionTimes_CompleteAnalysis() {
        MergeOutputJSON merge1 = createMerge(10.0f, 20.0f, 1);
        MergeOutputJSON merge2 = createMerge(20.0f, 40.0f, 2);

        ExecutionTimeAnalyzer.ExecutionTimeMetrics metrics = analyzer.analyzeExecutionTimes(
                List.of(merge1, merge2)
        );

        assertNotNull(metrics, "Metrics should not be null");
        assertEquals(2.0, metrics.getAverageRatio(), 0.01, "Average ratio should be 2.0");

        Map<Integer, Double> distribution = metrics.getDistributionByConflictChunks();
        assertNotNull(distribution, "Distribution should not be null");
        assertEquals(2, distribution.size(), "Should have entries for 1 and 2 chunks");
        assertEquals(2.0, distribution.get(1), 0.01);
        assertEquals(2.0, distribution.get(2), 0.01);
    }

    @Test
    void testAnalyzeExecutionTimes_EmptyList() {
        ExecutionTimeAnalyzer.ExecutionTimeMetrics metrics = analyzer.analyzeExecutionTimes(
                Collections.emptyList()
        );

        assertNotNull(metrics, "Metrics should not be null even for empty list");
        assertEquals(0.0, metrics.getAverageRatio(), 0.01);
        assertTrue(metrics.getDistributionByConflictChunks().isEmpty());
    }

    @Test
    void testExecutionTimeMetrics_GettersWork() {
        Map<Integer, Double> distribution = Map.of(1, 2.0, 2, 3.0);
        ExecutionTimeAnalyzer.ExecutionTimeMetrics metrics =
                new ExecutionTimeAnalyzer.ExecutionTimeMetrics(2.5, distribution);

        assertEquals(2.5, metrics.getAverageRatio(), 0.01);
        assertEquals(distribution, metrics.getDistributionByConflictChunks());
        assertEquals(2.0, metrics.getDistributionByConflictChunks().get(1), 0.01);
        assertEquals(3.0, metrics.getDistributionByConflictChunks().get(2), 0.01);
    }

    @Test
    void testCalculateAverageExecutionTimeRatio_LargeDataset() {
        List<MergeOutputJSON> merges = new ArrayList<>();
        // Create 100 merges with varying ratios
        for (int i = 1; i <= 100; i++) {
            merges.add(createMerge(10.0f, 10.0f * i / 50.0f, 1)); // ratios from 0.02 to 2.0
        }

        double avgRatio = analyzer.calculateAverageExecutionTimeRatio(merges);

        assertTrue(avgRatio > 0.0, "Should handle large datasets");
        assertTrue(avgRatio < 2.0, "Average should be reasonable");
    }

    // Helper methods

    private MergeOutputJSON createMerge(float baselineTime, float variantTime, int conflictChunks) {
        MergeOutputJSON merge = new MergeOutputJSON();

        try {
            // Create a mock Maven log file with the specified baseline time
            Path logFile = tempDir.resolve("maven-log-" + System.nanoTime() + ".txt");
            String mavenLog = String.format("""
                    [INFO] BUILD SUCCESS
                    [INFO] Total time: %.3f s
                    """, baselineTime);
            Files.writeString(logFile, mavenLog);

            // Create CompilationResult from the log file and store in human_baseline variant
            CompilationResult compilationResult = new CompilationResult(logFile);
            MergeOutputJSON.Variant baselineVariant = new MergeOutputJSON.Variant();
            baselineVariant.setVariantName("human_baseline");
            baselineVariant.setCompilationResult(compilationResult);
            List<MergeOutputJSON.Variant> allVariants = new ArrayList<>();
            allVariants.add(baselineVariant);
            MergeOutputJSON.VariantsExecution variantsExecution = new MergeOutputJSON.VariantsExecution(allVariants);
            variantsExecution.setExecutionTimeSeconds((long) variantTime);
            merge.setVariantsExecution(variantsExecution);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create test CompilationResult", e);
        }

        // Set conflict chunks
        merge.setNumConflictChunks(conflictChunks);

        return merge;
    }
}
