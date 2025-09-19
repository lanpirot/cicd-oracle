package ch.unibe.cs.mergeci.util;

import ch.unibe.cs.mergeci.model.ConflictBlock;
import ch.unibe.cs.mergeci.model.IMergeBlock;
import ch.unibe.cs.mergeci.model.NonConflictBlock;
import ch.unibe.cs.mergeci.model.ProjectClass;
import ch.unibe.cs.mergeci.model.patterns.IPattern;
import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.merge.MergeChunk;
import org.eclipse.jgit.merge.MergeResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class ProjectBuilderUtils {
    private String gitRootPath;

    public ProjectBuilderUtils(String gitRootPath) {
        this.gitRootPath = gitRootPath;
    }

    public static List<ProjectClass> getAllPossibleConflictResolution(ProjectClass projectClass, List<IPattern> patterns) {
        List<List<IMergeBlock>> resolvedMergedConflicts = new ArrayList<>();
        resolveConflicts(projectClass.getMergeBlocks(), new ArrayList<>(), resolvedMergedConflicts, patterns, 0);

        List<ProjectClass> projectClasses = new ArrayList<>();
        for (List<IMergeBlock> mergeBlocks : resolvedMergedConflicts) {
            ProjectClass projectClassResolved = new ProjectClass();
            projectClassResolved.setMergeBlocks(mergeBlocks);
            projectClasses.add(projectClassResolved);
        }
        return projectClasses;
    }

    public static void resolveConflicts(List<IMergeBlock> original, List<IMergeBlock> previous,
                                        List<List<IMergeBlock>> general, List<IPattern> patterns, int counter) {

        if (counter == original.size()-1) {
            general.add(previous);
            return;
        }

        IMergeBlock currentBlock = original.get(counter);
        if (currentBlock instanceof ConflictBlock) {
            for (IPattern pattern : patterns) {
                List<IMergeBlock> currentList = new ArrayList<>(previous);
                ConflictBlock conflictBlock = ((ConflictBlock) currentBlock).clone();
                conflictBlock.setPattern(pattern);
                currentList.add(conflictBlock);
                resolveConflicts(original, currentList, general, patterns, counter + 1);
            }
        } else if (currentBlock instanceof NonConflictBlock) {
            previous.add(currentBlock);
            resolveConflicts(original, previous, general, patterns, counter + 1);
        }
    }

    public static ProjectClass getProjectClass(MergeResult mergeResult, String classPath) {
        ProjectClass projectClass = new ProjectClass();
        projectClass.setProjectName(classPath);
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
        projectClass.setMergeBlocks(mergeBlockList);
        return projectClass;
    }
}
