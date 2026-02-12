package ch.unibe.cs.mergeci.experimentSetup.coverageCalculater;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.util.Utility;
import org.junit.jupiter.api.Test;

import java.io.File;

public class CoverageCalculatorTest {

    @Test
    void calculateCoverage() {
        for (Utility.Experiments ex : Utility.Experiments.values()) {
            CoverageCalculator coverageCalculator = new CoverageCalculator(
                    AppConfig.TEST_EXPERIMENTS_DIR.resolve(ex.getName()).toFile(),
                    AppConfig.INPUT_PROJECT_XLSX.toFile(),
                    AppConfig.TEST_DATASET_DIR.toFile(),
                    AppConfig.TEST_REPO_DIR.toFile());
            coverageCalculator.calculateCoverage(AppConfig.TEST_COVERAGE_DIR.resolve(ex.getName()).toFile());
        }
    }
}