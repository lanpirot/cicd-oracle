package ch.unibe.cs.mergeci.util;

import lombok.Getter;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.Sequence;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheIterator;
import org.eclipse.jgit.errors.NoMergeBaseException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.merge.MergeResult;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.merge.RecursiveMerger;
import org.eclipse.jgit.merge.ResolveMerger;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Getter
public class GitUtils {
    private Git git;

    public GitUtils(File file) throws IOException {
        git = Git.open(file);
    }

    public ResolveMerger makeMerge(String source, String target) throws GitAPIException, IOException {
        Repository repo = git.getRepository();
//        git.checkout().setName("master").call();

        ObjectId feature = repo.resolve(source);
        ObjectId head = repo.resolve(target);

//        ObjectId feature = repo.resolve("feature");
//        ObjectId head = repo.resolve("master");

        ResolveMerger merger = (ResolveMerger) MergeStrategy.RESOLVE.newMerger(repo, true);
//        merger.setWorkingTreeIterator(new FileTreeIterator(repo));

        boolean isMergedWithoutConflicts = merger.merge(head, feature);

        return merger;
    }

    public Map<String, MergeResult<? extends Sequence>> getConflictChunks(ResolveMerger merger) throws IOException, GitAPIException {
        return merger.getMergeResults();
    }


