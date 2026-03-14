package ch.unibe.cs.mergeci;

import ch.unibe.cs.mergeci.config.AppConfig;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.Sequence;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.merge.MergeChunk;
import org.eclipse.jgit.merge.MergeResult;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.merge.RecursiveMerger;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

class CiCdMergeResolverApplicationTests extends BaseTest {

    @Test
    void simpleMerge() throws IOException, GitAPIException {
        Git git = Git.open(AppConfig.TEST_REPO_DIR.resolve(AppConfig.myTest).toFile());
        Status status = git.status().call();
        Set<String> conflictList = status.getConflicting();
        System.out.println(conflictList.size());

        Repository repo = git.getRepository();

        System.out.println(repo.getRepositoryState());
        // check out "master"
        Ref checkout = git.checkout().setName("master").call();
        System.out.println("Result of checking out master: " + checkout);

        // retrieve the objectId of the latest commit on branch
        ObjectId mergeBase = repo.resolve("feature");

        // perform the actual merge, here we disable FastForward to see the
        // actual merge-commit even though the merge is trivial
        org.eclipse.jgit.api.MergeResult merge = git.merge().
                include(mergeBase).
                setCommit(true).
                setFastForward(MergeCommand.FastForwardMode.NO_FF).
                        setMessage("Merged changes").
                call();



        System.out.println("Merge-Results for id: " + mergeBase + ": " + merge);

        // Check for conflicts with null check (JGit returns null when there are no conflicts)
        Map<String, int[][]> conflicts = merge.getConflicts();
        if (conflicts != null) {
            System.out.println("Amount of conflicts: " + conflicts.size());
            for (Map.Entry<String, int[][]> entry : conflicts.entrySet()) {
                System.out.println("Key: " + entry.getKey());
                for (int[] arr : entry.getValue()) {
                    System.out.println("value: " + Arrays.toString(arr));
                }
            }
        } else {
            System.out.println("Amount of conflicts: 0 (merge succeeded with no conflicts)");
        }
    }
    @Test
    void gitMerge() throws IOException, GitAPIException {
        Git git = Git.open(AppConfig.TEST_REPO_DIR.resolve(AppConfig.myTest).toFile());
        Repository repo = git.getRepository();
        git.checkout().setName("master").call();

        ObjectId feature = repo.resolve("ed4809f");
        ObjectId head = repo.resolve("26fcd8a");

        System.out.println(MergeStrategy.RECURSIVE.newMerger(repo, true));
        RecursiveMerger merger = (RecursiveMerger) MergeStrategy.RECURSIVE.newMerger(repo, true);

        boolean isMergedWithoutConflicts = merger.merge(head, feature);

        if (!isMergedWithoutConflicts) {
            System.out.println("Merge has conflicts!");

            List<String> conflictPaths = merger.getUnmergedPaths();

            System.out.println("Amount of conflicts:" + conflictPaths.size());
            assertNotNull(conflictPaths, "Conflict paths should not be null");
            assertFalse(conflictPaths.isEmpty(), "Should have at least one conflict");

            for (String path : conflictPaths) {
                MergeResult<? extends Sequence> result = merger.getMergeResults().get(path);
                if (result == null) continue;

                System.out.println("\nFile: " + path);
                assertNotNull(result, "Merge result should not be null");
                assertNotNull(result.getSequences(), "Sequences should not be null");

                for (MergeChunk chunk : result) {
                    System.out.println("  State: " + chunk.getConflictState());
                    assertNotNull(chunk.getConflictState(), "Chunk state should not be null");

                    int seqIndex = chunk.getSequenceIndex();

                    Sequence seq = result.getSequences().get(seqIndex);
                    if (chunk.getConflictState() == MergeChunk.ConflictState.BASE_CONFLICTING_RANGE || chunk.getConflictState() == MergeChunk.ConflictState.NO_CONFLICT) {
                        for (int i = chunk.getBegin(); i < chunk.getEnd(); i++) {
                            System.out.println("    " + ((RawText) seq).getString(i));
                        }
                    }
                }
            }
        } else {
            System.out.println("Merge completed successfully!");
        }
    }

    @Test
    void gitMerge2() throws IOException, GitAPIException {
        Git git = Git.open(AppConfig.TEST_REPO_DIR.resolve(AppConfig.myTest).toFile());
        Repository repo = git.getRepository();
        git.checkout().setName("master").call();

        ObjectId head = repo.resolve("master");
        ObjectId feature = repo.resolve("feature");

        System.out.println(MergeStrategy.RECURSIVE.newMerger(repo, true));
        RecursiveMerger merger = (RecursiveMerger) MergeStrategy.RECURSIVE.newMerger(repo, true);
        merger.setWorkingTreeIterator(new FileTreeIterator(repo));

        boolean isMergedWithoutConflicts = merger.merge(head, feature);

        if (!isMergedWithoutConflicts) {
            System.out.println("Merge has conflicts!");

            List<String> conflictPaths = merger.getUnmergedPaths();
            Map<String, MergeResult<? extends Sequence>> mergeResult = merger.getMergeResults();

            assertNotNull(conflictPaths, "Conflict paths should not be null");
            assertFalse(conflictPaths.isEmpty(), "Should have at least one conflict");
            assertNotNull(mergeResult, "Merge result map should not be null");
            assertFalse(mergeResult.isEmpty(), "Merge result map should not be empty");

            System.out.println("Amount of conflicts: " + conflictPaths.size());
            for (Map.Entry<String, MergeResult<? extends Sequence>> mergeResultEntry : mergeResult.entrySet()) {
                String path = mergeResultEntry.getKey();
                MergeResult<? extends Sequence> mergeChunks = mergeResultEntry.getValue();

                System.out.println("\nFile: " + mergeResultEntry.getKey());
                assertNotNull(mergeChunks, "Merge chunks should not be null");
                assertNotNull(mergeChunks.getSequences(), "Sequences should not be null");

                for (MergeChunk chunk : mergeChunks) {
                    System.out.println("  State: " + chunk.getConflictState());
                    assertNotNull(chunk.getConflictState(), "Chunk state should not be null");

                    int seqIndex = chunk.getSequenceIndex();

                    Sequence seq = mergeResultEntry.getValue().getSequences().get(seqIndex);
                    assertNotNull(seq, "Sequence should not be null");
                    for (int i = chunk.getBegin(); i < chunk.getEnd(); i++) {
                        System.out.println("    " + ((RawText) seq).getString(i));
                    }
                }
            }
        } else {
            System.out.println("Merge completed successfully!");
        }
    }

}
