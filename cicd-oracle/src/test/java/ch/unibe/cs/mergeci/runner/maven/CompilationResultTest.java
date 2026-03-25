package ch.unibe.cs.mergeci.runner.maven;

import ch.unibe.cs.mergeci.BaseTest;
import ch.unibe.cs.mergeci.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

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
        assertFalse(result.getFailedModuleNames().isEmpty(), "should have failed modules");
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
}
