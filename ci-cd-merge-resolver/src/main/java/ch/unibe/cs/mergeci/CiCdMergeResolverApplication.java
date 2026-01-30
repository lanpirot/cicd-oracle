package ch.unibe.cs.mergeci;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.experimentSetup.MetricsAnalyzer;
import ch.unibe.cs.mergeci.experimentSetup.RepoCollector;
import ch.unibe.cs.mergeci.experimentSetup.evaluationCollection.ExperimentRunner;
import ch.unibe.cs.mergeci.util.GitUtils;
import ch.unibe.cs.mergeci.util.Utility;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.Sequence;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.merge.MergeChunk;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.merge.ResolveMerger;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;

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
