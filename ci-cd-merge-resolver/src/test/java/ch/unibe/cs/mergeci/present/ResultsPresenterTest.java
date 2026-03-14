package ch.unibe.cs.mergeci.present;

import ch.unibe.cs.mergeci.BaseTest;
import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.experiment.MergeOutputJSON;
import ch.unibe.cs.mergeci.util.Utility;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

public class ResultsPresenterTest extends BaseTest {

    @Test
    void loadAllMerges() {
        ResultsPresenter metricsAnalyzer = new ResultsPresenter(AppConfig.TEST_EXPERIMENTS_DIR.resolve(Utility.Experiments.no_cache_parallel.getName()));
        metricsAnalyzer.presentFullResults();

        // Verify the analysis loaded merges
        List<MergeOutputJSON> merges = metricsAnalyzer.getMerges();
        assertNotNull(merges, "Merges list should not be null");
    }

    @Test
    void makeCacheOptimizationAnalysis() {
        ResultsPresenter metricsAnalyzer = new ResultsPresenter(AppConfig.TEST_EXPERIMENTS_DIR.resolve(Utility.Experiments.cache_parallel.getName()));

        List<MergeOutputJSON> initMerges = metricsAnalyzer.getMerges();
        assertNotNull(initMerges, "Initial merges should not be null");

        List<MergeOutputJSON> merges;
        merges = initMerges.stream().filter(x->x.getCompilationResult().getTotalTime()>0).filter(x->x.getCompilationResult().getTotalTime()<1.1*x.getVariantsExecution().getExecutionTimeSeconds()).
                collect(Collectors.toList());

        System.out.printf(merges.size()+" %n");
        List<MergeOutputJSON> oneModuleMerges = ResultsPresenter.getSingleModuleProjects(merges);
        List<MergeOutputJSON> multiModuleMerges = ResultsPresenter.getMultiModuleProjects(merges);

        assertNotNull(oneModuleMerges, "Single module merges should not be null");
        assertNotNull(multiModuleMerges, "Multi module merges should not be null");

        // Use ExecutionTimeAnalyzer instead of removed static method
        ExecutionTimeAnalyzer timeAnalyzer = new ExecutionTimeAnalyzer();
        Map<Integer, Double> map = timeAnalyzer.calculateExecutionTimeDistribution(merges);
        System.out.printf("Total: %s %n",map);
        System.out.printf("OneModule: %s %n", timeAnalyzer.calculateExecutionTimeDistribution(oneModuleMerges));
        System.out.printf("MultiModule: %s %n", timeAnalyzer.calculateExecutionTimeDistribution(multiModuleMerges));

        assertNotNull(map, "Execution time distribution should not be null");
    }

    @Test
    void withoutOptimization() {
        ResultsPresenter metricsAnalyzer = new ResultsPresenter(AppConfig.TEST_EXPERIMENTS_DIR.resolve(Utility.Experiments.no_cache_no_parallel.getName()));

        List<MergeOutputJSON> merges = metricsAnalyzer.getMerges();
        assertNotNull(merges, "Merges list should not be null");

        System.out.printf(merges.size()+" %n");

        // Use ExecutionTimeAnalyzer instead of removed static method
        ExecutionTimeAnalyzer timeAnalyzer = new ExecutionTimeAnalyzer();
        Map<Integer, Double> map = timeAnalyzer.calculateExecutionTimeDistribution(merges);
        System.out.printf("Total: %s %n",map);

        assertNotNull(map, "Execution time distribution should not be null");
    }
}
