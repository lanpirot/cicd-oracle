package ch.unibe.cs.mergeci.experimentSetup.evaluationCollection;

import ch.unibe.cs.mergeci.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.io.File;

class ExperimentRunnerTest {

    @Test
    void makeAnalysisByDataset() throws Exception {
        File dataset = new File(AppConfig.TEST_EXPERIMENTS_DIR + "dataset-test.xlsx");
        File output = new File(AppConfig.TEST_EXPERIMENTS_DIR + "dataset-test-output_mvnd.json");
        ExperimentRunner.makeAnalysisByDataset(dataset,
                new File(AppConfig.TEST_RESOURCE_DIR.getPath()+"/jackson-databind"),
                output,false, false);
    }

    @Test
    void runTests() throws Exception {
        ExperimentRunner experimentRunner = new ExperimentRunner(new File(AppConfig.TEST_EXPERIMENTS_DIR + "datasets"),
                AppConfig.INPUT_PROJECT_XLSX_TEST,
                new File(AppConfig.TEST_EXPERIMENTS_DIR + "temp")
                );

        experimentRunner.runTests(new File(AppConfig.TEST_EXPERIMENTS_DIR + "results_wo_optimization2"), true,false);
    }

    @Test
    void runTestsWithCache() throws Exception {
        ExperimentRunner experimentRunner = new ExperimentRunner(new File(AppConfig.TEST_EXPERIMENTS_DIR + "datasets"),
                AppConfig.INPUT_PROJECT_XLSX_TEST,
                new File(AppConfig.TEST_EXPERIMENTS_DIR + "temp")
        );

        experimentRunner.runTests(new File(AppConfig.TEST_EXPERIMENTS_DIR + "results_cache_optimization"), true, false);
    }

    @Test
    void runTestsWithoutParallelization() throws Exception {
        ExperimentRunner experimentRunner = new ExperimentRunner(new File(AppConfig.TEST_EXPERIMENTS_DIR + "datasets"),
                AppConfig.INPUT_PROJECT_XLSX_TEST,
                new File(AppConfig.TEST_EXPERIMENTS_DIR + "temp")
        );

        experimentRunner.runTests(new File(AppConfig.TEST_EXPERIMENTS_DIR + "results_without_parallelization"), false, false);
    }
}