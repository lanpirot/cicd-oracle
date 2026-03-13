package ch.unibe.cs.mergeci.util;

import ch.unibe.cs.mergeci.BaseTest;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.Sequence;
import org.eclipse.jgit.errors.NoMergeBaseException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.merge.MergeResult;
import org.eclipse.jgit.merge.ResolveMerger;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that verify RecursiveMerger can handle criss-cross merges (multiple merge bases).
 *
 * This test creates a repository with the following structure:
 *
 *     A---B---D---F---H  (branch1)
 *      \     X   X   /
 *       \   /   /   /
 *        \ /   /   /
 *         C---E---G      (branch2)
 *
 * Where:
 * - D is a merge of B and C
 * - E is a merge of C and B (criss-cross)
 * - When merging H and G, both D and E are merge bases
 * - This would fail with MergeStrategy.RESOLVE
 * - This should work with MergeStrategy.RECURSIVE
 */
public class CrissCrossMergeTest extends BaseTest {

    @Test
    void testCrissCrossMerge_NoException(@TempDir Path tempDir) throws Exception {
        // Create a repository with criss-cross merge scenario
        Path repoPath = tempDir.resolve("criss-cross-repo");
        Files.createDirectories(repoPath);

        Git git = Git.init().setDirectory(repoPath.toFile()).call();

        // Create initial commit A
        Path file = repoPath.resolve("file.txt");
        Files.writeString(file, "Initial content\n");
        git.add().addFilepattern("file.txt").call();
        RevCommit commitA = git.commit().setMessage("A: Initial commit").call();

        // Create branch1 and branch2 from A
        git.branchCreate().setName("branch1").setStartPoint(commitA).call();
        git.branchCreate().setName("branch2").setStartPoint(commitA).call();

        // On branch2: Create commit C
        git.checkout().setName("branch2").setForced(true).call();
        Files.writeString(file, "Initial content\nBranch2 line 1\n");
        git.add().addFilepattern("file.txt").call();
        RevCommit commitC = git.commit().setMessage("C: Branch2 change 1").call();

        // On branch1: Create commit B
        git.checkout().setName("branch1").setForced(true).call();
        Files.writeString(file, "Initial content\nBranch1 line 1\n");
        git.add().addFilepattern("file.txt").call();
        RevCommit commitB = git.commit().setMessage("B: Branch1 change 1").call();

        // On branch1: Merge branch2 -> commit D (first merge)
        org.eclipse.jgit.api.MergeResult mergeResult = git.merge()
                .include(commitC)
                .setMessage("D: Merge branch2 into branch1")
                .call();

        // Handle conflicts if any
        if (mergeResult.getMergeStatus() == org.eclipse.jgit.api.MergeResult.MergeStatus.CONFLICTING) {
            Files.writeString(file, "Initial content\nBranch1 line 1\nBranch2 line 1\n");
            git.add().addFilepattern("file.txt").call();
            git.commit().setMessage("D: Merge branch2 into branch1").call();
        }

        RevCommit commitD = git.log().setMaxCount(1).call().iterator().next();

        // On branch2: Merge branch1 -> commit E (criss-cross merge)
        git.checkout().setName("branch2").setForced(true).call();
        org.eclipse.jgit.api.MergeResult mergeResult2 = git.merge()
                .include(commitB)
                .setMessage("E: Merge branch1 into branch2")
                .call();

        // Handle conflicts if any
        if (mergeResult2.getMergeStatus() == org.eclipse.jgit.api.MergeResult.MergeStatus.CONFLICTING) {
            Files.writeString(file, "Initial content\nBranch1 line 1\nBranch2 line 1\n");
            git.add().addFilepattern("file.txt").call();
            git.commit().setMessage("E: Merge branch1 into branch2").call();
        }

        RevCommit commitE = git.log().setMaxCount(1).call().iterator().next();

        // On branch1: Create commit F
        git.checkout().setName("branch1").setForced(true).call();
        Files.writeString(file, Files.readString(file) + "Branch1 line 2\n");
        git.add().addFilepattern("file.txt").call();
        RevCommit commitF = git.commit().setMessage("F: Branch1 change 2").call();

        // On branch2: Create commit G
        git.checkout().setName("branch2").setForced(true).call();
        Files.writeString(file, Files.readString(file) + "Branch2 line 2\n");
        git.add().addFilepattern("file.txt").call();
        RevCommit commitG = git.commit().setMessage("G: Branch2 change 2").call();

        // Now merge branch2 into branch1 -> commit H
        // This merge has MULTIPLE merge bases (D and E) - a criss-cross merge
        git.checkout().setName("branch1").setForced(true).call();

        // Test: This should NOT throw NoMergeBaseException with RECURSIVE strategy
        assertDoesNotThrow(() -> {
            ResolveMerger merger = GitUtils.makeMerge("branch1", "branch2", git);
            assertNotNull(merger, "Merger should not be null");
        }, "RECURSIVE strategy should handle criss-cross merge without exception");

        System.out.println("✓ Criss-cross merge handled successfully with RECURSIVE strategy!");
    }

