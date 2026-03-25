package ch.unibe.cs.mergeci.util;

import ch.unibe.cs.mergeci.model.ConflictFile;
import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.Sequence;
import org.eclipse.jgit.merge.MergeChunk;
import org.eclipse.jgit.merge.MergeResult;

import ch.unibe.cs.mergeci.model.ConflictBlock;
import ch.unibe.cs.mergeci.model.IMergeBlock;
import ch.unibe.cs.mergeci.model.NonConflictBlock;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class ProjectBuilderUtils {

    public static ConflictFile getProjectClass(MergeResult<? extends Sequence> mergeResult, String classPath) {
        ConflictFile conflictFile = new ConflictFile();
        conflictFile.setClassPath(Paths.get(classPath));
        List<IMergeBlock> mergeBlockList = new ArrayList<>();
        for (Iterator<MergeChunk> i = mergeResult.iterator(); i.hasNext(); ) {
            MergeChunk mergeChunk = i.next();
            if (mergeChunk.getConflictState() == MergeChunk.ConflictState.NO_CONFLICT) {
                NonConflictBlock nonConflictBlock = new NonConflictBlock((MergeResult<RawText>) mergeResult, mergeChunk);
                mergeBlockList.add(nonConflictBlock);
            } else {
                Map<CheckoutCommand.Stage, MergeChunk> chunks = new HashMap<>();
                chunks.put(CheckoutCommand.Stage.OURS, mergeChunk);
                chunks.put(CheckoutCommand.Stage.BASE, i.next());
                chunks.put(CheckoutCommand.Stage.THEIRS, i.next());
                ConflictBlock conflictBlock = new ConflictBlock((MergeResult<RawText>) mergeResult, chunks);
                mergeBlockList.add(conflictBlock);
            }
        }
        conflictFile.setMergeBlocks(mergeBlockList);
        return conflictFile;
    }
}
