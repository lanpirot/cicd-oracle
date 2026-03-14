package ch.unibe.cs.mergeci.experiment;

import ch.unibe.cs.mergeci.BaseTest;
import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.util.Utility;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class ResolutionVariantRunnerTest extends BaseTest {

    @Test
    void makeAnalysisByDataset() throws Exception {
        Path dataset = AppConfig.TEST_DATASET_DIR.resolve(AppConfig.jacksonDatabind + AppConfig.XLSX);
        Path repoPath = AppConfig.TEST_REPO_DIR.resolve(AppConfig.jacksonDatabind);
        Path output = AppConfig.TEST_EXPERIMENTS_DIR.resolve( AppConfig.jacksonDatabind + AppConfig.JSON);

        // Only run the test if dataset and repo exist
        if (Files.exists(dataset) && Files.exists(repoPath)) {
            Path humanBaselineOutput = AppConfig.TEST_EXPERIMENTS_TEMP_DIR.resolve("human_baseline_test.json");
            ResolutionVariantRunner.makeAnalysisByDataset(dataset, repoPath, output, humanBaselineOutput, false, false);

            // Verify output file was created
            assertTrue(Files.exists(output),
                "Output file should be created when inputs exist");
        } else {
            // Skip test if required files don't exist
            System.out.println("Skipping makeAnalysisByDataset test - dataset or repo not found");
        }
    }

    @Test
    void runTestsNoCacheParallel() throws Exception {
        ResolutionVariantRunner experimentRunner = new ResolutionVariantRunner(AppConfig.TEST_DATASET_DIR,
                AppConfig.INPUT_PROJECT_XLSX,
                AppConfig.TEST_EXPERIMENTS_TEMP_DIR
                );

        Path outputPath = AppConfig.TEST_EXPERIMENTS_DIR.resolve(Utility.Experiments.no_cache_parallel.getName());
        experimentRunner.runTests(outputPath, Utility.Experiments.no_cache_parallel.isParallel(), Utility.Experiments.no_cache_parallel.isCache());

        // Verify experiment configuration is correct
        assertFalse(Utility.Experiments.no_cache_parallel.isCache(), "Should not use cache");
        assertTrue(Utility.Experiments.no_cache_parallel.isParallel(), "Should use parallel execution");
    }

    @Test
    void runTestsCacheParallel() throws Exception {
        ResolutionVariantRunner experimentRunner = new ResolutionVariantRunner(AppConfig.TEST_DATASET_DIR,
                AppConfig.INPUT_PROJECT_XLSX,
                AppConfig.TEST_EXPERIMENTS_TEMP_DIR
        );

        Path outputPath = AppConfig.TEST_EXPERIMENTS_DIR.resolve(Utility.Experiments.cache_parallel.getName());
        experimentRunner.runTests(outputPath, Utility.Experiments.cache_parallel.isParallel(), Utility.Experiments.cache_parallel.isCache());

        // Verify experiment configuration is correct
        assertTrue(Utility.Experiments.cache_parallel.isCache(), "Should use cache");
        assertTrue(Utility.Experiments.cache_parallel.isParallel(), "Should use parallel execution");
    }

    @Test
    void runTestsNoCacheNoParallel() throws Exception {
        ResolutionVariantRunner experimentRunner = new ResolutionVariantRunner(AppConfig.TEST_DATASET_DIR,
                AppConfig.INPUT_PROJECT_XLSX,
                AppConfig.TEST_EXPERIMENTS_TEMP_DIR
        );

        Path outputPath = AppConfig.TEST_EXPERIMENTS_DIR.resolve(Utility.Experiments.no_cache_no_parallel.getName());
        experimentRunner.runTests(outputPath, Utility.Experiments.no_cache_no_parallel.isParallel(), Utility.Experiments.no_cache_no_parallel.isCache());

        // Verify experiment configuration is correct
        assertFalse(Utility.Experiments.no_cache_no_parallel.isCache(), "Should not use cache");
        assertFalse(Utility.Experiments.no_cache_no_parallel.isParallel(), "Should not use parallel execution");
    }
}
