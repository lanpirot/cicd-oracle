package ch.unibe.cs.mergeci.util;

import ch.unibe.cs.mergeci.experimentSetup.SimpleProgressMonitor;
import ch.unibe.cs.mergeci.util.model.MergeInfo;
import lombok.Getter;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.Sequence;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheIterator;
import org.eclipse.jgit.errors.NoMergeBaseException;
import org.eclipse.jgit.internal.storage.file.WindowCache;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.merge.MergeChunk;
import org.eclipse.jgit.merge.MergeResult;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.merge.ResolveMerger;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.storage.file.WindowCacheConfig;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Getter
public class GitUtils {

    public Git GitUtils(File file) throws IOException {
        return Git.open(file);
    }

    public Git GitUtils(String file) throws IOException {
        return Git.open(new File(file));
    }

    /**
     * Creates and executes a merge between two branches using the RECURSIVE strategy.
     * The RECURSIVE strategy supports multiple merge bases (criss-cross merges) by
     * recursively merging the merge bases into a virtual common ancestor.
     *
     * @param oursBranch The "ours" branch/commit identifier
     * @param theirsBranch The "theirs" branch/commit identifier
     * @param git The Git repository
     * @return ResolveMerger instance with merge results
     * @throws IOException If repository access fails
     */
    public static ResolveMerger makeMerge(String oursBranch, String theirsBranch, Git git) throws IOException {
        Repository repo = git.getRepository();

        ObjectId oursObject = repo.resolve(oursBranch);
        ObjectId theirsObject = repo.resolve(theirsBranch);

        // Use RECURSIVE strategy to support multiple merge bases (criss-cross merges)
        // RECURSIVE handles complex merge scenarios by recursively merging merge bases
        ResolveMerger merger = (ResolveMerger) MergeStrategy.RECURSIVE.newMerger(repo, true);

        boolean isMergedWithoutConflicts = merger.merge(oursObject, theirsObject);

        return merger;
    }

    public static Map<String, MergeResult<? extends Sequence>> getMergeResults(ResolveMerger merger) throws IOException, GitAPIException {
        return merger.getMergeResults();
    }


