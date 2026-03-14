package ch.unibe.cs.mergeci.runner.maven.strategy;

import ch.unibe.cs.mergeci.BaseTest;
import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.runner.maven.MavenCommandResolver;
import ch.unibe.cs.mergeci.runner.maven.MavenProcessExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests that coverage goals (JaCoCo prepare-agent / test / report) are correctly
 * injected into the standard build when COVERAGE_ACTIVATED is true, and absent
 * when it is false. The coverage logic now lives in AppConfig.mavenGoals() and is
 * exercised through SequentialStrategy.
 */
public class CoverageStrategyTest extends BaseTest {

    private MavenCommandResolver mockCommandResolver;
    private MavenProcessExecutor mockProcessExecutor;
    private SequentialStrategy strategy;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        mockCommandResolver = mock(MavenCommandResolver.class);
        mockProcessExecutor = mock(MavenProcessExecutor.class);
        strategy = new SequentialStrategy(mockCommandResolver, mockProcessExecutor, tempDir.resolve("logs"));
    }

    @AfterEach
    void resetCoverageFlag() {
        System.clearProperty("coverageActivated");
    }

    @Test
    void testCoverageOn_includesJacocoGoals(@TempDir Path tempDir) throws Exception {
        System.setProperty("coverageActivated", "true");
        Path project = Files.createDirectories(tempDir.resolve("project"));
        when(mockCommandResolver.resolveMavenCommand(project)).thenReturn("mvn");

        strategy.execute(project);

        ArgumentCaptor<String[]> goalsCaptor = ArgumentCaptor.forClass(String[].class);
        verify(mockProcessExecutor).executeCommand(eq(project), any(Path.class), goalsCaptor.capture());

        List<String> goals = List.of(goalsCaptor.getValue());
        assertTrue(goals.contains(AppConfig.JACOCO_FULL + ":prepare-agent"), "Missing jacoco:prepare-agent");
        assertTrue(goals.contains("test"), "Missing test goal");
        assertTrue(goals.contains(AppConfig.JACOCO_FULL + ":report"), "Missing jacoco:report");

        // Order: prepare-agent -> test -> report
        int prepIdx = goals.indexOf(AppConfig.JACOCO_FULL + ":prepare-agent");
        int testIdx = goals.indexOf("test");
        int repIdx  = goals.indexOf(AppConfig.JACOCO_FULL + ":report");
        assertTrue(prepIdx < testIdx && testIdx < repIdx, "Goals must appear in order: prepare-agent, test, report");
    }

    @Test
    void testCoverageOff_omitsJacocoGoals(@TempDir Path tempDir) throws Exception {
        System.setProperty("coverageActivated", "false");
        Path project = Files.createDirectories(tempDir.resolve("project"));
        when(mockCommandResolver.resolveMavenCommand(project)).thenReturn("mvn");

        strategy.execute(project);

        ArgumentCaptor<String[]> goalsCaptor = ArgumentCaptor.forClass(String[].class);
        verify(mockProcessExecutor).executeCommand(eq(project), any(Path.class), goalsCaptor.capture());

        List<String> goals = List.of(goalsCaptor.getValue());
        assertTrue(goals.contains("test"), "test goal must always be present");
        assertFalse(goals.contains(AppConfig.JACOCO_FULL + ":prepare-agent"), "jacoco:prepare-agent must be absent");
        assertFalse(goals.contains(AppConfig.JACOCO_FULL + ":report"), "jacoco:report must be absent");
    }

    @Test
    void testCoverageOn_logFileNamedCorrectly(@TempDir Path tempDir) throws Exception {
        System.setProperty("coverageActivated", "true");
        Path project = Files.createDirectories(tempDir.resolve("my-project"));
        when(mockCommandResolver.resolveMavenCommand(any())).thenReturn("mvn");

        strategy.execute(project);

        ArgumentCaptor<Path> logCaptor = ArgumentCaptor.forClass(Path.class);
        verify(mockProcessExecutor).executeCommand(eq(project), logCaptor.capture(), any(String[].class));
        assertTrue(logCaptor.getValue().toString().contains("my-project_compilation"),
                "Log file must follow naming pattern <projectName>_compilation");
    }

    @Test
    void testCoverageOn_emptyProjects() {
        System.setProperty("coverageActivated", "true");
        strategy.execute();
        verifyNoInteractions(mockCommandResolver);
        verifyNoInteractions(mockProcessExecutor);
    }
}
