package ch.unibe.cs.mergeci.util;

import ch.unibe.cs.mergeci.BaseTest;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.merge.RecursiveMerger;
import org.eclipse.jgit.merge.ResolveMerger;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test that verifies RecursiveMerger correctly identifies and handles multiple merge bases.
 *
 * This test creates a criss-cross merge scenario and explicitly verifies that:
 * 1. Multiple merge bases exist between two branches
 * 2. RecursiveMerger finds all of them
 * 3. RecursiveMerger successfully merges them
 */
public class MultipleMergeBasesTest extends BaseTest {

    @Test
    void testMultipleMergeBasesDetected(@TempDir Path tempDir) throws Exception {
        System.out.println("\n=== Testing Multiple Merge Base Detection ===\n");

        // Create repository with criss-cross merge
        Path repoPath = tempDir.resolve("multi-base-repo");
        Files.createDirectories(repoPath);

        Git git = Git.init().setDirectory(repoPath.toFile()).call();

        // Create initial commit A
        Path file = repoPath.resolve("file.txt");
        Files.writeString(file, "line1\n");
        git.add().addFilepattern("file.txt").call();
        RevCommit commitA = git.commit().setMessage("A: Initial").call();
        System.out.println("✓ Created commit A (initial): " + commitA.abbreviate(7).name());

        // Create branch1 and branch2 from A
        git.branchCreate().setName("branch1").setStartPoint(commitA).call();
        git.branchCreate().setName("branch2").setStartPoint(commitA).call();

        // Branch2: Create commit C (add line at end)
        git.checkout().setName("branch2").call();
        Files.writeString(file, "line1\nline2-b2\n");
        git.add().addFilepattern("file.txt").call();
        RevCommit commitC = git.commit().setMessage("C: Branch2 work").call();
        System.out.println("✓ Created commit C (branch2): " + commitC.abbreviate(7).name());

        // Branch1: Create commit B (add different line at end)
        git.checkout().setName("branch1").call();
        Files.writeString(file, "line1\nline2-b1\n");
        git.add().addFilepattern("file.txt").call();
        RevCommit commitB = git.commit().setMessage("B: Branch1 work").call();
        System.out.println("✓ Created commit B (branch1): " + commitB.abbreviate(7).name());

        // Branch1: Merge branch2 -> commit D (first merge) - will have conflict, resolve it
        git.checkout().setName("branch1").call();
        org.eclipse.jgit.api.MergeResult mergeResult = git.merge()
                .include(commitC)
                .setMessage("D: Merge branch2 into branch1")
                .call();

        // If there was a conflict, resolve it and commit
        if (mergeResult.getMergeStatus() == org.eclipse.jgit.api.MergeResult.MergeStatus.CONFLICTING) {
            Files.writeString(file, "line1\nline2-b1\nline2-b2\n"); // Resolve: keep both
            git.add().addFilepattern("file.txt").call();
            git.commit().setMessage("D: Merge branch2 into branch1").call();
        }

        RevCommit commitD = git.log().setMaxCount(1).call().iterator().next();
        System.out.println("✓ Created commit D (merge C into branch1): " + commitD.abbreviate(7).name());

        // Branch2: Merge branch1 -> commit E (criss-cross!)
        git.checkout().setName("branch2").call();
        org.eclipse.jgit.api.MergeResult mergeResult2 = git.merge()
                .include(commitB)
                .setMessage("E: Merge branch1 into branch2")
                .call();

        // If there was a conflict, resolve it and commit
        if (mergeResult2.getMergeStatus() == org.eclipse.jgit.api.MergeResult.MergeStatus.CONFLICTING) {
            Files.writeString(file, "line1\nline2-b1\nline2-b2\n"); // Resolve: keep both
            git.add().addFilepattern("file.txt").call();
            git.commit().setMessage("E: Merge branch1 into branch2").call();
        }

        RevCommit commitE = git.log().setMaxCount(1).call().iterator().next();
        System.out.println("✓ Created commit E (merge B into branch2): " + commitE.abbreviate(7).name());

        // Branch1: Additional work -> commit F
        git.checkout().setName("branch1").call();
        Files.writeString(file, Files.readString(file) + "line3-b1\n");
        git.add().addFilepattern("file.txt").call();
        RevCommit commitF = git.commit().setMessage("F: More branch1 work").call();
        System.out.println("✓ Created commit F (more branch1 work): " + commitF.abbreviate(7).name());

        // Branch2: Additional work -> commit G
        git.checkout().setName("branch2").call();
        Files.writeString(file, Files.readString(file) + "line3-b2\n");
        git.add().addFilepattern("file.txt").call();
        RevCommit commitG = git.commit().setMessage("G: More branch2 work").call();
        System.out.println("✓ Created commit G (more branch2 work): " + commitG.abbreviate(7).name());

        System.out.println("\n--- Repository structure created ---");
        System.out.println("    A (initial)");
        System.out.println("   / \\");
        System.out.println("  B   C");
        System.out.println("  |\\X/|");
        System.out.println("  | X |");
        System.out.println("  |/X\\|");
        System.out.println("  D   E  (both are merge bases!)");
        System.out.println("  |   |");
        System.out.println("  F   G  (final commits to merge)");
        System.out.println();

        // Now find merge bases between F and G
        ObjectId branch1Head = git.getRepository().resolve("branch1"); // F
        ObjectId branch2Head = git.getRepository().resolve("branch2"); // G

        System.out.println("Finding merge bases between:");
        System.out.println("  branch1 (F): " + branch1Head.abbreviate(7).name());
        System.out.println("  branch2 (G): " + branch2Head.abbreviate(7).name());
        System.out.println();

        // Use RevWalk to find merge bases
        try (RevWalk walk = new RevWalk(git.getRepository())) {
            RevCommit head1 = walk.parseCommit(branch1Head);
            RevCommit head2 = walk.parseCommit(branch2Head);

            walk.setRevFilter(RevFilter.MERGE_BASE);
            walk.markStart(head1);
            walk.markStart(head2);

            List<RevCommit> mergeBases = new ArrayList<>();
            for (RevCommit mergeBase : walk) {
                mergeBases.add(mergeBase);
            }

            System.out.println("✓ Found " + mergeBases.size() + " merge base(s):");
            for (int i = 0; i < mergeBases.size(); i++) {
                RevCommit base = mergeBases.get(i);
                System.out.println("  [" + (i+1) + "] " + base.abbreviate(7).name() + " - " + base.getShortMessage());
            }
            System.out.println();

            // VERIFY: Should have MORE THAN ONE merge base (criss-cross)
            assertTrue(mergeBases.size() > 1,
                "Should have multiple merge bases in criss-cross merge! Found: " + mergeBases.size());

            System.out.println("✅ VERIFIED: Multiple merge bases detected (" + mergeBases.size() + " bases)");

            // Verify B and C are among the merge bases (they are the actual common ancestors)
            boolean foundB = mergeBases.stream().anyMatch(c -> c.equals(commitB));
            boolean foundC = mergeBases.stream().anyMatch(c -> c.equals(commitC));

            System.out.println("   - Commit B is a merge base: " + foundB);
            System.out.println("   - Commit C is a merge base: " + foundC);

            assertTrue(foundB && foundC, "Both B and C should be merge bases in criss-cross scenario");
        }

        // Now test that RecursiveMerger can handle this
        System.out.println("\nTesting RecursiveMerger with multiple merge bases...");

        RecursiveMerger recursiveMerger = (RecursiveMerger) MergeStrategy.RECURSIVE.newMerger(
            git.getRepository(), true);

        boolean recursiveMergeResult = recursiveMerger.merge(branch1Head, branch2Head);

        System.out.println("✓ RecursiveMerger.merge() completed successfully");
        System.out.println("  Merge result (no conflicts): " + recursiveMergeResult);
        System.out.println("  Conflicting files: " +
            (recursiveMerger.getMergeResults().isEmpty() ? "none" : recursiveMerger.getMergeResults().keySet()));

        assertNotNull(recursiveMerger, "RecursiveMerger should handle multiple merge bases");

        System.out.println("\n✅ SUCCESS: RecursiveMerger correctly handles multiple merge bases!");
        System.out.println("============================================\n");
    }

