package ch.unibe.cs.mergeci.experimentSetup.coverageCalculater;

import ch.unibe.cs.mergeci.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

class CoverageCalculatorTest {

    @Test
    void calculateCoverage() {
        CoverageCalculator coverageCalculator = new CoverageCalculator(
                new File(AppConfig.TEST_COVERAGE_DIR, "datasets"),
                new File(AppConfig.TEST_EXPERIMENTS_DIR + "projects_Java_desc-stars-1000.xlsx"),
                AppConfig.TEST_DATASET_TEMP_DIR,
                AppConfig.TEST_COVERAGE_DIR);

        coverageCalculator.calculateCoverage(new File(AppConfig.TEST_COVERAGE_DIR, "results2"));
    }
}