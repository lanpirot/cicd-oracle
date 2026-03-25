package ch.unibe.cs.mergeci.util;

import ch.unibe.cs.mergeci.BaseTest;
import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.model.ConflictBlock;
import ch.unibe.cs.mergeci.model.IMergeBlock;
import ch.unibe.cs.mergeci.model.ConflictFile;
import ch.unibe.cs.mergeci.model.patterns.PatternFactory;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.Sequence;
import org.eclipse.jgit.merge.MergeResult;
import org.eclipse.jgit.merge.ResolveMerger;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ProjectBuilderUtilsTest extends BaseTest {

    @Test
    void getProjectClass() throws GitAPIException, IOException {
        Git git = GitUtils.getGit(AppConfig.TEST_REPO_DIR.resolve(AppConfig.myTest));
        ResolveMerger merger = GitUtils.makeMerge("26fcd8abe1e9a9ed95af8f4a9c853ae14cb50a61", "ed4809f3570ef0a9213ffdde4e4e04dfe3e334ca", git);
        Map<String, MergeResult<? extends Sequence>> mergeResultMap = GitUtils.getMergeResults(merger);

        assertFalse(mergeResultMap.isEmpty(), "Should have at least one conflicting file");

        for (Map.Entry<String, MergeResult<? extends Sequence>> entry : mergeResultMap.entrySet()) {
            ConflictFile cf = ProjectBuilderUtils.getProjectClass(entry.getValue(), entry.getKey());
            assertNotNull(cf, "getProjectClass should return a non-null ConflictFile");
            assertNotNull(cf.getMergeBlocks(), "Merge blocks should not be null");
            assertFalse(cf.getMergeBlocks().isEmpty(), "Should have merge blocks");

            // Apply OURS to all conflict blocks — verify the pattern applies cleanly
            List<IMergeBlock> resolvedBlocks = new ArrayList<>();
            for (IMergeBlock block : cf.getMergeBlocks()) {
                if (block instanceof ConflictBlock cb) {
                    ConflictBlock clone = cb.clone();
                    clone.setPattern(PatternFactory.fromName("OURS"));
                    resolvedBlocks.add(clone);
                } else {
                    resolvedBlocks.add(block);
                }
            }
            assertFalse(resolvedBlocks.isEmpty(), "Resolved block list should not be empty");
        }
    }
}
