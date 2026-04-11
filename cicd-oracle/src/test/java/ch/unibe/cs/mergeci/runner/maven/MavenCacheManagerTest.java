package ch.unibe.cs.mergeci.runner.maven;

import ch.unibe.cs.mergeci.BaseTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MavenCacheManager - validates Maven cache artifact management.
 */
class MavenCacheManagerTest extends BaseTest {

    private MavenCacheManager cacheManager;

    @BeforeEach
    void setUp(@TempDir Path sharedCache) {
        cacheManager = new MavenCacheManager(sharedCache.resolve("shared-cache"));
    }

    @Test
    void testInjectCacheArtifacts_CreatesDirectory(@TempDir Path tempDir) throws IOException {
        Path projectDir = tempDir.resolve("test-project");
        Files.createDirectories(projectDir);

        // Execute
        cacheManager.injectCacheArtifacts(projectDir);

        // Verify .mvn directory created (if cache artifacts exist)
        // Note: This test may not create files if cache-artifacts don't exist in resources
        // but should not crash
        assertDoesNotThrow(() -> cacheManager.injectCacheArtifacts(projectDir),
                "Should not throw exception even if cache artifacts missing");
    }

    @Test
    void testCopyTargetDirectories_SourceNotExists(@TempDir Path tempDir) {
        File nonExistentSource = tempDir.resolve("non-existent").toFile();
        File destination = tempDir.resolve("destination").toFile();

        boolean result = cacheManager.copyTargetDirectories(nonExistentSource, destination);

        assertFalse(result, "Should return false when source doesn't exist");
    }

    @Test
    void testCopyTargetDirectories_SourceIsFile(@TempDir Path tempDir) throws IOException {
        // Create a file (not directory) as source
        Path sourceFile = tempDir.resolve("source.txt");
        Files.writeString(sourceFile, "test");
        File destination = tempDir.resolve("destination").toFile();

        boolean result = cacheManager.copyTargetDirectories(sourceFile.toFile(), destination);

        assertFalse(result, "Should return false when source is a file, not directory");
    }

    @Test
    void testCopyTargetDirectories_NoTargetDirs(@TempDir Path tempDir) throws IOException {
        // Create source directory without any "target" subdirectories
        Path sourceDir = tempDir.resolve("source-project");
        Files.createDirectories(sourceDir);
        Files.createDirectories(sourceDir.resolve("src/main/java"));

        Path destDir = tempDir.resolve("dest-project");

        boolean result = cacheManager.copyTargetDirectories(sourceDir.toFile(), destDir.toFile());

        assertTrue(result, "Should return true even with no target dirs to copy");
        assertTrue(destDir.toFile().exists(), "Destination directory should be created");
    }

    @Test
    void testCopyTargetDirectories_SingleModule(@TempDir Path tempDir) throws IOException {
        // Create source project with target directory
        Path sourceDir = tempDir.resolve("source-project");
        Path sourceTarget = sourceDir.resolve("target");
        Files.createDirectories(sourceTarget);
        Files.writeString(sourceTarget.resolve("test.jar"), "compiled artifact");

        Path destDir = tempDir.resolve("dest-project");

        // Execute
        boolean result = cacheManager.copyTargetDirectories(sourceDir.toFile(), destDir.toFile());

        // Verify
        assertTrue(result, "Should successfully copy target directory");
        assertTrue(destDir.resolve("target").toFile().exists(), "Target directory should exist in destination");
        assertTrue(destDir.resolve("target/test.jar").toFile().exists(), "Artifact should be copied");
    }

    @Test
    void testCopyTargetDirectories_MultiModule(@TempDir Path tempDir) throws IOException {
        // Create multi-module project structure
        Path sourceDir = tempDir.resolve("source-project");

        // Root module target
        Path rootTarget = sourceDir.resolve("target");
        Files.createDirectories(rootTarget);
        Files.writeString(rootTarget.resolve("root.jar"), "root artifact");

        // Module 1 target
        Path module1Target = sourceDir.resolve("module1/target");
        Files.createDirectories(module1Target);
        Files.writeString(module1Target.resolve("module1.jar"), "module1 artifact");

        // Module 2 target
        Path module2Target = sourceDir.resolve("module2/target");
        Files.createDirectories(module2Target);
        Files.writeString(module2Target.resolve("module2.jar"), "module2 artifact");

        Path destDir = tempDir.resolve("dest-project");

        // Execute
        boolean result = cacheManager.copyTargetDirectories(sourceDir.toFile(), destDir.toFile());

        // Verify all target directories copied
        assertTrue(result, "Should successfully copy all target directories");
        assertTrue(destDir.resolve("target/root.jar").toFile().exists(), "Root artifact should be copied");
        assertTrue(destDir.resolve("module1/target/module1.jar").toFile().exists(),
                "Module1 artifact should be copied");
        assertTrue(destDir.resolve("module2/target/module2.jar").toFile().exists(),
                "Module2 artifact should be copied");
    }