    @Test
    void testCrissCrossMerge_CanGetMergeResults(@TempDir Path tempDir) throws Exception {
        // Create the same criss-cross scenario with no conflicts
        Path repoPath = tempDir.resolve("criss-cross-repo-2");
        Files.createDirectories(repoPath);

        Git git = Git.init().setDirectory(repoPath.toFile()).call();

        // Initial commit
        Path file1 = repoPath.resolve("file1.txt");
        Path file2 = repoPath.resolve("file2.txt");
        Files.writeString(file1, "line1\n");
        Files.writeString(file2, "line1\n");
        git.add().addFilepattern(".").call();
        RevCommit initialCommit = git.commit().setMessage("Initial").call();

        // Create branches
        git.branchCreate().setName("branch1").setStartPoint(initialCommit).call();
        git.branchCreate().setName("branch2").setStartPoint(initialCommit).call();

        // Branch2: Add line to file2 only
        git.checkout().setName("branch2").setForced(true).call();
        Files.writeString(file2, "line1\nline2-from-branch2\n");
        git.add().addFilepattern(".").call();
        RevCommit c1 = git.commit().setMessage("Branch2 add").call();

        // Branch1: Add line to file1 only
        git.checkout().setName("branch1").setForced(true).call();
        Files.writeString(file1, "line1\nline2-from-branch1\n");
        git.add().addFilepattern(".").call();
        RevCommit c2 = git.commit().setMessage("Branch1 add").call();

        // Criss-cross: merge each way (no conflicts since different files)
        git.checkout().setName("branch1").setForced(true).call();
        git.merge().include(c1).setMessage("Merge branch2 to branch1").call();

        git.checkout().setName("branch2").setForced(true).call();
        git.merge().include(c2).setMessage("Merge branch1 to branch2").call();

        // Add conflicting changes to same file
        git.checkout().setName("branch1").setForced(true).call();
        Path conflictFile = repoPath.resolve("conflict.txt");
        Files.writeString(conflictFile, "branch1-version\n");
        git.add().addFilepattern(".").call();
        git.commit().setMessage("Branch1 final").call();

        git.checkout().setName("branch2").setForced(true).call();
        Files.writeString(conflictFile, "branch2-version\n");
        git.add().addFilepattern(".").call();
        git.commit().setMessage("Branch2 final").call();

        // Final merge with conflict - test that we can get merge results
        git.checkout().setName("branch1").setForced(true).call();
        ResolveMerger merger = GitUtils.makeMerge("branch1", "branch2", git);

        assertNotNull(merger, "Merger should not be null");

        // Should be able to get merge results (will have conflicts)
        Map<String, MergeResult<? extends Sequence>> mergeResults = GitUtils.getMergeResults(merger);
        assertNotNull(mergeResults, "Should be able to get merge results");

        System.out.println("✓ Can successfully retrieve merge results from criss-cross merge!");
        System.out.println("  Conflicting files: " + mergeResults.keySet());
    }

    @Test
    void testCountConflictChunks_CrissCrossMerge(@TempDir Path tempDir) throws Exception {
        // Test that countConflictChunks works with criss-cross merges
        Path repoPath = tempDir.resolve("criss-cross-repo-3");
        Files.createDirectories(repoPath);

        Git git = Git.init().setDirectory(repoPath.toFile()).call();

        // Create a conflict scenario with criss-cross history
        Path file1 = repoPath.resolve("file1.txt");
        Path file2 = repoPath.resolve("file2.txt");
        Files.writeString(file1, "original\n");
        Files.writeString(file2, "original\n");
        git.add().addFilepattern(".").call();
        RevCommit initialCommit = git.commit().setMessage("Initial").call();

        // Branch setup
        git.branchCreate().setName("branch1").setStartPoint(initialCommit).call();
        git.branchCreate().setName("branch2").setStartPoint(initialCommit).call();

        // Branch2 change (modify file2 only)
        git.checkout().setName("branch2").setForced(true).call();
        Files.writeString(file2, "branch2-version\n");
        git.add().addFilepattern(".").call();
        RevCommit c1 = git.commit().setMessage("Branch2").call();

        // Branch1 change (modify file1 only)
        git.checkout().setName("branch1").setForced(true).call();
        Files.writeString(file1, "branch1-version\n");
        git.add().addFilepattern(".").call();
        RevCommit c2 = git.commit().setMessage("Branch1").call();

        // Create criss-cross (no conflicts since different files)
        git.checkout().setName("branch1").setForced(true).call();
        git.merge().include(c1).setMessage("Merge to branch1").call();

        git.checkout().setName("branch2").setForced(true).call();
        git.merge().include(c2).setMessage("Merge to branch2").call();

        // Add conflicting changes to same file
        git.checkout().setName("branch1").setForced(true).call();
        Path conflictFile = repoPath.resolve("conflict.txt");
        Files.writeString(conflictFile, "branch1-final-version\n");
        git.add().addFilepattern(".").call();
        git.commit().setMessage("Branch1 final").call();

        git.checkout().setName("branch2").setForced(true).call();
        Files.writeString(conflictFile, "branch2-final-version\n");
        git.add().addFilepattern(".").call();
        git.commit().setMessage("Branch2 final").call();

        // Test: countConflictChunks should work without throwing NoMergeBaseException
        git.checkout().setName("branch1").setForced(true).call();
        assertDoesNotThrow(() -> {
            Map<String, Integer> conflicts = GitUtils.countConflictChunks("branch1", "branch2", git);
            assertNotNull(conflicts, "Should get conflict map");
            assertFalse(conflicts.isEmpty(), "Should have conflicts");
            assertTrue(conflicts.containsKey("conflict.txt"), "Should have conflict in conflict.txt");
            System.out.println("✓ countConflictChunks works with criss-cross merge!");
            System.out.println("  Conflicts found: " + conflicts);
        }, "countConflictChunks should handle criss-cross merge");
    }
}
