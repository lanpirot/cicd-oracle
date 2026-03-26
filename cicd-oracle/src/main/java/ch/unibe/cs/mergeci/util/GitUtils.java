package ch.unibe.cs.mergeci.util;

import ch.unibe.cs.mergeci.config.AppConfig;
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

@Getter
public class GitUtils {

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

        merger.merge(oursObject, theirsObject);

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

    /** Conflict counts derived in a single merge pass. */
    public record ConflictStats(int totalFiles, int javaFiles, int totalChunks) {}

    /**
     * Compute all conflict counts (files, Java files, chunks) in one git merge pass.
     */
    public static ConflictStats getConflictStats(Path repo, String parent1, String parent2) throws IOException {
        try (Git git = getGit(repo)) {
            Map<String, Integer> map = countConflictChunks(parent1, parent2, git);
            int totalChunks = map.values().stream().mapToInt(Integer::intValue).sum();
            int totalFiles = map.size();
            int javaFiles = (int) map.keySet().stream().filter(p -> p.endsWith(".java")).count();
            return new ConflictStats(totalFiles, javaFiles, totalChunks);
        }
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
        return getConflictCommits(maxConflictingMergeCount, 0, git);
    }

    /**
     * Returns up to {@code maxConflictingMergeCount} merge commits that have conflicts,
     * skipping the first {@code skip} conflict-having merges in history.
     * Used by the resampling loop to fetch successive non-overlapping batches.
     */
    public static List<MergeInfo> getConflictCommits(int maxConflictingMergeCount, int skip, Git git) {

        int conflictingMergeCount = 0;
        int skipped = 0;
        List<RevCommit> history = getAllMergeCommits(git);

        List<MergeInfo> mergeInfos = new ArrayList<>();

        for (RevCommit revCommit : history) {
            if (conflictingMergeCount >= maxConflictingMergeCount) return mergeInfos;
            if (revCommit.getParentCount() == 2) {
                RevCommit objectId1 = revCommit.getParent(0);
                RevCommit objectId2 = revCommit.getParent(1);

                try {
                    Map<String, Integer> conflictFiles = countConflictChunks(objectId1.getName(), objectId2.getName(), git);

                    if (!conflictFiles.isEmpty()) {
                        if (skipped < skip) {
                            skipped++;
                            continue;
                        }
                        MergeInfo mergeInfo = new MergeInfo(revCommit, objectId1, objectId2, conflictFiles);
                        mergeInfos.add(mergeInfo);
                        conflictingMergeCount++;
                    }
                } catch (NoMergeBaseException e) {
                    // Rare: Skip merges where even RECURSIVE strategy cannot find a merge base
                    System.err.println("Warning: No merge base found for commit " + revCommit.name() +
                                       " (even with RECURSIVE strategy). Skipping.");
                } catch (Exception e) {
                    System.err.println("Warning: Failed to analyze merge commit " + revCommit.name() + ": " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }

        return mergeInfos;
    }

    protected static List<RevCommit> getAllMergeCommits(Git git) {

        List<RevCommit> mergeCommits = new ArrayList<>();
        Iterable<RevCommit> logs = new ArrayList<>();

        try {
            logs = git.log().all().setRevFilter(RevFilter.ONLY_MERGES).call();
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

    /**
     * Get the total number of commits reachable from HEAD.
     */
    public static int getTotalCommitCount(Git git) {
        try {
            int count = 0;
            for (RevCommit ignored : git.log().call()) count++;
            return count;
        } catch (GitAPIException e) {
            System.err.println("Warning: Failed to count commits: " + e.getMessage());
            return -1;
        }
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

    /**
     * Returns the two parent commit SHAs of a merge commit.
     * @throws IllegalStateException if the commit does not have exactly 2 parents
     */
    public static String[] getParentCommits(Path repoPath, String mergeCommit) throws Exception {
        try (Git git = Git.open(repoPath.toFile());
             RevWalk walk = new RevWalk(git.getRepository())) {
            ObjectId id = git.getRepository().resolve(mergeCommit);
            RevCommit commit = walk.parseCommit(id);
            RevCommit[] parents = commit.getParents();
            if (parents.length != 2) throw new IllegalStateException(
                "Expected 2 parents for " + mergeCommit + " but got " + parents.length);
            return new String[]{ parents[0].getName(), parents[1].getName() };
        }
    }

    public static QuietProgressMonitor cloneRepo(Path folderToClone, String url) {

        // then clone
        QuietProgressMonitor monitor = new QuietProgressMonitor();
        try (Git result = Git.cloneRepository()
                .setURI(url)
                .setDirectory(folderToClone.toFile())
                .setProgressMonitor(monitor)
                .setTimeout(AppConfig.CLONE_SOCKET_TIMEOUT_SECONDS)
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
