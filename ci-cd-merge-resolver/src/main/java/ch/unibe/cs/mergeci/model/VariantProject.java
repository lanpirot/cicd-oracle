package ch.unibe.cs.mergeci.model;

import ch.unibe.cs.mergeci.model.patterns.IPattern;
import lombok.Getter;
import lombok.Setter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        Map<String, List<String>> result = new HashMap<>();

        for (ConflictFile cls : classes) {
            List<String> patternNames = new ArrayList<>();

            if (cls.getMergeBlocks() != null) {
                for (IMergeBlock block : cls.getMergeBlocks()) {
                    if (block instanceof ConflictBlock conflict) {
                        IPattern pattern = conflict.getPattern();
                        if (pattern != null) {
                            patternNames.add(pattern.name());
                        }
                    }
                }
            }

            result.put(cls.getClassPath().toString(), patternNames);
        }

        return result;
    }

}