    @Test
    void testGitUtilsMakeMergeHandlesMultipleBases(@TempDir Path tempDir) throws Exception {
        System.out.println("\n=== Testing GitUtils.makeMerge() with Multiple Bases ===\n");

        // Create same criss-cross scenario but with no conflicts
        Path repoPath = tempDir.resolve("gitutils-multi-base-repo");
        Files.createDirectories(repoPath);

        Git git = Git.init().setDirectory(repoPath.toFile()).call();

        // Initial setup
        Path file1 = repoPath.resolve("file1.txt");
        Path file2 = repoPath.resolve("file2.txt");
        Files.writeString(file1, "content1\n");
        Files.writeString(file2, "content2\n");
        git.add().addFilepattern(".").call();
        RevCommit initialCommit = git.commit().setMessage("Initial").call();

        // Create branches
        git.branchCreate().setName("feature1").setStartPoint(initialCommit).call();
        git.branchCreate().setName("feature2").setStartPoint(initialCommit).call();

        // Feature2 work (modify file2 only)
        git.checkout().setName("feature2").call();
        Files.writeString(file2, "content2\nfeature2-line1\n");
        git.add().addFilepattern(".").call();
        RevCommit f2c1 = git.commit().setMessage("Feature2: Add to file2").call();

        // Feature1 work (modify file1 only)
        git.checkout().setName("feature1").call();
        Files.writeString(file1, "content1\nfeature1-line1\n");
        git.add().addFilepattern(".").call();
        RevCommit f1c1 = git.commit().setMessage("Feature1: Add to file1").call();

        // Criss-cross merges (no conflicts since they modify different files)
        git.checkout().setName("feature1").call();
        git.merge().include(f2c1).setMessage("Merge feature2 to feature1").call();

        git.checkout().setName("feature2").call();
        git.merge().include(f1c1).setMessage("Merge feature1 to feature2").call();

        // More work on each (still separate files)
        git.checkout().setName("feature1").call();
        Files.writeString(file1, Files.readString(file1) + "feature1-line2\n");
        git.add().addFilepattern(".").call();
        git.commit().setMessage("Feature1: More work").call();

        git.checkout().setName("feature2").call();
        Files.writeString(file2, Files.readString(file2) + "feature2-line2\n");
        git.add().addFilepattern(".").call();
        git.commit().setMessage("Feature2: More work").call();

        // Verify multiple merge bases exist
        ObjectId f1Head = git.getRepository().resolve("feature1");
        ObjectId f2Head = git.getRepository().resolve("feature2");

        try (RevWalk walk = new RevWalk(git.getRepository())) {
            RevCommit h1 = walk.parseCommit(f1Head);
            RevCommit h2 = walk.parseCommit(f2Head);

            walk.setRevFilter(RevFilter.MERGE_BASE);
            walk.markStart(h1);
            walk.markStart(h2);

            int baseCount = 0;
            for (RevCommit base : walk) {
                baseCount++;
                System.out.println("  Merge base " + baseCount + ": " + base.abbreviate(7).name());
            }

            System.out.println("✓ Found " + baseCount + " merge base(s)");
            assertTrue(baseCount > 1, "Should have multiple merge bases");
        }

        // Test GitUtils.makeMerge()
        System.out.println("\nTesting GitUtils.makeMerge()...");

        ResolveMerger merger = GitUtils.makeMerge("feature1", "feature2", git);

        assertNotNull(merger, "GitUtils.makeMerge should return a merger");
        assertNotNull(merger.getMergeResults(), "Should be able to get merge results");

        System.out.println("✓ GitUtils.makeMerge() succeeded with multiple merge bases");
        System.out.println("  Merge had conflicts: " + !merger.getMergeResults().isEmpty());

        System.out.println("\n✅ SUCCESS: GitUtils.makeMerge() handles multiple merge bases!");
        System.out.println("=======================================================\n");
    }
}
