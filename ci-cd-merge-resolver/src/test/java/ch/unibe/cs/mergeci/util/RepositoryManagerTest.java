package ch.unibe.cs.mergeci.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test for RepositoryManager focusing on core functionality
 */
class RepositoryManagerTest {

    @TempDir
    Path tempDir;
    
    private Path repoBaseDir;
    private RepositoryManager repoManager;
    
    @BeforeEach
    void setUp() {
        repoBaseDir = tempDir.resolve("repos");
        repoManager = new RepositoryManager(repoBaseDir);
    }
    
    @AfterEach
    void tearDown() throws IOException {
        // Clean up test directories
        if (Files.exists(repoBaseDir)) {
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
    }
    
    @Test
    void testRepositoryStatusEnum() {
        // Test enum values and methods
        assertTrue(RepositoryStatus.SUCCESS.isSuccessful());
        assertFalse(RepositoryStatus.SUCCESS.isRejected());
        
        assertTrue(RepositoryStatus.REJECTED_NO_POM.isRejected());
        assertFalse(RepositoryStatus.REJECTED_NO_POM.isSuccessful());
        
        assertTrue(RepositoryStatus.REJECTED_CLONE_FAILED.isRejected());
        assertFalse(RepositoryStatus.REJECTED_CLONE_FAILED.isSuccessful());
        
        assertFalse(RepositoryStatus.NOT_PROCESSED.isRejected());
        assertFalse(RepositoryStatus.NOT_PROCESSED.isSuccessful());
    }
    
    @Test
    void testInitialState() {
        // Initially no repositories should need download
        assertEquals(RepositoryStatus.NOT_PROCESSED, 
                repoManager.getRepositoryStatus("test-repo"));
        assertTrue(repoManager.shouldDownloadRepository("test-repo"));
    }
    
    @Test
    void testRejectionMarking() throws IOException {
        String repoName = "test-rejection";
        
        // Create a test repository with files
        Path repoPath = repoBaseDir.resolve(repoName);
        Files.createDirectories(repoPath);
        Files.write(repoPath.resolve("file1.txt"), "content1".getBytes());
        Files.write(repoPath.resolve("file2.txt"), "content2".getBytes());
        Files.createDirectories(repoPath.resolve("subdir"));
        Files.write(repoPath.resolve("subdir/file3.txt"), "content3".getBytes());
        
        // Mark as rejected
        repoManager.markRepositoryRejected(repoName, RepositoryStatus.REJECTED_NO_POM);
        
        // Verify directory still exists but is empty
        assertTrue(Files.exists(repoPath), "Rejected repository directory should still exist");
        assertTrue(Files.isDirectory(repoPath), "Rejected repository should still be a directory");
        
        // Check that files are deleted
        assertFalse(Files.exists(repoPath.resolve("file1.txt")), "Rejected repo files should be deleted");
        assertFalse(Files.exists(repoPath.resolve("file2.txt")), "Rejected repo files should be deleted");
        assertFalse(Files.exists(repoPath.resolve("subdir/file3.txt")), "Rejected repo subdirectory files should be deleted");
        assertFalse(Files.exists(repoPath.resolve("subdir")), "Rejected repo subdirectories should be deleted");
        
        // Verify status
        assertEquals(RepositoryStatus.REJECTED_NO_POM, repoManager.getRepositoryStatus(repoName));
        assertFalse(repoManager.shouldDownloadRepository(repoName), "Rejected repo should not need download");
    }
    
    @Test
    void testSuccessMarking() throws IOException {
        String repoName = "test-success";
        
        // Create a test repository
        Path repoPath = repoBaseDir.resolve(repoName);
        Files.createDirectories(repoPath);
        Files.write(repoPath.resolve("pom.xml"), "<project></project>".getBytes());
        Files.write(repoPath.resolve("src.java"), "public class Test {}".getBytes());
        
        // Mark as success
        repoManager.markRepositorySuccess(repoName);
        
        // Verify repository still exists with all files
        assertTrue(Files.exists(repoPath), "Successful repository directory should still exist");
        assertTrue(Files.exists(repoPath.resolve("pom.xml")), "Successful repository should still have pom.xml");
        assertTrue(Files.exists(repoPath.resolve("src.java")), "Successful repository should still have source files");
        
        // Verify status
        assertEquals(RepositoryStatus.SUCCESS, repoManager.getRepositoryStatus(repoName));
        assertFalse(repoManager.shouldDownloadRepository(repoName), "Successful repo should not need download");
    }
    
    @Test
    void testStatusPersistence() throws IOException {
        // Create directory for repo1 to simulate successful repository
        Path repo1Path = repoBaseDir.resolve("repo1");
        Files.createDirectories(repo1Path);
        Files.write(repo1Path.resolve("pom.xml"), "<project></project>".getBytes());
        
        // Add some repositories with different statuses
        repoManager.markRepositorySuccess("repo1");
        repoManager.markRepositoryRejected("repo2", RepositoryStatus.REJECTED_NO_POM);
        repoManager.markRepositoryRejected("repo3", RepositoryStatus.REJECTED_CLONE_FAILED);
        
        // Create new manager (simulating restart)
        RepositoryManager newManager = new RepositoryManager(repoBaseDir);
        
        // Verify statuses are preserved
        assertEquals(RepositoryStatus.SUCCESS, newManager.getRepositoryStatus("repo1"));
        assertEquals(RepositoryStatus.REJECTED_NO_POM, newManager.getRepositoryStatus("repo2"));
        assertEquals(RepositoryStatus.REJECTED_CLONE_FAILED, newManager.getRepositoryStatus("repo3"));
        
        // Verify download decisions are preserved
        assertFalse(newManager.shouldDownloadRepository("repo1"));
        assertFalse(newManager.shouldDownloadRepository("repo2"));
        assertFalse(newManager.shouldDownloadRepository("repo3"));
    }
    
    @Test
    void testRejectionWithNonExistentDirectory() throws IOException {
        String repoName = "non-existent-rejection";
        
        // Mark non-existent repository as rejected
        repoManager.markRepositoryRejected(repoName, RepositoryStatus.REJECTED_CLONE_FAILED);
        
        // Verify empty directory was created
        Path repoPath = repoBaseDir.resolve(repoName);
        assertTrue(Files.exists(repoPath), "Rejected repository directory should be created");
        assertTrue(Files.isDirectory(repoPath), "Rejected repository should be a directory");
        
        // Verify it's empty
        assertEquals(0, Objects.requireNonNull(repoPath.toFile().listFiles()).length,
                "Rejected repository directory should be empty");
        
        // Verify status
        assertEquals(RepositoryStatus.REJECTED_CLONE_FAILED, repoManager.getRepositoryStatus(repoName));
    }

    @Test
    void testStatusPersistenceWithVerification() throws IOException {
        // Create directory for repo1 to simulate successful repository
        Path repo1Path = repoBaseDir.resolve("repo1");
        Files.createDirectories(repo1Path);
        Files.write(repo1Path.resolve("pom.xml"), "<project></project>".getBytes());
        
        // Add some repositories with different statuses
        repoManager.markRepositorySuccess("repo1");
        repoManager.markRepositoryRejected("repo2", RepositoryStatus.REJECTED_NO_POM);
        repoManager.markRepositoryRejected("repo3", RepositoryStatus.REJECTED_CLONE_FAILED);
        
        // Verify status file was created and contains expected data
        Path statusFile = repoBaseDir.resolve(".repo_status.json");
        assertTrue(Files.exists(statusFile), "Status file should be created");
        
        String statusContent = Files.readString(statusFile);
        assertTrue(statusContent.contains("SUCCESS"), "Status file should contain SUCCESS status");
        assertTrue(statusContent.contains("REJECTED_NO_POM"), "Status file should contain REJECTED_NO_POM status");
        assertTrue(statusContent.contains("REJECTED_CLONE_FAILED"), "Status file should contain REJECTED_CLONE_FAILED status");
        
        // Create new manager (simulating restart)
        RepositoryManager newManager = new RepositoryManager(repoBaseDir);
        
        // Verify statuses are preserved with descriptive assertions
        assertEquals(RepositoryStatus.SUCCESS, newManager.getRepositoryStatus("repo1"),
                "Repository 1 should be marked as SUCCESS after restart");
        assertEquals(RepositoryStatus.REJECTED_NO_POM, newManager.getRepositoryStatus("repo2"),
                "Repository 2 should be marked as REJECTED_NO_POM after restart");
        assertEquals(RepositoryStatus.REJECTED_CLONE_FAILED, newManager.getRepositoryStatus("repo3"),
                "Repository 3 should be marked as REJECTED_CLONE_FAILED after restart");
        
        // Verify download decisions are preserved with clear expectations
        assertFalse(newManager.shouldDownloadRepository("repo1"),
                "Successful repository should not need download on restart");
        assertFalse(newManager.shouldDownloadRepository("repo2"),
                "Rejected repository should not need download on restart");
        assertFalse(newManager.shouldDownloadRepository("repo3"),
                "Failed repository should not need download on restart");
        
        // Verify directory states are correct
        assertTrue(Files.exists(repo1Path), "Successful repository directory should still exist");
        assertTrue(Files.exists(repoBaseDir.resolve("repo2")), "Rejected repository directory should exist");
        assertTrue(Files.exists(repoBaseDir.resolve("repo3")), "Failed repository directory should exist");
    }
    
    @Test
    void testInvalidRejectionStatus() {
        String repoName = "test-invalid";
        
        // Try to mark with non-rejection status
        assertThrows(IllegalArgumentException.class, () -> {
            repoManager.markRepositoryRejected(repoName, RepositoryStatus.SUCCESS);
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            repoManager.markRepositoryRejected(repoName, RepositoryStatus.NOT_PROCESSED);
        });
    }
}