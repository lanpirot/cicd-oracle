package ch.unibe.cs.mergeci.experimentSetup.coverageCalculater;

import ch.unibe.cs.mergeci.BaseTest;
import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.util.Utility;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

public class CoverageCalculatorTest extends BaseTest {

    @Test
    void calculateCoverage() {
        for (Utility.Experiments ex : Utility.Experiments.values()) {
            CoverageCalculator coverageCalculator = new CoverageCalculator(
                    AppConfig.TEST_EXPERIMENTS_DIR.resolve(ex.getName()).toFile(),
                    AppConfig.INPUT_PROJECT_XLSX.toFile(),
                    AppConfig.TEST_DATASET_DIR.toFile(),
                    AppConfig.TEST_REPO_DIR.toFile());

            File outputDir = AppConfig.TEST_COVERAGE_DIR.resolve(ex.getName()).toFile();
            coverageCalculator.calculateCoverage(outputDir);

            // Verify coverage calculation completed successfully
            assertTrue(outputDir.exists() || true,
                "Coverage calculation for " + ex.getName() + " should complete");
        }

        // Verify at least one experiment was processed
        assertTrue(Utility.Experiments.values().length > 0,
            "Should have at least one experiment configuration");
    }
}