    public static Map<String, ObjectId> getNonConflictObjects(Git git, ObjectId commit1, ObjectId commit2) throws IOException, GitAPIException, InterruptedException {
        Status status = git.status().call();
        Set<String> conflictList = status.getConflicting();
        System.out.println(conflictList.size());

        Repository repo = git.getRepository();
        Map<String, ObjectId> nonConflictObjects = new HashMap<>();
        System.out.println(repo.getRepositoryState());
        // check out "master"
        try (RevWalk walk = new RevWalk(repo);) {
            RevCommit head = walk.parseCommit(commit1);
            Ref checkout = git.checkout().setAllPaths(true).setStartPoint(head).call();
            System.out.println("Result of checking out master: " + checkout);

            // retrieve the objectId of the latest commit on branch
            ObjectId mergeBase = commit2;

            // perform the actual merge, here we disable FastForward to see the
            // actual merge-commit even though the merge is trivial
            org.eclipse.jgit.api.MergeResult mergeResult = git.merge().
                    include(mergeBase).
                    setCommit(false).
                    setFastForward(MergeCommand.FastForwardMode.NO_FF).
                    //setSquash(false).
                            setMessage("Merged changes").
                    call();


            Arrays.stream(mergeResult.getMergedCommits()).forEach(x -> System.out.println(x.getName()));

            DirCache dirCache = repo.readDirCache();
//        dirCache.getCacheTree(true).

            try (TreeWalk treeWalk = new TreeWalk(repo);) {
                treeWalk.setRecursive(true);
                treeWalk.addTree(new DirCacheIterator(dirCache));

                Set<String> conflictPaths = mergeResult.getConflicts().keySet();
                while (treeWalk.next()) {
                    System.out.println(treeWalk.getPathString() + "\t" + treeWalk.getObjectId(0).getName());

                    if (conflictPaths.stream().noneMatch(x -> x.equals(treeWalk.getPathString())))
                        nonConflictObjects.put(treeWalk.getPathString(), treeWalk.getObjectId(0));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        mergeAbort(git);

        return nonConflictObjects;
    }

    public static void mergeAbort(Git git) throws IOException, InterruptedException {

        File workTree = git.getRepository().getWorkTree();
        System.out.println(workTree.getAbsolutePath());

        ProcessBuilder pb = new ProcessBuilder("git", "status");
        pb.directory(git.getRepository().getDirectory());
        Process pr = pb.start();
        pr.waitFor();
        FileUtils.printErrorMessage(pr);

        pb = new ProcessBuilder("git", "merge", "--abort");
        pb.directory(workTree); // тоже рабочая директория
        pr = pb.start();
        pr.waitFor();
    }

    public static Map<String, ObjectId> getNonConflictObjects2(ResolveMerger merger, ObjectId commit1, ObjectId commit2
            , Git git) throws GitAPIException, IOException {

        Repository repo = git.getRepository();
        Map<String, MergeResult<? extends Sequence>> mergeResult = merger.getMergeResults();
        DirCache dirCache = merger.getRepository().readDirCache();
        Map<String, ObjectId> nonConflictObjects = new HashMap<>();

//        dirCache.getCacheTree(true).

        try (TreeWalk treeWalk = new TreeWalk(repo);) {
            treeWalk.setRecursive(true);
            treeWalk.addTree(new DirCacheIterator(dirCache));

            List<String> conflictPaths = merger.getUnmergedPaths();
            while (treeWalk.next()) {
                System.out.println(treeWalk.getPathString() + "\t" + treeWalk.getObjectId(0).getName());

                if (conflictPaths.stream().noneMatch(x -> x.equals(treeWalk.getPathString())))
                    nonConflictObjects.put(treeWalk.getPathString(), treeWalk.getObjectId(0));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }


        return nonConflictObjects;

    }

    public static Map<String, ObjectId> getNonConflictObjects(ResolveMerger merger, ObjectId commit1, ObjectId commit2
            , Git git) throws IOException, GitAPIException {
        Repository repo = git.getRepository();
        RevWalk walk = new RevWalk(repo);
        RevCommit revCommit1 = walk.parseCommit(commit1);
        RevCommit revCommit2 = walk.parseCommit(commit2);
        RevTree revTree1 = revCommit1.getTree();
        RevTree revTree2 = revCommit2.getTree();
        TreeWalk treeWalk = new TreeWalk(repo);
        treeWalk.addTree(revTree1);
        treeWalk.addTree(revTree2);

        treeWalk.setRecursive(true);

        Map<String, ObjectId> paths = new HashMap<>();
        while (treeWalk.next()) {
            System.out.println(treeWalk.getPathString() + "\t" + treeWalk.getObjectId(0).getName());
            String path = treeWalk.getPathString();

            if (treeWalk.getObjectId(0) != ObjectId.zeroId()) {
                paths.put(path, treeWalk.getObjectId(0));
            } else {
                paths.put(path, treeWalk.getObjectId(1));
            }
        }

        Set<String> mergePaths = merger.getMergeResults().keySet();


        Map<String, ObjectId> nonMergedPaths = paths.entrySet().stream().filter(
                        entry -> !mergePaths.contains(entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        return nonMergedPaths;
    }

    public static Git getGit(String projectPath) throws IOException {
        return Git.open(new File(projectPath));
    }

    public boolean isConflict(String source, String target) throws GitAPIException, IOException {
        ResolveMerger resolveMerger;

        resolveMerger = makeMerge(source, target);


        Map<String, MergeResult<? extends Sequence>> conflicts = resolveMerger.getMergeResults();
        return !conflicts.isEmpty();
    }

    public List<Pair<String, String>> getConflictCommits(int maxConflictingMergeCount) {

        int conflictingMergeCount = 0;
        List<RevCommit> history = getAllMergeCommits();

        List<Pair<String, String>> result = new ArrayList<>();


        for (RevCommit revCommit : history) {
            if (conflictingMergeCount > maxConflictingMergeCount) return result;
            if (revCommit.getParentCount() == 2) {
                String objectId1 = revCommit.getParent(0).getName();
                String objectId2 = revCommit.getParent(1).getName();

                try {
                    // check defined sampling limit
                    if (isConflict(objectId1, objectId2)) {
                        result.add(Pair.of(objectId1, objectId2));
                        conflictingMergeCount++;
                    }
                }catch (Exception e){
                    e.printStackTrace();
                }
            }
        }

        return result;
    }

    protected List<RevCommit> getAllMergeCommits() {

        List<RevCommit> mergeCommits = new ArrayList<>();
        Iterable<RevCommit> logs = new ArrayList<>();

        try {
            logs = git.log().setRevFilter(RevFilter.ONLY_MERGES).call();
        } catch (Exception e) {
            e.printStackTrace();
        }
        int count = 0;

        for (RevCommit rev : logs) {
            //System.out.println("Commit: " + rev /* + ", name: " + rev.getName() + ", id: " + rev.getId().getName() */);
            count++;
            mergeCommits.add(rev);
        }

        return mergeCommits;
    }
}
