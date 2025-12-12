package ch.unibe.cs.mergeci.util;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.merge.ResolveMerger;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FileUtilsTest {

    @Test
    void saveFilesFromObjectId() throws GitAPIException, IOException {
        FileUtils.deleteDirectory(new File("mytempt\\subfolder"));

        Git git =GitUtils.getGit("src/test/resources/test-merge-projects/myTest");
        ObjectId branch1 = git.getRepository().resolve("master");
        ObjectId branch2 = git.getRepository().resolve("feature");
        ResolveMerger merger = GitUtils.makeMerge("","", git);
        Map<String, ObjectId> map = GitUtils.getNonConflictObjects2(merger, branch1, branch2, git);

        FileUtils.saveFilesFromObjectId(Paths.get("mytempt\\subfolder"), map, git);
    }
}