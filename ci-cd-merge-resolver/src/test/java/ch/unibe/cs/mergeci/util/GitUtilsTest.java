package ch.unibe.cs.mergeci.util;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.util.model.MergeInfo;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.merge.ResolveMerger;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public class GitUtilsTest {

    @Test
    void getConflictChunks() {
    }

    @Test
    void getNonConflictObjects() throws IOException, GitAPIException {


        Git git = GitUtils.getGit(AppConfig.TEST_REPO_DIR.resolve(AppConfig.myTest));
        ObjectId branch1 = git.getRepository().resolve("master");
        ObjectId branch2 = git.getRepository().resolve("feature");
        ResolveMerger merger = GitUtils.makeMerge("master", "feature", git);
        Map<String, ObjectId> map = GitUtils.getNonConflictObjects2(merger, branch1, branch2, git);
    }

    @Test
    void getNonConflictObjectsFromRealMerge() throws IOException, GitAPIException, InterruptedException {

        Git git = GitUtils.getGit(AppConfig.TEST_REPO_DIR.resolve(AppConfig.myTest));
        ObjectId branch1 = git.getRepository().resolve("26fcd8abe1e9a9ed95af8f4a9c853ae14cb50a61");
        ObjectId branch2 = git.getRepository().resolve("ed4809f3570ef0a9213ffdde4e4e04dfe3e334ca");
        Map<String, ObjectId> map = GitUtils.getNonConflictObjects(git, branch1, branch2);
    }

    @Test
    void testGetNonConflictObjects() throws IOException, GitAPIException, InterruptedException {

        Git git = GitUtils.getGit(AppConfig.TEST_REPO_DIR.resolve(AppConfig.myTest));
        ObjectId branch1 = git.getRepository().resolve("master");
        ObjectId branch2 = git.getRepository().resolve("feature");

        Map<String, ObjectId> map = GitUtils.getNonConflictObjects(git, branch1, branch2);


    }

    @Test
    void isConflict() throws IOException, GitAPIException {
        Git git = GitUtils.getGit(AppConfig.TEST_REPO_DIR.resolve(AppConfig.ripme));
        GitUtils.isConflict("e0b104f55b153", "3241ae0a84046a21", git);
    }

    @Test
    void getConflictCommits() throws IOException, GitAPIException {
        Git git = GitUtils.getGit(AppConfig.TEST_REPO_DIR.resolve(AppConfig.jacksonDatabind));

        ObjectId head = git.getRepository().resolve("HEAD");

        RevWalk walk = new RevWalk(git.getRepository());


        List<MergeInfo> list = GitUtils.getConflictCommits(AppConfig.TEST_MAX_CONFLICT_MERGES, git);

        System.out.printf("Total number of conflicts: %d", list.size());
        System.out.println();
        for (MergeInfo mergeInfo : list) {
            System.out.println(mergeInfo);
        }
    }

    @Test
    void getNonConflictObjects2() throws IOException, GitAPIException {
        Git git = GitUtils.getGit(AppConfig.TEST_REPO_DIR.resolve(AppConfig.myTest));
        ObjectId branch1 = git.getRepository().resolve("26fcd8abe1e9a9ed95af8f4a9c853ae14cb50a61");
        ObjectId branch2 = git.getRepository().resolve("ed4809f3570ef0a9213ffdde4e4e04dfe3e334ca");

        Repository repo = git.getRepository();


        ResolveMerger merger = (ResolveMerger) MergeStrategy.RESOLVE.newMerger(repo, true);
        merger.setWorkingTreeIterator(new FileTreeIterator(repo));

        DirCache dc = DirCache.newInCore(); // in-memory DirCache
        merger.setDirCache(dc);
        Map<String, ObjectId> map =  GitUtils.getNonConflictObjects2(merger, branch1, branch2, git);
        assertEquals(10, map.size());
    }

    @Test
    void countConflictChunks() throws IOException {
        Git git = GitUtils.getGit(AppConfig.TEST_REPO_DIR.resolve(AppConfig.ruoyivuepro));
        Map<String, Integer> map = GitUtils.countConflictChunks("41eec7806d81c64605e6f1b84454df31801a2488","c6c20234404536803f1e9d7fe0095e50db4c54a1",git);
        System.out.println(map);
    }
}