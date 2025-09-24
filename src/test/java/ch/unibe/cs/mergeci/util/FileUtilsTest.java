package ch.unibe.cs.mergeci.util;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.merge.ResolveMerger;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FileUtilsTest {

    @Test
    void saveFilesFromObjectId() throws GitAPIException, IOException {
        FileUtils.deleteDirectory(new File("mytempt\\subfolder"));
        GitUtils gitUtils = new GitUtils(new File("src/test/resources/test-merge-projects/myTest"));

        Git git = gitUtils.getGit();
        ObjectId branch1 = git.getRepository().resolve("master");
        ObjectId branch2 = git.getRepository().resolve("feature");
        ResolveMerger merger = gitUtils.makeMerge("","");
        Map<String, ObjectId> map = GitUtils.getNonConflictObjects2(merger, branch1, branch2, gitUtils.getGit());

        FileUtils.saveFilesFromObjectId("mytempt\\subfolder", map, gitUtils.getGit());
    }
}