    public static Map<String, ObjectId> getNonConflictObjects(Git git, ObjectId commit1, ObjectId commit2) throws IOException, InterruptedException {
        Map<String, ObjectId> nonConflictObjects = new HashMap<>();
        Repository repo = git.getRepository();
        String tempBranch = "temp_merge_branch";
        Ref originalBranch = repo.findRef(repo.getBranch());

        try (RevWalk walk = new RevWalk(repo)) {
            RevCommit head = walk.parseCommit(commit1);

            // Checkout commit1 in a temp branch

            Ref checkout = git.checkout()
                    .setName(tempBranch)
                    .setForced(true)
                    .setCreateBranch(true)
                    .setStartPoint(head)
                    .call();
            // Quiet checkout - no console output

            // Perform the merge
            org.eclipse.jgit.api.MergeResult mergeResult = git.merge()
                    .include(commit2)
                    .setCommit(false)
                    .setFastForward(MergeCommand.FastForwardMode.NO_FF)
                    .setMessage("Temporary merge")
                    .call();

            // Conflicting files
            Set<String> conflictPaths = mergeResult.getConflicts() != null ?
                    mergeResult.getConflicts().keySet() : Collections.emptySet();
            // Quiet - no console output for conflicts

            // Iterate over DirCache to find automatically merged files
            DirCache dirCache = repo.readDirCache();
            try (TreeWalk treeWalk = new TreeWalk(repo)) {
                treeWalk.setRecursive(true);
                treeWalk.addTree(new DirCacheIterator(dirCache));

                while (treeWalk.next()) {
                    String path = treeWalk.getPathString();
                    if (!conflictPaths.contains(path)) {
                        nonConflictObjects.put(path, treeWalk.getObjectId(0));
//                        System.out.println("Auto-merged: " + path + "\t" + treeWalk.getObjectId(0).getName());
                    }
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            // Abort merge and delete temp branch
            try {
                git.reset().setMode(ResetCommand.ResetType.HARD).call();
                git.checkout().setName(originalBranch.getName()).setForced(true).call();
                git.branchDelete().setBranchNames(tempBranch).setForce(true).call();
            } catch (GitAPIException e) {
                throw new RuntimeException(e);
            }
        }

        return nonConflictObjects;
    }



    public static Map<String, ObjectId> getNonConflictObjects2(ResolveMerger merger, ObjectId commit1, ObjectId commit2
            , Git git) throws GitAPIException, IOException {

        Repository repo = git.getRepository();
        Map<String, MergeResult<? extends Sequence>> mergeResult = merger.getMergeResults();
        DirCache dirCache = merger.getRepository().readDirCache();
        merger.getResultTreeId();
        Map<String, ObjectId> nonConflictObjects = new HashMap<>();

//        dirCache.getCacheTree(true).

        try (TreeWalk treeWalk = new TreeWalk(repo);) {
            treeWalk.setRecursive(true);
            treeWalk.addTree(new DirCacheIterator(dirCache));

            List<String> conflictPaths = merger.getUnmergedPaths();
            System.out.println("Non Conflict Paths:");
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

    public static Git getGit(Path projectPath) throws IOException {
        return Git.open(projectPath.toFile());
    }

    public static boolean isConflict(String source, String target, Git git) throws GitAPIException, IOException {
        ResolveMerger resolveMerger;

        resolveMerger = makeMerge(source, target, git);


        Map<String, MergeResult<? extends Sequence>> conflicts = resolveMerger.getMergeResults();
        return !conflicts.isEmpty();
    }

    public static Map<String, Integer> countConflictChunks(String source, String target, Git git) throws IOException {
        ResolveMerger resolveMerger;
        resolveMerger = makeMerge(source, target, git);

        Map<String, Integer> conflictingFiles = new HashMap<>();


        int counter = 0;
        Map<String, MergeResult<? extends Sequence>> conflicts = resolveMerger.getMergeResults();
        for (Map.Entry<String, MergeResult<? extends Sequence>> mergeResult : conflicts.entrySet()) {
            for (MergeChunk mergeChunk : mergeResult.getValue()) {
                if (mergeChunk.getConflictState() != MergeChunk.ConflictState.NO_CONFLICT) {
                    counter++;
                }
            }

            conflictingFiles.put(mergeResult.getKey(), counter / 3);
            counter = 0;
        }


        return conflictingFiles;
    }

    /**
     * Get the total number of conflict chunks across all conflicting files between two commits.
     * This is a convenience method that wraps countConflictChunks() and sums all values.
     *
     * @param repo Path to the repository
     * @param parent1 First parent commit hash
     * @param parent2 Second parent commit hash
     * @return Total number of conflict chunks
     * @throws IOException if git operations fail
     */
    public static int getTotalConflictChunks(Path repo, String parent1, String parent2) throws IOException {
        try (Git git = getGit(repo)) {
            Map<String, Integer> map = countConflictChunks(parent1, parent2, git);
            return map.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    public static List<MergeInfo> getConflictCommits(int maxConflictingMergeCount, Git git) {

        int conflictingMergeCount = 0;
        List<RevCommit> history = getAllMergeCommits(git);

        List<MergeInfo> mergeInfos = new ArrayList<>();

        for (RevCommit revCommit : history) {
            if (conflictingMergeCount >= maxConflictingMergeCount) return mergeInfos;
            if (revCommit.getParentCount() == 2) {
                RevCommit objectId1 = revCommit.getParent(0);
                RevCommit objectId2 = revCommit.getParent(1);

                try {
                    Map<String, Integer> conflictFiles = countConflictChunks(objectId1.getName(), objectId2.getName(), git);

                    // check defined sampling limit
                    if (!conflictFiles.isEmpty()) {
                        MergeInfo mergeInfo = new MergeInfo(revCommit,objectId1, objectId2, conflictFiles);
                        mergeInfos.add(mergeInfo);
                        conflictingMergeCount++;
                    }
                } catch (NoMergeBaseException e) {
                    // Rare: Skip merges where even RECURSIVE strategy cannot find a merge base
                    // This should be very uncommon now that we use RECURSIVE merge strategy
                    System.err.println("Warning: No merge base found for commit " + revCommit.name() +
                                       " (even with RECURSIVE strategy). Skipping.");
                } catch (Exception e) {
                    System.err.println("Warning: Failed to analyze merge commit " + revCommit.name() + ": " + e.getMessage());
                    e.printStackTrace();
                    // Continue processing other commits
                }
            }
        }

        return mergeInfos;
    }

    protected static List<RevCommit> getAllMergeCommits(Git git) {

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

    /**
     * Get the total count of merge commits in the repository
     * @param git the Git repository
     * @return the total number of merge commits
     */
    public static int getTotalMergeCount(Git git) {
        return getAllMergeCommits(git).size();
    }

    public static Map<String, ObjectId> getObjectsFromCommit(String commitHash, Git git) throws IOException, GitAPIException {
        Repository repo = git.getRepository();
        RevWalk walk = new RevWalk(repo);
        ObjectId objectId = repo.resolve(commitHash);
        RevCommit commit = walk.parseCommit(objectId);
        RevTree revTree = commit.getTree();
        TreeWalk treeWalk = new TreeWalk(repo);
        treeWalk.addTree(revTree);

        treeWalk.setRecursive(true);

        Map<String, ObjectId> map = new HashMap<>();
        while (treeWalk.next()) {
//            System.out.println(treeWalk.getPathString() + "\t" + treeWalk.getObjectId(0).getName());
            String path = treeWalk.getPathString();

            map.put(path, treeWalk.getObjectId(0));
        }

        treeWalk.close();
        walk.close();

        return map;
    }

    public static QuietProgressMonitor cloneRepo(Path folderToClone, String url) {

        // then clone
        QuietProgressMonitor monitor = new QuietProgressMonitor();
        try (Git result = Git.cloneRepository()
                .setURI(url)
                .setDirectory(folderToClone.toFile())
                .setProgressMonitor(monitor)
                .call()) {
            // Note: the call() returns an opened repository already which needs to be closed to avoid file handle leaks!
            // Repository info available via result.getRepository().getDirectory() if needed

        } catch (GitAPIException e) {
            throw new RuntimeException(e);
        }
        RepositoryCache.clear();
        WindowCache.reconfigure(new WindowCacheConfig());

        return monitor;
    }
}
