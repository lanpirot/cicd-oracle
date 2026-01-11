package ch.unibe.cs.mergeci.experimentSetup.coverageCalculater;

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
                new File("coverageTemp/datasets"),
                new File("experiments/projects_Java_desc-stars-1000.xlsx"),
                new File("dataset_temp"),
                new File("coverageTemp"));

        coverageCalculator.calculateCoverage(new File("coverageTemp/results2"));
    }
}