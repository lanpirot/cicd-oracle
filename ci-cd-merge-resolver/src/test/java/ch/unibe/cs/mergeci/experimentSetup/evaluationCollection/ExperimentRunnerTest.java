package ch.unibe.cs.mergeci.experimentSetup.evaluationCollection;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.util.Utility;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;

public class ExperimentRunnerTest {

    @Test
    void makeAnalysisByDataset() throws Exception {
        Path dataset = AppConfig.TEST_DATASET_DIR.resolve(AppConfig.jacksonDatabind + AppConfig.XLSX);
        Path repoPath = AppConfig.TEST_REPO_DIR.resolve(AppConfig.jacksonDatabind);
        Path output = AppConfig.TEST_EXPERIMENTS_DIR.resolve( AppConfig.jacksonDatabind + AppConfig.JSON);
        ExperimentRunner.makeAnalysisByDataset(dataset, repoPath, output,false, false);
    }

    @Test
    void runTestsNoCacheParallel() throws Exception {
        ExperimentRunner experimentRunner = new ExperimentRunner(AppConfig.TEST_DATASET_DIR,
                AppConfig.INPUT_PROJECT_XLSX,
                AppConfig.TEST_EXPERIMENTS_TEMP_DIR
                );

        experimentRunner.runTests(AppConfig.TEST_EXPERIMENTS_DIR.resolve(Utility.Experiments.no_cache_parallel.getName()), Utility.Experiments.no_cache_parallel.isParallel(), Utility.Experiments.no_cache_parallel.isCache());
    }

    @Test
    void runTestsCacheParallel() throws Exception {
        ExperimentRunner experimentRunner = new ExperimentRunner(AppConfig.TEST_DATASET_DIR,
                AppConfig.INPUT_PROJECT_XLSX,
                AppConfig.TEST_EXPERIMENTS_TEMP_DIR
        );

        experimentRunner.runTests(AppConfig.TEST_EXPERIMENTS_DIR.resolve(Utility.Experiments.cache_parallel.getName()), Utility.Experiments.cache_parallel.isParallel(), Utility.Experiments.cache_parallel.isCache());
    }

    @Test
    void runTestsNoCacheNoParallel() throws Exception {
        ExperimentRunner experimentRunner = new ExperimentRunner(AppConfig.TEST_DATASET_DIR,
                AppConfig.INPUT_PROJECT_XLSX,
                AppConfig.TEST_EXPERIMENTS_TEMP_DIR
        );

        experimentRunner.runTests(AppConfig.TEST_EXPERIMENTS_DIR.resolve(Utility.Experiments.no_cache_no_parallel.getName()), Utility.Experiments.no_cache_no_parallel.isParallel(), Utility.Experiments.no_cache_no_parallel.isCache());
    }
}