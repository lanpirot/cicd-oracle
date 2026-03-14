package ch.unibe.cs.mergeci.runner;

import ch.unibe.cs.mergeci.BaseTest;
import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.runner.maven.CompilationResult;
import ch.unibe.cs.mergeci.runner.maven.TestTotal;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class VariantProjectBuilderTest extends BaseTest {

    @Test
    void buildProjectsAndRunTests() throws Exception {
        VariantProjectBuilder variantProjectBuilder = new VariantProjectBuilder(
                AppConfig.TEST_REPO_DIR.resolve(AppConfig.zembereknlp),
                AppConfig.TEST_TMP_DIR,
                AppConfig.TEST_EXPERIMENTS_TEMP_DIR
        );

        // Prepare variants using new API
        VariantBuildContext context = variantProjectBuilder.prepareVariants(
                "c10e035c4b36e0b4cd50e009fb94b67e8fc51a45",
                "356fa0178ca851a1ccee41c7a1846a1a19abbd6b",
                "4b39a3ee35ffcf61f66a783dde2af1d9fbd9c12a"
        );

        // Create just-in-time runner with 10 minute budget
        MavenExecutionFactory factory = new MavenExecutionFactory(variantProjectBuilder.getLogDir());
        IJustInTimeRunner runner = factory.createJustInTimeRunner(true, false);

        // Run tests using just-in-time runner
        variantProjectBuilder.runTestsJustInTime(context, runner);

        System.out.println("Compilation result:");
        Map<String, CompilationResult> compilationResultMap = factory.getCompilationResults();
        compilationResultMap.forEach((k, v) -> {
            System.out.println(k + ": " + v);
        });

        // Verify compilation results were collected
        assertNotNull(compilationResultMap, "Compilation result map should not be null");
        assertFalse(compilationResultMap.isEmpty(), "Should have at least one compilation result");
        for (Map.Entry<String, CompilationResult> entry : compilationResultMap.entrySet()) {
            assertNotNull(entry.getKey(), "Project name should not be null");
            assertNotNull(entry.getValue(), "Compilation result should not be null");
        }

        System.out.println("\n\nTesting result:");
        Map<String, TestTotal> testTotalMap = factory.getTestResults();
        testTotalMap.forEach((k, v) -> {
            System.out.println(k + ": " + v);
        });

        // Verify test results were collected
        assertNotNull(testTotalMap, "Test result map should not be null");
        assertFalse(testTotalMap.isEmpty(), "Should have at least one test result");
        for (Map.Entry<String, TestTotal> entry : testTotalMap.entrySet()) {
            assertNotNull(entry.getKey(), "Project name should not be null");
            assertNotNull(entry.getValue(), "Test total should not be null");
        }
    }
}
