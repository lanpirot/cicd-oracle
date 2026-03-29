package ch.unibe.cs.mergeci.model;

import ch.unibe.cs.mergeci.model.patterns.IPattern;
import lombok.Getter;
import lombok.Setter;
import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.merge.MergeChunk;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Getter
@Setter
public class VariantProject implements Cloneable {
    private Path projectPath;
    private List<ConflictFile> classes;


    @Override
    public VariantProject clone() {
        try {
            VariantProject clone = (VariantProject) super.clone();
            List<ConflictFile> clonedClasses = new ArrayList<>();
            for (ConflictFile conflictFile : classes) {
                clonedClasses.add(conflictFile.clone());
            }
            return clone;
        } catch (CloneNotSupportedException e) {
            throw new AssertionError();
        }
    }

    public Map<String, List<String>> extractPatterns() {
        Map<String, List<String>> result = new TreeMap<>();

        for (ConflictFile cls : classes) {
            List<ConflictBlock> conflicts = new ArrayList<>();
            if (cls.getMergeBlocks() != null) {
                for (IMergeBlock block : cls.getMergeBlocks()) {
                    if (block instanceof ConflictBlock conflict) {
                        conflicts.add(conflict);
                    }
                }
            }
            // Sort by line number (OURS begin) to guarantee top-to-bottom order
            // independent of JGit's iteration order.
            conflicts.sort(Comparator.comparingInt(VariantProject::conflictStartLine));

            List<String> patternNames = new ArrayList<>();
            for (ConflictBlock conflict : conflicts) {
                IPattern pattern = conflict.getPattern();
                if (pattern != null) {
                    patternNames.add(pattern.name());
                }
            }

            result.put(cls.getClassPath().toString(), patternNames);
        }

        return result;
    }

    private static int conflictStartLine(ConflictBlock block) {
        MergeChunk ours = block.getChunks().get(CheckoutCommand.Stage.OURS);
        return (ours != null) ? ours.getBegin() : 0;
    }

}
