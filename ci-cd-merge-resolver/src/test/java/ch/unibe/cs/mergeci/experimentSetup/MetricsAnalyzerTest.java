package ch.unibe.cs.mergeci.experimentSetup;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

class MetricsAnalyzerTest {

    @Test
    void loadAllMerges() {
        MetricsAnalyzer metricsAnalyzer = new MetricsAnalyzer(new File("analysis/results_wo_optimization"));
    }

}