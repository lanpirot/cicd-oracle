package ch.unibe.cs.mergeci;

import ch.unibe.cs.mergeci.config.AppConfig;
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
        new RepoCollector().processCsv();
    }

    private static void generateMergeVariants() {
        ResolutionVariantRunner variantRunner = new ResolutionVariantRunner();
        for (Utility.Experiments ex : Utility.Experiments.values()) {
            variantRunner.runTests(ex);
        }
    }

    private static void analyzeResults() {
        // Console summary (all modes)
        for (Utility.Experiments ex : Utility.Experiments.values()) {
            new ResultsPresenter(ex).presentFullResults();
        }
        // Paper-ready PDF with LaTeX fonts (Python presentation layer)
        generatePdfPlots();
    }

    /**
     * Invoke the Python presentation script to produce a paper-ready PDF.
     * Python is the single source of truth for all visual output; Java keeps
     * StatisticsReporter only for quick console/development checks.
     */
    private static void generatePdfPlots() {
        try {
            System.out.printf("%nGenerating paper-ready PDF: %s%n", AppConfig.PLOTS_OUTPUT_PDF);
            Process p = new ProcessBuilder(
                    "python3",
                    AppConfig.PLOT_SCRIPT.toString(),
                    AppConfig.VARIANT_EXPERIMENT_DIR.toString(),
                    AppConfig.PLOTS_OUTPUT_PDF.toString()
            ).inheritIO().start();
            int exit = p.waitFor();
            if (exit != 0) {
                System.err.printf("Warning: plot script exited with code %d%n", exit);
            }
        } catch (Exception e) {
            System.err.println("Warning: could not generate PDF plots: " + e.getMessage());
        }
    }
}
