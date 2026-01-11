package ch.unibe.cs.mergeci.experimentSetup;

import ch.unibe.cs.mergeci.experimentSetup.evaluationCollection.MergeOutputJSON;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

class MetricsAnalyzerTest {

    @Test
    void loadAllMerges() {
        MetricsAnalyzer metricsAnalyzer = new MetricsAnalyzer(new File("analysis/results_wo_optimization"));

        metricsAnalyzer.makeFullAnalysis();
    }

    @Test
    void makeCacheOptimizationAnalysis() {
        MetricsAnalyzer metricsAnalyzer = new MetricsAnalyzer(new File("analysis/results_cache_optimization"));

        List<MergeOutputJSON> initMerges = metricsAnalyzer.getMerges();
        List<MergeOutputJSON> merges;
        merges = initMerges.stream().filter(x->x.getCompilationResult().getTotalTime()>0).filter(x->x.getCompilationResult().getTotalTime()<1.1*x.getVariantsExecution().getExecutionTimeSeconds()).
                collect(Collectors.toList());

        System.out.printf(merges.size()+" %n");
        List<MergeOutputJSON> oneModuleMerges = MetricsAnalyzer.getSingleModuleProjects(merges);
        List<MergeOutputJSON> multiModuleMerges = MetricsAnalyzer.getMultiModuleProjects(merges);

        Map<Integer, Double> map = MetricsAnalyzer.ratioInExecutionTimeDistribution(merges);
        System.out.printf("Total: %s %n",map);
        System.out.printf("OneModule: %s %n",MetricsAnalyzer.ratioInExecutionTimeDistribution(oneModuleMerges));
        System.out.printf("MultiModule: %s %n",MetricsAnalyzer.ratioInExecutionTimeDistribution(multiModuleMerges));
    }

    @Test
    void withoutOptimization() {
        MetricsAnalyzer metricsAnalyzer = new MetricsAnalyzer(new File("analysis/results_without_parallelization"));
        System.out.printf(metricsAnalyzer.getMerges().size()+" %n");
        Map<Integer, Double> map = MetricsAnalyzer.ratioInExecutionTimeDistribution(metricsAnalyzer.getMerges());
        System.out.printf("Total: %s %n",map);
    }
}