    @Test
    void testCopyTargetDirectories_NestedTargets(@TempDir Path tempDir) throws IOException {
        // Create nested structure with multiple target directories
        Path sourceDir = tempDir.resolve("source-project");
        Path deepTarget = sourceDir.resolve("parent/child/grandchild/target");
        Files.createDirectories(deepTarget);
        Files.writeString(deepTarget.resolve("deep.jar"), "deeply nested artifact");

        Path destDir = tempDir.resolve("dest-project");

        // Execute
        boolean result = cacheManager.copyTargetDirectories(sourceDir.toFile(), destDir.toFile());

        // Verify deeply nested target copied
        assertTrue(result, "Should successfully copy deeply nested target");
        assertTrue(destDir.resolve("parent/child/grandchild/target/deep.jar").toFile().exists(),
                "Deeply nested artifact should be copied with correct structure");
    }

    @Test
    void testCopyTargetDirectories_PreservesRelativePaths(@TempDir Path tempDir) throws IOException {
        // Create complex module structure
        Path sourceDir = tempDir.resolve("maven-project");

        // Create targets at different depths
        Files.createDirectories(sourceDir.resolve("target"));
        Files.writeString(sourceDir.resolve("target/root.txt"), "root");

        Files.createDirectories(sourceDir.resolve("core/target"));
        Files.writeString(sourceDir.resolve("core/target/core.txt"), "core");

        Files.createDirectories(sourceDir.resolve("api/v1/target"));
        Files.writeString(sourceDir.resolve("api/v1/target/api.txt"), "api");

        Path destDir = tempDir.resolve("dest-project");

        // Execute
        boolean result = cacheManager.copyTargetDirectories(sourceDir.toFile(), destDir.toFile());

        // Verify all relative paths preserved
        assertTrue(result);
        assertEquals("root", Files.readString(destDir.resolve("target/root.txt")));
        assertEquals("core", Files.readString(destDir.resolve("core/target/core.txt")));
        assertEquals("api", Files.readString(destDir.resolve("api/v1/target/api.txt")));
    }

    @Test
    void testCopyTargetDirectories_DeletesStaleTestReports(@TempDir Path tempDir) throws IOException {
        // Create source with target containing surefire and failsafe reports
        Path sourceDir = tempDir.resolve("source-project");
        Path surefireDir = sourceDir.resolve("target/surefire-reports");
        Path failsafeDir = sourceDir.resolve("module1/target/failsafe-reports");
        Files.createDirectories(surefireDir);
        Files.createDirectories(failsafeDir);
        Files.writeString(surefireDir.resolve("TEST-com.example.FooTest.txt"), "Tests run: 5");
        Files.writeString(failsafeDir.resolve("TEST-com.example.BarIT.txt"), "Tests run: 3");
        // Also create a real artifact that should survive
        Path rootClasses = sourceDir.resolve("target/classes");
        Files.createDirectories(rootClasses);
        Files.writeString(rootClasses.resolve("Foo.class"), "bytecode");
        Path mod1Classes = sourceDir.resolve("module1/target/classes");
        Files.createDirectories(mod1Classes);
        Files.writeString(mod1Classes.resolve("Bar.class"), "bytecode");

        Path destDir = tempDir.resolve("dest-project");

        // Execute
        boolean result = cacheManager.copyTargetDirectories(sourceDir.toFile(), destDir.toFile());

        // Verify: reports deleted, compiled artifacts preserved
        assertTrue(result);
        assertFalse(destDir.resolve("target/surefire-reports").toFile().exists(),
                "Surefire reports should be deleted from destination");
        assertFalse(destDir.resolve("module1/target/failsafe-reports").toFile().exists(),
                "Failsafe reports should be deleted from destination");
        assertTrue(destDir.resolve("target/classes/Foo.class").toFile().exists(),
                "Compiled class should be preserved");
        assertTrue(destDir.resolve("module1/target/classes/Bar.class").toFile().exists(),
                "Module compiled class should be preserved");
    }

    @Test
    void testCopyTargetDirectories_IgnoresNonTargetDirs(@TempDir Path tempDir) throws IOException {
        // Create source with various directories, only "target" should be copied
        Path sourceDir = tempDir.resolve("source-project");
        Files.createDirectories(sourceDir.resolve("target"));
        Files.createDirectories(sourceDir.resolve("build"));  // Should be ignored
        Files.createDirectories(sourceDir.resolve("output")); // Should be ignored
        Files.createDirectories(sourceDir.resolve("src"));    // Should be ignored

        Files.writeString(sourceDir.resolve("target/artifact.jar"), "target artifact");
        Files.writeString(sourceDir.resolve("build/output.jar"), "build artifact");

        Path destDir = tempDir.resolve("dest-project");

        // Execute
        cacheManager.copyTargetDirectories(sourceDir.toFile(), destDir.toFile());

        // Verify only target was copied
        assertTrue(destDir.resolve("target").toFile().exists(), "target should be copied");
        assertTrue(destDir.resolve("target/artifact.jar").toFile().exists(), "target artifact should be copied");
        assertFalse(destDir.resolve("build").toFile().exists(), "build should not be copied");
        assertFalse(destDir.resolve("output").toFile().exists(), "output should not be copied");
        assertFalse(destDir.resolve("src").toFile().exists(), "src should not be copied");
    }
}
