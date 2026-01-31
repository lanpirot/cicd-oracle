package ch.unibe.cs.mergeci.util;

import ch.unibe.cs.mergeci.config.AppConfig;
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
        FileUtils.deleteDirectory(AppConfig.TEST_MYTEMP_DIR);

        Git git = GitUtils.getGit(AppConfig.TEST_RESOURCE_DIR.getPath()+"/myTest");
        ObjectId branch1 = git.getRepository().resolve("master");
        ObjectId branch2 = git.getRepository().resolve("feature");
        ResolveMerger merger = GitUtils.makeMerge("master","feature", git);
        //ResolveMerger merger = GitUtils.makeMerge("","", git);
        Map<String, ObjectId> map = GitUtils.getNonConflictObjects2(merger, branch1, branch2, git);

        FileUtils.saveFilesFromObjectId(AppConfig.TEST_MYTEMP_DIR.toPath(), map, git);
        FileUtils.deleteDirectory(AppConfig.TEST_MYTEMP_DIR);
    }
}