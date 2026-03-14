package ch.unibe.cs.mergeci.runner;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.Sequence;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.merge.MergeResult;
import org.eclipse.jgit.merge.ResolveMerger;

import java.util.Map;

public interface IMergeAnalyzer {
    Map<String, ObjectId> getNonConflictObjects(Git git, ObjectId commit1, ObjectId commit2);

    Map<String, MergeResult<? extends Sequence>> getConflictChunks(ResolveMerger merger);
}
