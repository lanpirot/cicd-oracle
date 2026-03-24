package ch.unibe.cs.mergeci.runner;

import ch.unibe.cs.mergeci.BaseTest;
import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.runner.maven.CompilationResult;
import ch.unibe.cs.mergeci.runner.maven.TestTotal;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class VariantProjectBuilderTest extends BaseTest {

    // myTest merge commit and its two parents (fast build, < 5s)
    private static final String MERGE  = "f8b010dd54b018c868ebfc061da31296c3fba6f4";
    private static final String PARENT1 = "c1317ffdd3795f49ba2d0771464e5fec7b858f9d";
    private static final String PARENT2 = "8d26e4bdde76972c121f04fe025ae636394cbb63";

    @Test
    void buildProjectsAndRunTests() throws Exception {
        VariantProjectBuilder variantProjectBuilder = new VariantProjectBuilder(
                AppConfig.TEST_REPO_DIR.resolve(AppConfig.myTest),
                AppConfig.TEST_TMP_DIR,
                AppConfig.TEST_EXPERIMENTS_TEMP_DIR
        );

        // null mergeId: myTest is not in Java_chunks.csv, so no ML-AR predictions
        VariantBuildContext context = variantProjectBuilder.prepareVariants(PARENT1, PARENT2, MERGE, (String) null);

        MavenExecutionFactory factory = new MavenExecutionFactory(variantProjectBuilder.getLogDir());
        IJustInTimeRunner runner = factory.createJustInTimeRunner(true, false);

        variantProjectBuilder.runTestsJustInTime(context, runner);

        Map<String, CompilationResult> compilationResultMap = factory.getCompilationResults();
        assertNotNull(compilationResultMap, "Compilation result map should not be null");
        assertFalse(compilationResultMap.isEmpty(), "Should have at least one compilation result");
        for (Map.Entry<String, CompilationResult> entry : compilationResultMap.entrySet()) {
            assertNotNull(entry.getKey(), "Project name should not be null");
            assertNotNull(entry.getValue(), "Compilation result should not be null");
        }

        Map<String, TestTotal> testTotalMap = factory.getTestResults();
        assertNotNull(testTotalMap, "Test result map should not be null");
        assertFalse(testTotalMap.isEmpty(), "Should have at least one test result");
        for (Map.Entry<String, TestTotal> entry : testTotalMap.entrySet()) {
            assertNotNull(entry.getKey(), "Project name should not be null");
            assertNotNull(entry.getValue(), "Test total should not be null");
        }
    }
}
