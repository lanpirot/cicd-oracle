package ch.unibe.cs.mergeci.experimentSetup.coverageCalculater;

import ch.unibe.cs.mergeci.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.io.File;

class CoverageCalculatorTest {

    @Test
    void calculateCoverage() {
        CoverageCalculator coverageCalculator = new CoverageCalculator(
                new File(AppConfig.TEST_COVERAGE_DIR, "datasets"),
                new File(AppConfig.TEST_EXPERIMENTS_DIR, "projects_Java_desc-stars-1000.xlsx"),
                AppConfig.TEST_DATASET_DIR,
                AppConfig.TEST_COVERAGE_DIR);

        coverageCalculator.calculateCoverage(new File(AppConfig.TEST_COVERAGE_DIR, "results2"));
    }
}