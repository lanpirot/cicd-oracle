package ch.unibe.cs.mergeci;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.Sequence;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectStream;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.merge.MergeChunk;
import org.eclipse.jgit.merge.MergeResult;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.merge.ResolveMerger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

@SpringBootTest
class CiCdMergeResolverApplicationTests {

    @Test
    void gitMerge() throws IOException, GitAPIException {
        Git git = Git.open(new File("src/test/resources/test-merge-projects/myTest"));
        Repository repo = git.getRepository();
        git.checkout().setName("master").call();

        ObjectId feature = repo.resolve("feature");
        ObjectId head = repo.resolve("master");

        System.out.println(MergeStrategy.RESOLVE.newMerger(repo, true));
        ResolveMerger merger = (ResolveMerger) MergeStrategy.RESOLVE.newMerger(repo, true);
//        merger.setWorkingTreeIterator(new FileTreeIterator(repo));

        boolean isMergedWithoutConflicts = merger.merge(head, feature);

        if (!isMergedWithoutConflicts) {
            System.out.println("Merge has conflicts!");

            List<String> conflictPaths = merger.getUnmergedPaths();

            System.out.println("Amount of conflicts:" + conflictPaths.size());
            for (String path : conflictPaths) {
                MergeResult<? extends Sequence> result = merger.getMergeResults().get(path);
                if (result == null) continue;

                System.out.println("\nFile: " + path);

                for (MergeChunk chunk : result) {
                    System.out.println("  State: " + chunk.getConflictState());

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

}
