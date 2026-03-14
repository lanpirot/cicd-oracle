package ch.unibe.cs.mergeci;

import ch.unibe.cs.mergeci.experiment.ResolutionVariantRunner;
import ch.unibe.cs.mergeci.present.ResultsPresenter;
import ch.unibe.cs.mergeci.repoCollection.RepoCollector;
import ch.unibe.cs.mergeci.util.Utility;

public class CiCdMergeResolverApplication {

    public static void main(String[] args) {
        collect();
        generateMergeVariants();
        analyzeResults();
    }

    private static void collect() {
        new RepoCollector().processExcel();
    }

    private static void generateMergeVariants() {
        ResolutionVariantRunner variantRunner = new ResolutionVariantRunner();
        for (Utility.Experiments ex : Utility.Experiments.values()) {
            variantRunner.runTests(ex);
        }
    }

    private static void analyzeResults() {
        for (Utility.Experiments ex : Utility.Experiments.values()) {
            new ResultsPresenter(ex).presentFullResults();
        }
    }
}
