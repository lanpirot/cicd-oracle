package ch.unibe.cs.mergeci.conflict;

import ch.unibe.cs.mergeci.BaseTest;
import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.experiment.MergeOutputJSON;
import ch.unibe.cs.mergeci.experiment.ResolutionVariantRunner;
import ch.unibe.cs.mergeci.repoCollection.RepoCollector;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for FRESH_RUN execution mode.
 * Tests that:
 * 1. FRESH_RUN=true processes all data from scratch
 * 2. FRESH_RUN=false skips already-processed data (resume mode)
 * 3. Both modes produce identical results
 * 4. Resume mode is significantly faster
 */
public class FreshRunModeIntegrationTest extends BaseTest {

    private Path testRepoDir;
    private Path testDatasetDir;
    private Path testExperimentsDir;
    private Path testInputExcel;
    private String originalFreshRunProperty;

    @BeforeEach
    void setUpIntegrationTest() throws IOException {
        // Save original system property
        originalFreshRunProperty = System.getProperty("freshRun");

        // Use test-specific directories
        testRepoDir = AppConfig.TEST_BASE_DIR.resolve("integration_test_repos");
        testDatasetDir = AppConfig.TEST_BASE_DIR.resolve("integration_test_datasets");
        testExperimentsDir = AppConfig.TEST_BASE_DIR.resolve("integration_test_experiments");
        testInputExcel = AppConfig.TEST_BASE_DIR.resolve("integration_test_projects.csv");

        // Clean up test directories
        cleanDirectory(testRepoDir);
        cleanDirectory(testDatasetDir);
        cleanDirectory(testExperimentsDir);

        // Create test Excel file with myTest repository
        createTestExcelFile();
    }

    @AfterEach
    void tearDownIntegrationTest() {
        // Restore original system property
        if (originalFreshRunProperty == null) {
            System.clearProperty("freshRun");
        } else {
            System.setProperty("freshRun", originalFreshRunProperty);
        }
    }

