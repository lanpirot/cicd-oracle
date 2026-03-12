package ch.unibe.cs.mergeci;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.experimentSetup.ResultsPresenter;
import ch.unibe.cs.mergeci.experimentSetup.RepoCollector;
import ch.unibe.cs.mergeci.experimentSetup.evaluationCollection.ResolutionVariantRunner;
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

    private static void collect() {
        RepoCollector collector = new RepoCollector(AppConfig.REPO_DIR, AppConfig.TMP_DIR, AppConfig.DATASET_DIR);
        try {
            collector.processExcel(AppConfig.INPUT_PROJECT_XLSX);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void generateMergeVariants() {
        ResolutionVariantRunner variantRunner = new ResolutionVariantRunner(
                AppConfig.DATASET_DIR,
                AppConfig.INPUT_PROJECT_XLSX,
                AppConfig.TMP_DIR
        );

        for (Utility.Experiments ex : Utility.Experiments.values()) {
            try {
                variantRunner.runTests(AppConfig.EXPERIMENTS_DIR.resolve(ex.getName()), ex.isParallel(), ex.isCache());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static void analyzeResults() {
        for (Utility.Experiments ex : Utility.Experiments.values()){
            ResultsPresenter resultsPresenter = new ResultsPresenter(AppConfig.EXPERIMENTS_DIR.resolve(ex.getName()));
            resultsPresenter.presentFullResults();
        }
    }
}
