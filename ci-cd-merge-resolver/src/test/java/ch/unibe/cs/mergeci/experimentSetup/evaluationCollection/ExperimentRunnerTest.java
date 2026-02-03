package ch.unibe.cs.mergeci.experimentSetup.evaluationCollection;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.util.Utility;
import org.junit.jupiter.api.Test;

import java.io.File;

public class ExperimentRunnerTest {

    @Test
    void makeAnalysisByDataset() throws Exception {
        File dataset = new File(AppConfig.TEST_DATASET_DIR, AppConfig.jacksonDatabind + AppConfig.XLSX);
        File output = new File(AppConfig.TEST_EXPERIMENTS_DIR, AppConfig.jacksonDatabind + AppConfig.JSON);
        ExperimentRunner.makeAnalysisByDataset(dataset,
                new File(AppConfig.TEST_REPO_DIR, AppConfig.jacksonDatabind),
                output,false, false);
    }

    @Test
    void runTestsNoCacheParallel() throws Exception {
        ExperimentRunner experimentRunner = new ExperimentRunner(AppConfig.TEST_DATASET_DIR,
                AppConfig.INPUT_PROJECT_XLSX,
                AppConfig.TEST_EXPERIMENTS_TEMP_DIR
                );

        experimentRunner.runTests(new File(AppConfig.TEST_EXPERIMENTS_DIR, Utility.Experiments.no_cache_parallel.getName()), Utility.Experiments.no_cache_parallel.isParallel(), Utility.Experiments.no_cache_parallel.isCache());
    }

    @Test
    void runTestsCacheParallel() throws Exception {
        ExperimentRunner experimentRunner = new ExperimentRunner(AppConfig.TEST_DATASET_DIR,
                AppConfig.INPUT_PROJECT_XLSX,
                AppConfig.TEST_EXPERIMENTS_TEMP_DIR
        );

        experimentRunner.runTests(new File(AppConfig.TEST_EXPERIMENTS_DIR, Utility.Experiments.cache_parallel.getName()), Utility.Experiments.cache_parallel.isParallel(), Utility.Experiments.cache_parallel.isCache());
    }

    @Test
    void runTestsNoCacheNoParallel() throws Exception {
        ExperimentRunner experimentRunner = new ExperimentRunner(AppConfig.TEST_DATASET_DIR,
                AppConfig.INPUT_PROJECT_XLSX,
                AppConfig.TEST_EXPERIMENTS_TEMP_DIR
        );

        experimentRunner.runTests(new File(AppConfig.TEST_EXPERIMENTS_DIR, Utility.Experiments.no_cache_no_parallel.getName()), Utility.Experiments.no_cache_no_parallel.isParallel(), Utility.Experiments.no_cache_no_parallel.isCache());
    }
}