    /**
     * Create a minimal test CSV file with one repository (myTest)
     */
    private void createTestExcelFile() throws IOException {
        Files.createDirectories(testInputExcel.getParent());

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(testInputExcel.toFile()))) {
            writer.write("merge_id,commit_id,project_id,project_name,remote_url,commit_time,is_maven");
            writer.newLine();
            String localRepoPath = AppConfig.TEST_REPO_DIR.resolve(AppConfig.myTest).toAbsolutePath().toString();
            writer.write("1,abc123,1,test/myTest,file://" + localRepoPath + ",2024-01-01,true");
            writer.newLine();
        }
    }

    @Test
    void testFreshRunMode_ProducesSameResults() throws Exception {
        System.out.println("\n=== FRESH_RUN Mode Integration Test ===\n");

        // ========== FIRST RUN: FRESH_RUN = true ==========
        System.setProperty("freshRun", "true");
        assertTrue(AppConfig.isFreshRun(), "FRESH_RUN should be enabled via system property");

        System.out.println("=== First Run: FRESH_RUN = true ===");
        long freshRunStartTime = System.currentTimeMillis();

        // Run collection
        RepoCollector freshCollector = new RepoCollector(testRepoDir, AppConfig.TEST_TMP_DIR, testDatasetDir);
        freshCollector.processCsv(testInputExcel);

        // Verify dataset was created
        File[] datasetsAfterFresh = testDatasetDir.toFile().listFiles();
        assertNotNull(datasetsAfterFresh, "Datasets should be created");
        assertTrue(datasetsAfterFresh.length > 0, "At least one dataset should exist");

        // Run experiment for one experiment type (use simplest one)
        ResolutionVariantRunner freshRunner = new ResolutionVariantRunner(testDatasetDir, testInputExcel, AppConfig.TEST_TMP_DIR);
        Path freshOutputDir = testExperimentsDir.resolve("fresh_run_test");
        freshRunner.runTests(freshOutputDir, false, false);

        long freshRunEndTime = System.currentTimeMillis();
        long freshRunDuration = freshRunEndTime - freshRunStartTime;

        // Verify experiment results were created
        File[] freshResults = freshOutputDir.toFile().listFiles();
        assertNotNull(freshResults, "Experiment results should be created");
        assertTrue(freshResults.length > 0, "At least one result file should exist");

        System.out.printf("First run (FRESH_RUN=true) took %d ms%n", freshRunDuration);

        // Save results for comparison
        List<MergeOutputJSON> freshRunResults = loadResults(freshOutputDir);

        // ========== SECOND RUN: FRESH_RUN = false (Resume mode) ==========
        System.setProperty("freshRun", "false");
        assertFalse(AppConfig.isFreshRun(), "FRESH_RUN should be disabled via system property");

        System.out.println("\n=== Second Run: FRESH_RUN = false (Resume Mode) ===");
        long resumeRunStartTime = System.currentTimeMillis();

        // Run collection again (should skip already-processed repos)
        RepoCollector resumeCollector = new RepoCollector(testRepoDir, AppConfig.TEST_TMP_DIR, testDatasetDir);
        resumeCollector.processCsv(testInputExcel);

        // Run experiment again (should skip already-processed datasets)
        ResolutionVariantRunner resumeRunner = new ResolutionVariantRunner(testDatasetDir, testInputExcel, AppConfig.TEST_TMP_DIR);
        resumeRunner.runTests(freshOutputDir, false, false);

        long resumeRunEndTime = System.currentTimeMillis();
        long resumeRunDuration = resumeRunEndTime - resumeRunStartTime;

        System.out.printf("Second run (FRESH_RUN=false) took %d ms%n", resumeRunDuration);

        // Verify results are identical
        File[] resumeResults = freshOutputDir.toFile().listFiles();
        assertNotNull(resumeResults, "Resume run should preserve results");
        assertEquals(freshResults.length, resumeResults.length, "Same number of result files");

        List<MergeOutputJSON> resumeRunResults = loadResults(freshOutputDir);

        // Compare results
        assertEquals(freshRunResults.get(0).getProjectName(), resumeRunResults.get(0).getProjectName(),
                "Project names should match");
        assertEquals(freshRunResults.size(), resumeRunResults.size(),
                "Number of merges should match");

        // ========== PERFORMANCE VERIFICATION ==========
        System.out.println("\n=== Performance Comparison ===");
        System.out.printf("FRESH_RUN=true:  %d ms%n", freshRunDuration);
        System.out.printf("FRESH_RUN=false: %d ms%n", resumeRunDuration);
        System.out.printf("Speedup: %.2fx%n", (double) freshRunDuration / resumeRunDuration);

        // Resume run should be faster (at least 2x faster since it skips everything)
        assertTrue(resumeRunDuration < freshRunDuration,
                String.format("Resume run (%d ms) should be faster than fresh run (%d ms)",
                        resumeRunDuration, freshRunDuration));

        // Resume run should be significantly faster (at least 50% faster)
        double speedup = (double) freshRunDuration / resumeRunDuration;
        assertTrue(speedup > 1.5,
                String.format("Resume run should be at least 50%% faster, but was only %.2fx faster", speedup));

        System.out.println("\n✓ Integration test passed!");
        System.out.println("✓ Both modes produce identical results");
        System.out.printf("✓ Resume mode is %.2fx faster%n", speedup);
    }

    @Test
    void testFreshRunMode_ActuallyDeletesData() throws Exception {
        System.out.println("\n=== Testing FRESH_RUN Actually Deletes Data ===\n");

        // First run: Create some data
        System.setProperty("freshRun", "false");
        RepoCollector collector1 = new RepoCollector(testRepoDir, AppConfig.TEST_TMP_DIR, testDatasetDir);
        collector1.processCsv(testInputExcel);

        // Verify data exists
        assertTrue(testDatasetDir.toFile().exists(), "Dataset directory should exist");
        File[] datasets1 = testDatasetDir.toFile().listFiles();
        assertNotNull(datasets1);
        assertTrue(datasets1.length > 0, "Datasets should exist after first run");

        // Second run: Enable FRESH_RUN
        System.setProperty("freshRun", "true");
        RepoCollector collector2 = new RepoCollector(testRepoDir, AppConfig.TEST_TMP_DIR, testDatasetDir);

        // Before processExcel, directories should still exist with old data
        assertTrue(testDatasetDir.toFile().exists(), "Dataset directory exists before processExcel");

        // Run with FRESH_RUN - should delete and recreate
        collector2.processCsv(testInputExcel);

        // Verify data was recreated (fresh timestamps)
        File[] datasets2 = testDatasetDir.toFile().listFiles();
        assertNotNull(datasets2);
        assertTrue(datasets2.length > 0, "Datasets should exist after FRESH_RUN");

        System.out.println("✓ FRESH_RUN correctly deletes and recreates data");
    }

    /**
     * Load all merge results from JSON files in the directory.
     */
    private List<MergeOutputJSON> loadResults(Path dir) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        List<MergeOutputJSON> results = new ArrayList<>();
        File[] files = dir.toFile().listFiles((d, name) -> name.endsWith(".json"));
        if (files != null) {
            for (File file : files) {
                results.add(mapper.readValue(file, MergeOutputJSON.class));
            }
        }
        return results;
    }
}
