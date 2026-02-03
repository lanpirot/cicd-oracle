package ch.unibe.cs.mergeci.experimentSetup.coverageCalculater;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.util.Utility;
import org.junit.jupiter.api.Test;

import java.io.File;

class CoverageCalculatorTest {

    @Test
    void calculateCoverage() {
        for (Utility.Experiments ex : Utility.Experiments.values()) {
            CoverageCalculator coverageCalculator = new CoverageCalculator(
                    new File(AppConfig.TEST_EXPERIMENTS_DIR, ex.getName()),
                    AppConfig.INPUT_PROJECT_XLSX,
                    AppConfig.TEST_DATASET_DIR,
                    AppConfig.TEST_COVERAGE_DIR);
            coverageCalculator.calculateCoverage(new File(AppConfig.TEST_COVERAGE_DIR, ex.getName()));
        }
    }
}