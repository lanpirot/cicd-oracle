package ch.unibe.cs.mergeci.experimentSetup.evaluationCollection;

import org.apache.commons.math3.analysis.function.Exp;
import org.junit.jupiter.api.Test;

import java.io.File;

class ExperimentRunnerTest {

    @Test
    void makeAnalysisByDataset() throws Exception {
        File dataset = new File("dataset-test.xlsx");
        File output = new File("dataset-test-otuput_mvnd.json");
        ExperimentRunner.makeAnalysisByDataset(dataset,
                new File("src\\test\\resources\\test-merge-projects\\jackson-databind"),
                output);
    }

    @Test
    void runTests() throws Exception {
        ExperimentRunner experimentRunner = new ExperimentRunner(new File("experiments/datasets"),
                new File("experiments/projects_Java_desc-stars-1000.xlsx"),
                new File("experiments/temp")
                );

        experimentRunner.runTests(new File("experiments/results_wo_optimization"));

    }
}