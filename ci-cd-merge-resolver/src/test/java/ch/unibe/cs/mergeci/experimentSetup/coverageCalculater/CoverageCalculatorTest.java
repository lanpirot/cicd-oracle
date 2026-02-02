package ch.unibe.cs.mergeci.experimentSetup.coverageCalculater;

import ch.unibe.cs.mergeci.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.io.File;

class CoverageCalculatorTest {

    @Test
    void calculateCoverage() {
        CoverageCalculator coverageCalculator = new CoverageCalculator(
                AppConfig.TEST_COVERAGE_DATASETS_DIR,
                AppConfig.INPUT_PROJECT_XLSX,
                AppConfig.TEST_DATASET_DIR,
                AppConfig.TEST_COVERAGE_DIR);

        coverageCalculator.calculateCoverage(AppConfig.TEST_COVERAGE_RESULTS2_DIR);
    }
}