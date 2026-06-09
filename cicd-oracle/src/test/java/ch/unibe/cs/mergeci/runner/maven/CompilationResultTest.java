package ch.unibe.cs.mergeci.runner.maven;

import ch.unibe.cs.mergeci.BaseTest;
import ch.unibe.cs.mergeci.config.AppConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
    void parsesQuickMavenFailureAsScanFailure() throws IOException {
        // Fixture: Maven exits immediately with a POM-resolution error — has [INFO] lines but
        // no BUILD SUCCESS/FAILURE/TIMEOUT line.  Must NOT be misclassified as TIMEOUT.
        // (This is the chronicle-queue / jackson-databind SNAPSHOT pattern, plus the
        // common case of merge variants that produce unparseable pom.xml content.)
        CompilationResult result = parse("compilation-result_2.txt");
        assertEquals(CompilationResult.Status.SCAN_FAILURE, result.getBuildStatus(),
                "quick Maven setup failure (no BUILD line, no timeout sentinel) should yield SCAN_FAILURE");
    }

    @Test
    void scanFailureIsNeverSuccess() throws IOException {
        CompilationResult result = parse("compilation-result_2.txt");
        assertNotEquals(CompilationResult.Status.SUCCESS, result.getBuildStatus());
        assertNotEquals(CompilationResult.Status.TIMEOUT, result.getBuildStatus());
    }

    @Test
    void inferModulesFromBuildLinesDetectsPerModuleFailure(@TempDir Path tmp) throws IOException {
        // No Reactor Summary (e.g. mvnd daemon crash) → the fallback line scanner runs.
        // beta hits a COMPILATION ERROR; alpha builds clean.
        String log = String.join("\n",
                "[INFO] Building alpha 1.0-SNAPSHOT [1/3]",
                "[INFO] BUILD-ing alpha sources...",
                "[INFO] Building beta 1.0-SNAPSHOT [2/3]",
                "[ERROR] COMPILATION ERROR",
                "[ERROR] /src/Beta.java:[3,9] cannot find symbol",
                "[INFO] Building gamma 1.0-SNAPSHOT [3/3]",
                "[INFO] gamma built");
        Path file = tmp.resolve("fallback.txt");
        Files.writeString(file, log);

        CompilationResult result = new CompilationResult(file);
        assertEquals(3, result.getTotalModules(), "three Building lines → three modules");
        assertTrue(result.getSuccessfulModules() < 3, "the failing module must not count as successful");
        assertTrue(result.getSuccessfulModules() >= 1, "the clean modules must still count as successful");
    }

    @Test
    void fallbackParserDoesNotBacktrackOnGiantLog(@TempDir Path tmp) throws IOException {
        // Regression guard for the ~3 h ReDoS hang: a large timeout log with many modules
        // and many "[ERROR]" lines that never form a real failure marker. The old per-module
        // "(?s)Building <name>.*?(?:…|\\[ERROR\\].*Failures: …)" pattern backtracked
        // exponentially over this; the linear scanner must finish near-instantly.
        StringBuilder log = new StringBuilder(8 * 1024 * 1024);
        for (int m = 1; m <= 200; m++) {
            log.append("[INFO] Building module").append(m).append(" 1.0-SNAPSHOT [")
               .append(m).append("/200]\n");
            for (int i = 0; i < 500; i++) {
                log.append("[ERROR] downloading artifact attempt ").append(i)
                   .append(" failed: connection reset (not a build failure)\n");
            }
        }
        Path file = tmp.resolve("giant-timeout.txt");
        Files.writeString(file, log.toString());

        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            CompilationResult result = new CompilationResult(file);
            assertEquals(200, result.getTotalModules(), "all modules discovered from Building lines");
            assertEquals(200, result.getSuccessfulModules(),
                    "noise '[ERROR]' lines are not failure markers → every module success");
        });
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
