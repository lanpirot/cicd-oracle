package ch.unibe.cs.mergeci;

import ch.unibe.cs.mergeci.experimentSetup.MetricsAnalyzer;
import ch.unibe.cs.mergeci.experimentSetup.RepoCollector;
import ch.unibe.cs.mergeci.experimentSetup.evaluationCollection.ExperimentRunner;
import ch.unibe.cs.mergeci.util.GitUtils;
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
        File project = new File("src/test/resources/test-merge-projects/myTest");

        //collect();
        //generateMergeVariants();
        analyzeResults();
    }

    private static void analyzeResults() {
        MetricsAnalyzer metricsAnalyzer = new MetricsAnalyzer(new File("experiments/results_wo_optimization"));
        metricsAnalyzer.makeFullAnalysis();
    }

    private static void generateMergeVariants() {
        ExperimentRunner experimentRunner = new ExperimentRunner(
                new File("repoCollector/datasets/"), // directory with dataset that were collected by `RepoCollector`
                new File("experiments/projects_Java_desc-stars-1000.xlsx"), // Excel file with list of repositories
                new File("/home/lanpirot/tmp/bruteforce") // temporary working directory
        );

        try {
            experimentRunner.runTests(new File("experiments/results_cache_optimization"), false); // output directory, build cache optimization flag
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void collect() {
        RepoCollector collector =
                new RepoCollector(
                        "/home/lanpirot/data/brutforcemerge/repos",   // name of directory to clone projects
                        "/home/lanpirot/tmp/bruteforcemerge",    // temporary working directory
                        578,         // start row
                        1000        // end row
                );
        // File with list of java projects and their repo URL
        try {
            collector.processExcel(new File("experiments/projects_Java_desc-stars-1000.xlsx"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}
