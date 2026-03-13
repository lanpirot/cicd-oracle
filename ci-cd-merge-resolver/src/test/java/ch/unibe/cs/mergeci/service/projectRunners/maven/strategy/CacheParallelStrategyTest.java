package ch.unibe.cs.mergeci.service.projectRunners.maven.strategy;

import ch.unibe.cs.mergeci.BaseTest;
import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.service.projectRunners.maven.MavenCacheManager;
import ch.unibe.cs.mergeci.service.projectRunners.maven.MavenCommandResolver;
import ch.unibe.cs.mergeci.service.projectRunners.maven.MavenProcessExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for CacheParallelStrategy - validates cache-enabled parallel Maven execution.
 */
class CacheParallelStrategyTest extends BaseTest {

    private MavenCommandResolver mockCommandResolver;
    private MavenProcessExecutor mockProcessExecutor;
    private MavenCacheManager mockCacheManager;
    private CacheParallelStrategy strategy;
    private Path logDir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        mockCommandResolver = mock(MavenCommandResolver.class);
        mockProcessExecutor = mock(MavenProcessExecutor.class);
        mockCacheManager = mock(MavenCacheManager.class);
        logDir = tempDir.resolve("logs");

        strategy = new CacheParallelStrategy(
                mockCommandResolver,
                mockProcessExecutor,
                mockCacheManager,
                logDir
        );
    }

    @Test
    void testExecute_EmptyProjects() {
        // Execute with no projects
        strategy.execute();

        // Verify no interactions
        verifyNoInteractions(mockCommandResolver);
        verifyNoInteractions(mockProcessExecutor);
        verifyNoInteractions(mockCacheManager);
    }

    @Test
    void testExecute_SingleProject(@TempDir Path tempDir) throws Exception {
        // Create a single test project
        Path project = tempDir.resolve("project1");
        Files.createDirectories(project);

        when(mockCommandResolver.resolveMavenCommand(project)).thenReturn("mvn");

        // Execute
        strategy.execute(project);

        // Verify first project is built normally
        verify(mockCacheManager).injectCacheArtifacts(project);
        verify(mockCommandResolver).resolveMavenCommand(project);
        verify(mockProcessExecutor).executeCommand(
                eq(project),
                any(Path.class),
                eq("mvn"),
                eq(AppConfig.MAVEN_FAIL_MODE),
                eq(AppConfig.MAVEN_TEST_FAILURE_IGNORE),
                eq("test")
        );

        // No parallel execution should happen
        verify(mockCacheManager, times(1)).injectCacheArtifacts(any());
    }

    @Test
    void testExecute_MultipleProjects(@TempDir Path tempDir) throws Exception {
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

        // Give some time for parallel execution to complete
        Thread.sleep(100);

        // Verify first project built normally (with cache injection)
        verify(mockCacheManager, atLeastOnce()).injectCacheArtifacts(project1);
        verify(mockProcessExecutor).executeCommand(
                eq(project1),
                any(Path.class),
                eq("mvn"),
                eq(AppConfig.MAVEN_FAIL_MODE),
                eq(AppConfig.MAVEN_TEST_FAILURE_IGNORE),
                eq("test")
        );

        // Verify remaining projects built in parallel with offline mode
        verify(mockCacheManager, atLeastOnce()).injectCacheArtifacts(project2);
        verify(mockCacheManager).copyTargetDirectories(project1.toFile(), project2.toFile());
        verify(mockCacheManager).copyCacheDirectory(project1, project2);
        verify(mockProcessExecutor).executeCommand(
                eq(project2),
                any(Path.class),
                eq("mvn"),
                eq("-o"),  // Offline mode
                eq(AppConfig.MAVEN_FAIL_MODE),
                eq(AppConfig.MAVEN_TEST_FAILURE_IGNORE),
                eq("test")
        );

        verify(mockCacheManager, atLeastOnce()).injectCacheArtifacts(project3);
        verify(mockCacheManager).copyTargetDirectories(project1.toFile(), project3.toFile());
        verify(mockCacheManager).copyCacheDirectory(project1, project3);
        verify(mockProcessExecutor).executeCommand(
                eq(project3),
                any(Path.class),
                eq("mvn"),
                eq("-o"),  // Offline mode
                eq(AppConfig.MAVEN_FAIL_MODE),
                eq(AppConfig.MAVEN_TEST_FAILURE_IGNORE),
                eq("test")
        );
    }

    @Test
    void testExecute_LogFileCreation(@TempDir Path tempDir) throws Exception {
        // Create test projects
        Path project1 = tempDir.resolve("test-project-1");
        Path project2 = tempDir.resolve("test-project-2");
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
                anyString()
        );

        // Check that log files follow naming pattern: projectName_compilation
        assertTrue(logFileCaptor.getAllValues().stream()
                .anyMatch(path -> path.toString().contains("test-project-1_compilation")),
                "Should create log file for project1"
        );
    }

    @Test
    void testGetName() {
        assertEquals("Cache-Parallel", strategy.getName());
    }

    @Test
    void testExecute_FirstProjectFailure(@TempDir Path tempDir) throws Exception {
        // Create projects
        Path project1 = tempDir.resolve("failing-project");
        Path project2 = tempDir.resolve("dependent-project");
        Files.createDirectories(project1);
        Files.createDirectories(project2);

        when(mockCommandResolver.resolveMavenCommand(any())).thenReturn("mvn");

        // Simulate first project failure
        doThrow(new RuntimeException("Build failed"))
                .when(mockProcessExecutor).executeCommand(
                        eq(project1),
                        any(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString()
                );

        // Execute - first project failure should propagate (not caught in buildFirstProject)
        assertThrows(RuntimeException.class, () -> strategy.execute(project1, project2));

        // Verify first project was attempted
        verify(mockProcessExecutor).executeCommand(
                eq(project1),
                any(),
                anyString(),
                anyString(),
                anyString(),
                anyString()
        );

        // Second project should NOT be attempted (execution stopped after first project failure)
        verify(mockCacheManager, never()).copyTargetDirectories(any(), any());
    }

    @Test
    void testExecute_VerifiesOfflineModeForCachedBuilds(@TempDir Path tempDir) throws Exception {
        // Create projects
        Path project1 = tempDir.resolve("source-project");
        Path project2 = tempDir.resolve("cached-project");
        Files.createDirectories(project1);
        Files.createDirectories(project2);

        when(mockCommandResolver.resolveMavenCommand(any())).thenReturn("mvn");

        // Execute
        strategy.execute(project1, project2);
        Thread.sleep(100);

        // Verify first project does NOT use offline mode
        verify(mockProcessExecutor).executeCommand(
                eq(project1),
                any(),
                eq("mvn"),
                eq(AppConfig.MAVEN_FAIL_MODE),
                eq(AppConfig.MAVEN_TEST_FAILURE_IGNORE),
                eq("test")
        );

        // Verify second project DOES use offline mode
        verify(mockProcessExecutor).executeCommand(
                eq(project2),
                any(),
                eq("mvn"),
                eq("-o"),  // Important: offline mode
                eq(AppConfig.MAVEN_FAIL_MODE),
                eq(AppConfig.MAVEN_TEST_FAILURE_IGNORE),
                eq("test")
        );
    }
}
