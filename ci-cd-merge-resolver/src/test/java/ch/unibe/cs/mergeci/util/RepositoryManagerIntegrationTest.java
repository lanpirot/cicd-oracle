package ch.unibe.cs.mergeci.util;

import ch.unibe.cs.mergeci.config.AppConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for the RepositoryManager that simulates the complete pipeline
 * running twice with both Maven and non-Maven projects.
 */
class RepositoryManagerIntegrationTest {

    @TempDir
    Path tempDir;
    
    private Path repoBaseDir;
    private RepositoryManager repoManager;
    
    // Test repository URLs (these would be mock URLs in a real test)
    private static final String MAVEN_REPO_NAME = "test-maven-project";
    private static final String MAVEN_REPO_URL = "https://github.com/test/test-maven-project.git";
    
    private static final String NON_MAVEN_REPO_NAME = "test-non-maven-project";
    private static final String NON_MAVEN_REPO_URL = "https://github.com/test/test-non-maven-project.git";
    
    @BeforeEach
    void setUp() {
        repoBaseDir = tempDir.resolve("repos");
        repoManager = new RepositoryManager(repoBaseDir);
    }
    
    @AfterEach
    void tearDown() throws IOException {
        // Clean up test directories
        Files.walk(repoBaseDir)
                .sorted((a, b) -> -a.compareTo(b)) // reverse order
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        // Ignore cleanup errors
                    }
                });
    }
    
    /**
     * Test the complete pipeline simulation:
     * 1. First run: download both repos, reject non-Maven, keep Maven
     * 2. Second run: no downloads, verify structure
     */
    @Test
    void testCompletePipelineTwoRuns() throws Exception {
        System.out.println("=== Starting Repository Manager Integration Test ===");
        
        // ===== FIRST RUN =====
        System.out.println("\n=== FIRST RUN ===");
        
        // Simulate first run - create mock repositories
        simulateFirstRun();
        
        // Verify first run results
        verifyFirstRunResults();
        
        // ===== SECOND RUN =====
        System.out.println("\n=== SECOND RUN ===");
        
        // Create new RepositoryManager (simulating restart)
        RepositoryManager repoManagerSecondRun = new RepositoryManager(repoBaseDir);
        
        // Verify second run behavior
        verifySecondRunBehavior(repoManagerSecondRun);
        
        // ===== FINAL VERIFICATION =====
        System.out.println("\n=== FINAL VERIFICATION ===");
        
        verifyFinalDirectoryStructure();
        verifyStatusFileContents();
        
        System.out.println("=== Test Completed Successfully ===");
    }
    
    /**
     * Simulate the first run of the pipeline
     */
    private void simulateFirstRun() throws IOException {
        // Create mock Maven repository
        Path mavenRepoPath = createMockMavenRepository();
        
        // Create mock non-Maven repository  
        Path nonMavenRepoPath = createMockNonMavenRepository();
        
        // Simulate processing: mark Maven as success, non-Maven as rejected
        repoManager.markRepositorySuccess(MAVEN_REPO_NAME);
        repoManager.markRepositoryRejected(NON_MAVEN_REPO_NAME, RepositoryStatus.REJECTED_NO_POM);
        
        System.out.println("First run completed:");
        System.out.println("- Maven repo created and marked as SUCCESS");
        System.out.println("- Non-Maven repo created and marked as REJECTED_NO_POM");
    }
    
    /**
     * Create a mock Maven repository with pom.xml
     */
    private Path createMockMavenRepository() throws IOException {
        Path repoPath = repoBaseDir.resolve(MAVEN_REPO_NAME);
        Files.createDirectories(repoPath);
        
        // Create pom.xml
        String pomContent = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n" +
                "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
                "         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0\n" +
                "         http://maven.apache.org/xsd/maven-4.0.0.xsd\">\n" +
                "    <modelVersion>4.0.0</modelVersion>\n" +
                "    <groupId>test</groupId>\n" +
                "    <artifactId>test-maven-project</artifactId>\n" +
                "    <version>1.0.0</version>\n" +
                "</project>";
        
        Files.write(repoPath.resolve(AppConfig.POMXML), pomContent.getBytes());
        
        // Create some source files
        Files.createDirectories(repoPath.resolve("src/main/java/test"));
        Files.write(repoPath.resolve("src/main/java/test/App.java"),
                "package test;\npublic class App { public static void main(String[] args) { System.out.println(\"Hello\"); } }".getBytes());
        
        // Create .git directory to simulate a real repo
        Files.createDirectories(repoPath.resolve(".git"));
        Files.write(repoPath.resolve(".git/config"), "[core]\nrepositoryformatversion = 0\n".getBytes());
        
        System.out.println("Created mock Maven repository: " + repoPath);
        return repoPath;
    }
    
    /**
     * Create a mock non-Maven repository (no pom.xml)
     */
    private Path createMockNonMavenRepository() throws IOException {
        Path repoPath = repoBaseDir.resolve(NON_MAVEN_REPO_NAME);
        Files.createDirectories(repoPath);
        
        // Create some non-Java files
        Files.write(repoPath.resolve("README.md"), "# Non-Maven Project\nThis is not a Maven project.".getBytes());
        Files.write(repoPath.resolve("script.py"), "print('Hello from Python')\n".getBytes());
        
        // Create .git directory to simulate a real repo
        Files.createDirectories(repoPath.resolve(".git"));
        Files.write(repoPath.resolve(".git/config"), "[core]\nrepositoryformatversion = 0\n".getBytes());
        
        System.out.println("Created mock non-Maven repository: " + repoPath);
        return repoPath;
    }
    
    /**
     * Verify results after first run
     */
    private void verifyFirstRunResults() throws IOException {
        System.out.println("Verifying first run results...");
        
        // Check Maven repository exists and has content
        Path mavenRepoPath = repoBaseDir.resolve(MAVEN_REPO_NAME);
        assertTrue(Files.exists(mavenRepoPath), "Maven repository should exist");
        assertTrue(Files.isDirectory(mavenRepoPath), "Maven repository should be a directory");
        assertTrue(Files.exists(mavenRepoPath.resolve(AppConfig.POMXML)), "Maven repository should have pom.xml");
        assertTrue(Files.exists(mavenRepoPath.resolve(".git")), "Maven repository should have .git directory");
        
        // Check non-Maven repository exists but should be empty (after rejection)
        Path nonMavenRepoPath = repoBaseDir.resolve(NON_MAVEN_REPO_NAME);
        assertTrue(Files.exists(nonMavenRepoPath), "Non-Maven repository directory should exist");
        assertTrue(Files.isDirectory(nonMavenRepoPath), "Non-Maven repository should be a directory");
        
        // After rejection, the directory should be empty (except status file)
        long fileCount = Arrays.stream(nonMavenRepoPath.toFile().listFiles())
                .filter(file -> !file.getName().equals(".repo_status.json"))
                .count();
        
        assertEquals(0, fileCount, "Non-Maven repository should be empty after rejection");
        System.out.println("Non-Maven repo file count after rejection: " + fileCount);
        
        // Check status file exists and has content
        Path statusFile = repoBaseDir.resolve(".repo_status.json");
        assertTrue(Files.exists(statusFile), "Status file should exist");
        assertTrue(Files.size(statusFile) > 0, "Status file should not be empty");
        
        String statusContent = Files.readString(statusFile);
        assertTrue(statusContent.contains(MAVEN_REPO_NAME), "Status file should contain Maven repo reference");
        assertTrue(statusContent.contains(NON_MAVEN_REPO_NAME), "Status file should contain non-Maven repo reference");
        assertTrue(statusContent.contains("SUCCESS"), "Status file should contain SUCCESS status");
        assertTrue(statusContent.contains("REJECTED_NO_POM"), "Status file should contain REJECTED_NO_POM status");
        
        // Check statuses
        assertEquals(RepositoryStatus.SUCCESS, repoManager.getRepositoryStatus(MAVEN_REPO_NAME), 
                "Maven repository should be marked as SUCCESS");
        assertEquals(RepositoryStatus.REJECTED_NO_POM, repoManager.getRepositoryStatus(NON_MAVEN_REPO_NAME), 
                "Non-Maven repository should be marked as REJECTED_NO_POM");
        
        // Verify download decisions
        assertFalse(repoManager.shouldDownloadRepository(MAVEN_REPO_NAME),
                "Successful repository should not need download");
        assertFalse(repoManager.shouldDownloadRepository(NON_MAVEN_REPO_NAME),
                "Rejected repository should not need download");
        
        System.out.println("✓ First run verification passed");
    }
    
    /**
     * Verify second run behavior (no downloads)
     */
    private void verifySecondRunBehavior(RepositoryManager repoManagerSecondRun) {
        System.out.println("Verifying second run behavior...");
        
        // Check that both repositories are marked as not needing download
        assertFalse(repoManagerSecondRun.shouldDownloadRepository(MAVEN_REPO_NAME),
                "Maven repository should not need download on second run");
        assertFalse(repoManagerSecondRun.shouldDownloadRepository(NON_MAVEN_REPO_NAME),
                "Non-Maven repository should not need download on second run");
        
        // Verify statuses are preserved
        assertEquals(RepositoryStatus.SUCCESS, repoManagerSecondRun.getRepositoryStatus(MAVEN_REPO_NAME),
                "Maven repository status should be preserved");
        assertEquals(RepositoryStatus.REJECTED_NO_POM, repoManagerSecondRun.getRepositoryStatus(NON_MAVEN_REPO_NAME),
                "Non-Maven repository status should be preserved");
        
        // Verify repository directories still exist
        Path mavenRepoPath = repoBaseDir.resolve(MAVEN_REPO_NAME);
        Path nonMavenRepoPath = repoBaseDir.resolve(NON_MAVEN_REPO_NAME);
        assertTrue(Files.exists(mavenRepoPath), "Maven repository should still exist on second run");
        assertTrue(Files.exists(nonMavenRepoPath), "Non-Maven repository marker should still exist on second run");
        
        // Verify Maven repository still has its content
        assertTrue(Files.exists(mavenRepoPath.resolve(AppConfig.POMXML)), 
                "Maven repository should still have pom.xml on second run");
        
        // Verify non-Maven repository is still empty
        long nonMavenFileCount = Arrays.stream(nonMavenRepoPath.toFile().listFiles())
                .filter(file -> !file.getName().equals(".repo_status.json"))
                .count();
        assertEquals(0, nonMavenFileCount, "Non-Maven repository should still be empty on second run");
        
        System.out.println("✓ Second run behavior verification passed");
    }
    
    /**
     * Verify final directory structure
     */
    private void verifyFinalDirectoryStructure() {
        System.out.println("Verifying final directory structure...");
        
        // Maven repo should still have full content
        Path mavenRepoPath = repoBaseDir.resolve(MAVEN_REPO_NAME);
        assertTrue(Files.exists(mavenRepoPath.resolve(AppConfig.POMXML)), 
                "Maven repository should still have pom.xml");
        assertTrue(Files.exists(mavenRepoPath.resolve("src/main/java/test/App.java")), 
                "Maven repository should still have source files");
        
        // Non-Maven repo should be empty (rejected)
        Path nonMavenRepoPath = repoBaseDir.resolve(NON_MAVEN_REPO_NAME);
        assertTrue(Files.exists(nonMavenRepoPath), "Non-Maven repository directory should still exist");
        
        // Count files in non-Maven repo (should be minimal)
        long nonMavenFileCount = Arrays.stream(nonMavenRepoPath.toFile().listFiles())
                .filter(file -> !file.getName().equals(".repo_status.json"))
                .count();
        
        System.out.println("Final non-Maven repo file count: " + nonMavenFileCount);
        
        System.out.println("✓ Final directory structure verification passed");
    }
    
    /**
     * Verify status file contents
     */
    private void verifyStatusFileContents() throws IOException {
        System.out.println("Verifying status file contents...");
        
        Path statusFile = repoBaseDir.resolve(".repo_status.json");
        assertTrue(Files.exists(statusFile), "Status file should exist");
        
        String statusContent = Files.readString(statusFile);
        System.out.println("Status file content:");
        System.out.println(statusContent);
        
        // Verify it contains both repositories
        assertTrue(statusContent.contains(MAVEN_REPO_NAME), "Status file should contain Maven repo");
        assertTrue(statusContent.contains(NON_MAVEN_REPO_NAME), "Status file should contain non-Maven repo");
        assertTrue(statusContent.contains("SUCCESS"), "Status file should contain SUCCESS status");
        assertTrue(statusContent.contains("REJECTED_NO_POM"), "Status file should contain REJECTED_NO_POM status");
        
        System.out.println("✓ Status file verification passed");
    }
    
    /**
     * Test the rejection marking specifically
     */
    @Test
    void testRejectionMarking() throws IOException {
        System.out.println("=== Testing Rejection Marking ===");
        
        // Create a test repository
        Path testRepoPath = repoBaseDir.resolve("test-rejection");
        Files.createDirectories(testRepoPath);
        Files.write(testRepoPath.resolve("file1.txt"), "content1".getBytes());
        Files.write(testRepoPath.resolve("file2.txt"), "content2".getBytes());
        Files.createDirectories(testRepoPath.resolve("subdir"));
        Files.write(testRepoPath.resolve("subdir/file3.txt"), "content3".getBytes());
        
        // Mark as rejected
        repoManager.markRepositoryRejected("test-rejection", RepositoryStatus.REJECTED_NO_POM);
        
        // Verify directory still exists but is empty
        assertTrue(Files.exists(testRepoPath), "Rejected repository directory should still exist");
        assertTrue(Files.isDirectory(testRepoPath), "Rejected repository should still be a directory");
        
        // Check that files are deleted
        assertFalse(Files.exists(testRepoPath.resolve("file1.txt")), "Rejected repo files should be deleted");
        assertFalse(Files.exists(testRepoPath.resolve("file2.txt")), "Rejected repo files should be deleted");
        assertFalse(Files.exists(testRepoPath.resolve("subdir/file3.txt")), "Rejected repo subdirectory files should be deleted");
        assertFalse(Files.exists(testRepoPath.resolve("subdir")), "Rejected repo subdirectories should be deleted");
        
        // Verify status
        assertEquals(RepositoryStatus.REJECTED_NO_POM, repoManager.getRepositoryStatus("test-rejection"));
        
        System.out.println("✓ Rejection marking test passed");
    }
    
    /**
     * Test repository path resolution
     */
    @Test
    void testRepositoryPathResolution() throws IOException {
        System.out.println("=== Testing Repository Path Resolution ===");
        
        String testRepoName = "test-path-resolution";
        String testRepoUrl = "https://github.com/test/test-path-resolution.git";
        
        // First call should indicate need for download
        assertTrue(repoManager.shouldDownloadRepository(testRepoName), 
                "Should need download for new repository");
        
        // Create mock repository
        Path testRepoPath = repoBaseDir.resolve(testRepoName);
        Files.createDirectories(testRepoPath);
        Files.write(testRepoPath.resolve(AppConfig.POMXML), "<project></project>".getBytes());
        
        // Mark as success
        repoManager.markRepositorySuccess(testRepoName);
        
        // Second call should not indicate need for download
        assertFalse(repoManager.shouldDownloadRepository(testRepoName), 
                "Should not need download for successful repository");
        
        // Get path should return existing path
        Path resolvedPath = repoManager.getRepositoryPath(testRepoName, testRepoUrl);
        assertEquals(testRepoPath, resolvedPath, "Should return existing repository path");
        
        System.out.println("✓ Repository path resolution test passed");
    }
}