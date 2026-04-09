package org.example.cicdmergeoracle.cicdMergeTool.util;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheIterator;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.merge.ResolveMerger;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class GitUtils {
    private static final Logger LOG = LoggerFactory.getLogger(GitUtils.class);

    public static boolean hasConflicts(File project) throws Exception {
        try (Git git = Git.open(project)) {
            return !git.status().call().getConflicting().isEmpty();
        }
    }

    public static String getOurs(File project) throws Exception {
        try (Git git = Git.open(project)) {
            return git.getRepository().resolve("HEAD").getName();
        }
    }

    public static String getTheirs(File project) throws Exception {
        try (Git git = Git.open(project)) {
            return git.getRepository().resolve("MERGE_HEAD").getName();
        }
    }

    public static Map<String, ObjectId> getNonConflictObjectsFromCurrentMerge(Git git) throws IOException {
        Map<String, ObjectId> nonConflictObjects = new HashMap<>();
        Repository repo = git.getRepository();
        ObjectId oursId = repo.resolve("HEAD");
        ObjectId theirsId = repo.resolve("MERGE_HEAD");

        ResolveMerger merger = (ResolveMerger) MergeStrategy.RESOLVE.newMerger(repo, true);
        merger.merge(oursId, theirsId);

        Set<String> conflictPaths = merger.getMergeResults().keySet();
        LOG.debug("Conflict paths: {}", conflictPaths);

        DirCache dirCache = repo.readDirCache();
        try (TreeWalk treeWalk = new TreeWalk(repo)) {
            treeWalk.setRecursive(true);
            treeWalk.addTree(new DirCacheIterator(dirCache));

            while (treeWalk.next()) {
                String path = treeWalk.getPathString();
                if (!conflictPaths.contains(path)) {
                    nonConflictObjects.put(path, treeWalk.getObjectId(0));
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to read non-conflict objects from DirCache", e);
        }

        return nonConflictObjects;
    }
}
