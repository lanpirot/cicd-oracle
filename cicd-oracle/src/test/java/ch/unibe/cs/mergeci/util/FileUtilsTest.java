package ch.unibe.cs.mergeci.util;

import ch.unibe.cs.mergeci.BaseTest;
import ch.unibe.cs.mergeci.config.AppConfig;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.merge.ResolveMerger;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class FileUtilsTest extends BaseTest {

    @Test
    void saveFilesFromObjectId() throws GitAPIException, IOException {
        Git git = GitUtils.getGit(AppConfig.TEST_REPO_DIR.resolve(AppConfig.myTest));
        ObjectId branch1 = git.getRepository().resolve("master");
        ObjectId branch2 = git.getRepository().resolve("feature");
        ResolveMerger merger = GitUtils.makeMerge("master","feature", git);
        //ResolveMerger merger = GitUtils.makeMerge("","", git);
        Map<String, ObjectId> map = GitUtils.getNonConflictObjects2(merger, branch1, branch2, git);

        assertNotNull(map, "Non-conflict objects map should not be null");
        assertFalse(map.isEmpty(), "Should have at least one non-conflicting file");

        FileUtils.saveFilesFromObjectId(AppConfig.TEST_TMP_DIR, map, git);

        // Verify files were actually saved
        assertTrue(Files.exists(AppConfig.TEST_TMP_DIR), "Test temp directory should exist");
        for (String filePath : map.keySet()) {
            Path savedFile = AppConfig.TEST_TMP_DIR.resolve(filePath);
            assertTrue(Files.exists(savedFile), "File should be saved: " + filePath);
        }
    }
}