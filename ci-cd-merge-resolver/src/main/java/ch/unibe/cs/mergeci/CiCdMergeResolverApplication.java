package ch.unibe.cs.mergeci;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.experimentSetup.MetricsAnalyzer;
import ch.unibe.cs.mergeci.experimentSetup.RepoCollector;
import ch.unibe.cs.mergeci.experimentSetup.evaluationCollection.ExperimentRunner;
import ch.unibe.cs.mergeci.util.Utility;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.File;

@SpringBootApplication
public class CiCdMergeResolverApplication {

    public static void main(String[] args) {
        SpringApplication.run(CiCdMergeResolverApplication.class, args);

        collect();
        generateMergeVariants();
        analyzeResults();
    }

    private static void analyzeResults() {
        MetricsAnalyzer metricsAnalyzer = new MetricsAnalyzer(new File("experiments/results_wo_optimization"));
        metricsAnalyzer.makeFullAnalysis();
    }

    private static void generateMergeVariants() {
        ExperimentRunner experimentRunner = new ExperimentRunner(
                AppConfig.DATASET_DIR,
                AppConfig.INPUT_PROJECT_XLSX,
                AppConfig.TMP_DIR
        );

        for (Utility.Experiments ex : Utility.Experiments.values()) {
            try {
                experimentRunner.runTests(new File(AppConfig.EXPERIMENTS_DIR + ex.getName()), ex.isParallel(), ex.isCache());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static void collect() {
        RepoCollector collector = new RepoCollector(AppConfig.REPO_DIR.getAbsolutePath(), AppConfig.TMP_DIR.getAbsolutePath());
        try {
            collector.processExcel(AppConfig.INPUT_PROJECT_XLSX);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}
