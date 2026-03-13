package ch.unibe.cs.mergeci.service.projectRunners.maven.strategy;

import ch.unibe.cs.mergeci.BaseTest;
import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.service.projectRunners.maven.MavenCommandResolver;
import ch.unibe.cs.mergeci.service.projectRunners.maven.MavenProcessExecutor;
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
 * Tests for CoverageStrategy - validates coverage-enabled Maven execution.
 */
class CoverageStrategyTest extends BaseTest {

    private MavenCommandResolver mockCommandResolver;
    private MavenProcessExecutor mockProcessExecutor;
    private CoverageStrategy strategy;
    private Path logDir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        mockCommandResolver = mock(MavenCommandResolver.class);
        mockProcessExecutor = mock(MavenProcessExecutor.class);
        logDir = tempDir.resolve("logs");

        strategy = new CoverageStrategy(
                mockCommandResolver,
                mockProcessExecutor,
                logDir
        );
    }

    @Test
    void testExecute_SingleProject(@TempDir Path tempDir) throws Exception {
        // Create test project
        Path project = tempDir.resolve("test-project");
        Files.createDirectories(project);

        when(mockCommandResolver.resolveMavenCommand(project)).thenReturn("mvn");

        // Execute
        strategy.execute(project);
        Thread.sleep(100);  // Allow parallel execution to complete

        // Verify Maven command executed with Jacoco goals
        verify(mockCommandResolver).resolveMavenCommand(project);
        verify(mockProcessExecutor).executeCommand(
                eq(project),
                any(Path.class),
                eq("mvn"),
                eq(AppConfig.MAVEN_FAIL_MODE),
                eq(AppConfig.MAVEN_TEST_FAILURE_IGNORE),
                eq(AppConfig.JACOCO_FULL + ":prepare-agent"),
                eq("test"),
                eq(AppConfig.JACOCO_FULL + ":report")
        );
    }

    @Test
    void testExecute_MultipleProjectsInParallel(@TempDir Path tempDir) throws Exception {
        // Create multiple test projects
        Path project1 = tempDir.resolve("project1");
        Path project2 = tempDir.resolve("project2");
        Path project3 = tempDir.resolve("project3");
        Files.createDirectories(project1);
        Files.createDirectories(project2);
        Files.createDirectories(project3);

        when(mockCommandResolver.resolveMavenCommand(any())).thenReturn("mvn");

        // Execute
        strategy.execute(project1, project2, project3);
        Thread.sleep(200);  // Allow parallel execution to complete

        // Verify all projects were executed with coverage
        verify(mockProcessExecutor).executeCommand(
                eq(project1),
                any(Path.class),
                eq("mvn"),
                eq(AppConfig.MAVEN_FAIL_MODE),
                eq(AppConfig.MAVEN_TEST_FAILURE_IGNORE),
                eq(AppConfig.JACOCO_FULL + ":prepare-agent"),
                eq("test"),
                eq(AppConfig.JACOCO_FULL + ":report")
        );

        verify(mockProcessExecutor).executeCommand(
                eq(project2),
                any(Path.class),
                eq("mvn"),
                eq(AppConfig.MAVEN_FAIL_MODE),
                eq(AppConfig.MAVEN_TEST_FAILURE_IGNORE),
                eq(AppConfig.JACOCO_FULL + ":prepare-agent"),
                eq("test"),
                eq(AppConfig.JACOCO_FULL + ":report")
        );

        verify(mockProcessExecutor).executeCommand(
                eq(project3),
                any(Path.class),
                eq("mvn"),
                eq(AppConfig.MAVEN_FAIL_MODE),
                eq(AppConfig.MAVEN_TEST_FAILURE_IGNORE),
                eq(AppConfig.JACOCO_FULL + ":prepare-agent"),
                eq("test"),
                eq(AppConfig.JACOCO_FULL + ":report")
        );
    }

    @Test
    void testExecute_EmptyProjects() {
        // Execute with no projects
        strategy.execute();

        // Verify no interactions
        verifyNoInteractions(mockCommandResolver);
        verifyNoInteractions(mockProcessExecutor);
    }

    @Test
    void testExecute_LogFileCreation(@TempDir Path tempDir) throws Exception {
        // Create test projects
        Path project1 = tempDir.resolve("coverage-project-1");
        Path project2 = tempDir.resolve("coverage-project-2");
        Files.createDirectories(project1);
        Files.createDirectories(project2);
        Files.createDirectories(logDir);

        when(mockCommandResolver.resolveMavenCommand(any())).thenReturn("mvn");

        // Capture log file paths
        ArgumentCaptor<Path> logFileCaptor = ArgumentCaptor.forClass(Path.class);

        // Execute
        strategy.execute(project1, project2);
        Thread.sleep(100);

        // Verify log files are created with correct names
        verify(mockProcessExecutor, atLeastOnce()).executeCommand(
                any(Path.class),
                logFileCaptor.capture(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString()
        );

        // Check that log files follow naming pattern: projectName_compilation
        List<Path> logFiles = logFileCaptor.getAllValues();
        assertTrue(logFiles.stream()
                .anyMatch(path -> path.toString().contains("coverage-project-1_compilation")),
                "Should create log file for project1"
        );
        assertTrue(logFiles.stream()
                .anyMatch(path -> path.toString().contains("coverage-project-2_compilation")),
                "Should create log file for project2"
        );
    }

    @Test
    void testGetName() {
        assertEquals("Coverage", strategy.getName());
    }

    @Test
    void testExecute_ProjectFailure(@TempDir Path tempDir) throws Exception {
        // Create project
        Path project = tempDir.resolve("failing-project");
        Files.createDirectories(project);

        when(mockCommandResolver.resolveMavenCommand(any())).thenReturn("mvn");

        // Simulate project failure
        doThrow(new RuntimeException("Coverage collection failed"))
                .when(mockProcessExecutor).executeCommand(
                        eq(project),
                        any(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString()
                );

        // Execute - should not crash, exception is caught and printed
        assertDoesNotThrow(() -> strategy.execute(project));
        Thread.sleep(100);

        // Verify project was attempted
        verify(mockProcessExecutor).executeCommand(
                eq(project),
                any(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString()
        );
    }

    @Test
    void testExecute_VerifiesJacocoGoals(@TempDir Path tempDir) throws Exception {
        // Create project
        Path project = tempDir.resolve("jacoco-test-project");
        Files.createDirectories(project);

        when(mockCommandResolver.resolveMavenCommand(project)).thenReturn("./mvnw");

        // Capture all arguments
        ArgumentCaptor<String> argsCaptor = ArgumentCaptor.forClass(String.class);

        // Execute
        strategy.execute(project);
        Thread.sleep(100);

        // Verify the exact goals are passed
        verify(mockProcessExecutor).executeCommand(
                eq(project),
                any(Path.class),
                eq("./mvnw"),
                argsCaptor.capture(),
                argsCaptor.capture(),
                argsCaptor.capture(),
                argsCaptor.capture(),
                argsCaptor.capture()
        );

        List<String> capturedArgs = argsCaptor.getAllValues();

        // Should have exactly these goals in order
        assertTrue(capturedArgs.contains(AppConfig.JACOCO_FULL + ":prepare-agent"),
                "Should include jacoco:prepare-agent goal");
        assertTrue(capturedArgs.contains("test"),
                "Should include test goal");
        assertTrue(capturedArgs.contains(AppConfig.JACOCO_FULL + ":report"),
                "Should include jacoco:report goal");

        // Verify order: prepare-agent, test, report
        int prepareAgentIndex = capturedArgs.indexOf(AppConfig.JACOCO_FULL + ":prepare-agent");
        int testIndex = capturedArgs.indexOf("test");
        int reportIndex = capturedArgs.indexOf(AppConfig.JACOCO_FULL + ":report");

        assertTrue(prepareAgentIndex >= 0 && testIndex >= 0 && reportIndex >= 0,
                "All goals should be present");
        assertTrue(prepareAgentIndex < testIndex && testIndex < reportIndex,
                "Goals should be in correct order: prepare-agent -> test -> report");
    }

    @Test
    void testExecute_ParallelExecution(@TempDir Path tempDir) throws Exception {
        // Create many projects to test parallel execution
        Path[] projects = new Path[10];
        for (int i = 0; i < 10; i++) {
            projects[i] = tempDir.resolve("project" + i);
            Files.createDirectories(projects[i]);
        }

        when(mockCommandResolver.resolveMavenCommand(any())).thenReturn("mvn");

        // Execute
        long startTime = System.currentTimeMillis();
        strategy.execute(projects);
        Thread.sleep(200);  // Allow parallel execution
        long endTime = System.currentTimeMillis();

        // Verify all projects were executed
        for (Path project : projects) {
            verify(mockProcessExecutor).executeCommand(
                    eq(project),
                    any(Path.class),
                    anyString(),
                    anyString(),
                    anyString(),
                    anyString(),
                    anyString(),
                    anyString()
            );
        }

        // Parallel execution should complete relatively quickly
        // (This is a weak assertion, but validates parallelism to some degree)
        long executionTime = endTime - startTime;
        assertTrue(executionTime < 5000,
                "Parallel execution should complete quickly, took: " + executionTime + "ms");
    }
}
