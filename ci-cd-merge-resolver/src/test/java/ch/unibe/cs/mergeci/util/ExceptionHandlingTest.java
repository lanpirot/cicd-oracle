package ch.unibe.cs.mergeci.util;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for exception handling improvements.
 * Verifies that critical methods properly throw/handle exceptions instead of silently failing.
 */
class ExceptionHandlingTest {

    @Test
    void testFileUtils_getFileFromObject_ThrowsOnInvalidObjectId(@TempDir Path tempDir) throws Exception {
        // Create a test repository
        Path repoPath = tempDir.resolve("test-repo");
        Files.createDirectories(repoPath);

        Git git = Git.init().setDirectory(repoPath.toFile()).call();

        // Create an invalid object ID (all zeros)
        ObjectId invalidId = ObjectId.fromString("0000000000000000000000000000000000000000");

        // Should throw IOException, not return null
        assertThrows(IOException.class, () -> {
            FileUtils.getFileFromObject(invalidId, git);
        });

        git.close();
    }

    @Test
    void testFileUtils_getFileFromObject_ThrowsOnNullGit() {
        ObjectId validId = ObjectId.fromString("1234567890123456789012345678901234567890");

        // Should throw exception, not return null
        assertThrows(Exception.class, () -> {
            FileUtils.getFileFromObject(validId, null);
        });
    }

    @Test
    void testGitUtils_getConflictCommits_HandlesNoMergeBaseException(@TempDir Path tempDir) throws Exception {
        // Create a simple repository
        Path repoPath = tempDir.resolve("test-repo");
        Files.createDirectories(repoPath);

        Git git = Git.init().setDirectory(repoPath.toFile()).call();

        // Add a dummy file and commit
        Path dummyFile = repoPath.resolve("test.txt");
        Files.writeString(dummyFile, "test");
        git.add().addFilepattern("test.txt").call();
        git.commit().setMessage("Initial commit").call();

        // Should not throw exception, should return empty list (no merge commits yet)
        var result = GitUtils.getConflictCommits(10, git);

        assertNotNull(result);
        assertTrue(result.isEmpty(), "New repository should have no merge commits");

        git.close();
    }

    @Test
    void testGitUtils_getTotalMergeCount_ReturnsZeroForNewRepo(@TempDir Path tempDir) throws Exception {
        // Create a simple repository
        Path repoPath = tempDir.resolve("test-repo");
        Files.createDirectories(repoPath);

        Git git = Git.init().setDirectory(repoPath.toFile()).call();

        // Add a dummy file and commit
        Path dummyFile = repoPath.resolve("test.txt");
        Files.writeString(dummyFile, "test");
        git.add().addFilepattern("test.txt").call();
        git.commit().setMessage("Initial commit").call();

        // Should return 0 for repository with no merges
        int count = GitUtils.getTotalMergeCount(git);

        assertEquals(0, count, "New repository should have 0 merge commits");

        git.close();
    }

    @Test
    void testFileUtils_deleteDirectory_HandlesNonExistentPath(@TempDir Path tempDir) {
        Path nonExistent = tempDir.resolve("does-not-exist");

        // Should not throw exception for non-existent directory
        assertDoesNotThrow(() -> {
            FileUtils.deleteDirectory(nonExistent.toFile());
        });
    }

    @Test
    void testFileUtils_listFilesUsingFileWalk_ThrowsOnInvalidPath() {
        Path invalidPath = Path.of("/this/path/does/not/exist");

        // Should throw IOException for non-existent path
        assertThrows(IOException.class, () -> {
            FileUtils.listFilesUsingFileWalk(invalidPath);
        });
    }

    /**
     * Test that the improved exception handling in GitUtils properly handles and logs
     * exceptions without swallowing them silently.
     */
    @Test
    void testGitUtils_getGit_ThrowsOnInvalidPath() {
        Path invalidPath = Path.of("/this/path/does/not/exist/definitely");

        // Should throw exception, not return null or fail silently
        assertThrows(Exception.class, () -> {
            GitUtils.getGit(invalidPath);
        });
    }
}
