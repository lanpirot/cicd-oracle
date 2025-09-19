package ch.unibe.cs.mergeci.model;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class ProjectClass{
    private String projectName;
    private List<IMergeBlock> mergeBlocks;

    @Override
    public ProjectClass clone() {
        try {
            ProjectClass clone = (ProjectClass) super.clone();
            List<IMergeBlock> newMergeBlocks = new ArrayList<>();
            for (IMergeBlock block : mergeBlocks) {
                newMergeBlocks.add(block.clone());
            }
            return clone;
        } catch (CloneNotSupportedException e) {
            throw new AssertionError();
        }
    }
}
