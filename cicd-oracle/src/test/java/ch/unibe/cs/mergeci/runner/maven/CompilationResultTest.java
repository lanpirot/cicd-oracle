package ch.unibe.cs.mergeci.runner.maven;

import ch.unibe.cs.mergeci.BaseTest;
import ch.unibe.cs.mergeci.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class CompilationResultTest extends BaseTest {

    private CompilationResult parse(String filename) throws IOException {
        Path file = AppConfig.TEST_RESOURCE_DIR.resolve(filename);
        return new CompilationResult(file);
    }

    @Test
    void parsesSuccessWithReactorSummary() throws IOException {
        CompilationResult result = parse("compilation-result_1.txt");
        assertEquals(CompilationResult.Status.SUCCESS, result.getBuildStatus());
        assertTrue(result.getTotalModules() > 0, "multi-module project should have modules");
        assertTrue(result.getTotalTime() > 0);
    }

    @Test
    void parsesFailureWithReactorSummary() throws IOException {
        CompilationResult result = parse("compilation-result_3.txt");
        assertEquals(CompilationResult.Status.FAILURE, result.getBuildStatus());
        assertTrue(result.getTotalModules() > 0, "multi-module failure should have modules");
        assertTrue(result.getSuccessfulModules() < result.getTotalModules(), "failure should have fewer successful than total");
    }

    @Test
    void parsesTimeoutSentinelAsTimeout() throws IOException {
        // Fixture: Maven output cut off mid-build + "[INFO] BUILD TIMEOUT" appended by
        // MavenProcessExecutor.handleTimeout(). Must be detected as TIMEOUT, not FAILURE or null.
        CompilationResult result = parse("compilation-result_timeout.txt");
        assertEquals(CompilationResult.Status.TIMEOUT, result.getBuildStatus());
    }

    @Test
    void parsesQuickMavenFailureAsNull() throws IOException {
        // Fixture: Maven exits immediately with a POM-resolution error — has [INFO] lines but
        // no BUILD SUCCESS/FAILURE/TIMEOUT line.  Must NOT be misclassified as TIMEOUT.
        // (This is the chronicle-queue / jackson-databind SNAPSHOT pattern.)
        CompilationResult result = parse("compilation-result_2.txt");
        assertNull(result.getBuildStatus(),
                "quick Maven setup failure (no BUILD line, no timeout sentinel) should yield null status");
    }

    @Test
    void nullStatusIsNeverSuccess() throws IOException {
        CompilationResult result = parse("compilation-result_2.txt");
        assertNotEquals(CompilationResult.Status.SUCCESS, result.getBuildStatus());
        assertNotEquals(CompilationResult.Status.TIMEOUT, result.getBuildStatus());
    }

    @Test
    void singleModuleSuccessInferredAs1Of1() {
        CompilationResult result = CompilationResult.forTest(CompilationResult.Status.SUCCESS, List.of());
        assertEquals(1, result.getTotalModules(), "single-module SUCCESS should report 1 total");
        assertEquals(1, result.getSuccessfulModules(), "single-module SUCCESS should report 1 successful");
    }

    @Test
    void singleModuleFailureInferredAs0Of1() {
        CompilationResult result = CompilationResult.forTest(CompilationResult.Status.FAILURE, List.of());
        assertEquals(1, result.getTotalModules(), "single-module FAILURE should report 1 total");
        assertEquals(0, result.getSuccessfulModules(), "single-module FAILURE should report 0 successful");
    }
}
