package ch.unibe.cs.mergeci.util;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.Sequence;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectStream;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.merge.MergeResult;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.merge.ResolveMerger;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class GitUtils {
    private Git git;

    public GitUtils(File file) throws IOException {
        git = Git.open(file);
    }

    public Map<String, MergeResult<? extends Sequence>> getConflictChunks(String source, String target) throws IOException, GitAPIException {
        Repository repo = git.getRepository();
        git.checkout().setName("master").call();

        ObjectId feature = repo.resolve("feature");
        ObjectId head = repo.resolve("master");

        System.out.println(MergeStrategy.RESOLVE.newMerger(repo, true));
        ResolveMerger merger = (ResolveMerger) MergeStrategy.RESOLVE.newMerger(repo, true);
//        merger.setWorkingTreeIterator(new FileTreeIterator(repo));

        boolean isMergedWithoutConflicts = merger.merge(head, feature);

        return merger.getMergeResults();
    }

   /* public void getConflictsValue() throws GitAPIException, IOException {
        Status status = git.status().call();
        Set<String> conflictList = status.getConflicting();
        System.out.println(conflictList.size());

        Repository repo = git.getRepository();

        System.out.println(repo.getRepositoryState());
        // check out "master"
        Ref checkout = git.checkout().setName("master").call();
        System.out.println("Result of checking out master: " + checkout);

        // retrieve the objectId of the latest commit on branch
        ObjectId mergeBase = repo.resolve("feature");

        // perform the actual merge, here we disable FastForward to see the
        // actual merge-commit even though the merge is trivial
        MergeResult merge = git.merge().
                include(mergeBase).
                setCommit(true).
                setFastForward(MergeCommand.FastForwardMode.NO_FF).
                //setSquash(false).
                        setMessage("Merged changes").
                call();

        System.out.println("Merge-Results for id: " + mergeBase + ": " + merge);
        for (Map.Entry<String, int[][]> entry : merge.getConflicts().entrySet()) {
            System.out.println("Key: " + entry.getKey());
            for (int[] arr : entry.getValue()) {
                System.out.println("value: " + Arrays.toString(arr));
            }
        }
    }*/

    public Map<String, ObjectId> getConflictPaths(ResolveMerger merger, List<String> conflictPaths) throws IOException, GitAPIException {
        Repository repo = git.getRepository();
        RevWalk walk = new RevWalk(repo);
        ObjectId head = repo.resolve("HEAD");
        RevCommit revCommit = walk.parseCommit(head);
        RevTree revTree = revCommit.getTree();
        TreeWalk treeWalk = new TreeWalk(repo);
        treeWalk.addTree(revTree);
        treeWalk.setRecursive(true);
        

        Map<String, ObjectId> paths = new HashMap<>();
        while (treeWalk.next()) {
            paths.put(treeWalk.getPathString(), treeWalk.getObjectId(0));
        }

        Set<String> mergePaths = merger.getMergeResults().keySet();


        Map<String, ObjectId> nonMergedPaths = paths.entrySet().stream().filter(entry -> !mergePaths.contains(entry.getKey())).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        return nonMergedPaths;
    }

    public void saveFile(String path, ObjectStream objectStream) throws IOException {
        File file = new File(path);
        if (file.getParentFile() != null) file.getParentFile().mkdirs();
        file.createNewFile();
        try (FileOutputStream objectOutputStream = new FileOutputStream(file); objectStream) {
            while (objectStream.available() > 0) {
                objectOutputStream.write(objectStream.readAllBytes());
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public ObjectStream getFileFromCommit(ObjectId objectId) throws IOException {
        ObjectLoader objectLoader = git.getRepository().open(objectId);
        return objectLoader.openStream();
    }
}
