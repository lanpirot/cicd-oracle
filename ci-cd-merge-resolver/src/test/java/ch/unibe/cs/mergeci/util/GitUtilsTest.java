package ch.unibe.cs.mergeci.util;

import ch.unibe.cs.mergeci.util.model.MergeInfo;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.Sequence;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.merge.MergeResult;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.merge.ResolveMerger;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class GitUtilsTest {

    @Test
    void getConflictChunks() {
    }

    @Test
    void getNonConflictObjects() throws IOException, GitAPIException {


        Git git = GitUtils.getGit("src/test/resources/test-merge-projects/myTest");
        ObjectId branch1 = git.getRepository().resolve("master");
        ObjectId branch2 = git.getRepository().resolve("feature");
        ResolveMerger merger = GitUtils.makeMerge("master","feature", git);
        Map<String, ObjectId> map = GitUtils.getNonConflictObjects2(merger, branch1, branch2, git);
    }

    @Test
    void getNonConflictObjectsFromRealMerge() throws IOException, GitAPIException, InterruptedException {

        Git git =GitUtils.getGit("src/test/resources/test-merge-projects/myTest");
        ObjectId branch1 = git.getRepository().resolve("26fcd8abe1e9a9ed95af8f4a9c853ae14cb50a61");
        ObjectId branch2 = git.getRepository().resolve("ed4809f3570ef0a9213ffdde4e4e04dfe3e334ca");
        Map<String, ObjectId> map = GitUtils.getNonConflictObjects(git, branch1, branch2);
    }

    @Test
    void testGetNonConflictObjects() throws IOException, GitAPIException, InterruptedException {

        Git git =GitUtils.getGit("src/test/resources/test-merge-projects/myTest");
        ObjectId branch1 = git.getRepository().resolve("master");
        ObjectId branch2 = git.getRepository().resolve("feature");

        Map<String, ObjectId> map =  GitUtils.getNonConflictObjects(git, branch1, branch2);


    }

    @Test
    void isConflict() throws IOException, GitAPIException {
        Git git =GitUtils.getGit("src/test/resources/test-merge-projects/ripme");
        GitUtils.isConflict("e0b104f55b153","3241ae0a84046a21", git);
    }

    @Test
    void getConflictCommits() throws IOException, GitAPIException {
        Git git = GitUtils.getGit("src/test/resources/test-merge-projects/jackson-databind");

        ObjectId head = git.getRepository().resolve("HEAD");

        RevWalk walk = new RevWalk(git.getRepository());


        List<MergeInfo> list  = GitUtils.getConflictCommits(100, git);

        for(MergeInfo mergeInfo: list){
            System.out.println(mergeInfo);
        }
    }

    @Test
    void getNonConflictObjects2() throws IOException, GitAPIException {
        Git git =GitUtils.getGit("src/test/resources/test-merge-projects/myTest");
        ObjectId branch1 = git.getRepository().resolve("26fcd8abe1e9a9ed95af8f4a9c853ae14cb50a61");
        ObjectId branch2 = git.getRepository().resolve("ed4809f3570ef0a9213ffdde4e4e04dfe3e334ca");

        Repository repo = git.getRepository();



        ResolveMerger merger = (ResolveMerger) MergeStrategy.RESOLVE.newMerger(repo, true);
//        merger.setWorkingTreeIterator(new FileTreeIterator(repo));

        DirCache dc = DirCache.newInCore(); // in-memory DirCache
        merger.setDirCache(dc);
        boolean isMergedWithoutConflicts = merger.merge(branch1, branch2);

        Map<String, DirCacheEntry> cache =  merger.getToBeCheckedOut();

//        Map<String, ObjectId> map =  GitUtils.getNonConflictObjects2(merger, branch1, branch2, git);
    }
}