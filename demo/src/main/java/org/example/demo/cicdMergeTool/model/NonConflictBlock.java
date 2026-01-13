package org.example.demo.cicdMergeTool.model;

import lombok.Getter;
import lombok.Setter;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.merge.MergeChunk;
import org.eclipse.jgit.merge.MergeResult;

import java.util.LinkedList;
import java.util.List;

@Getter
@Setter
public class NonConflictBlock implements IMergeBlock {
    private final MergeResult<RawText> mergeResult;
    private final MergeChunk chunk;

    public NonConflictBlock(MergeResult<RawText> mergeResult, MergeChunk chunk) {
        this.mergeResult = mergeResult;
        this.chunk = chunk;
    }

    @Override
    public List<String> getLines() {
        List<String> list = new LinkedList<>();
        int seqIndex = chunk.getSequenceIndex();
        RawText rawText = mergeResult.getSequences().get(seqIndex);
        for (int i = chunk.getBegin(); i < chunk.getEnd(); i++) {
            list.add(rawText.getString(i));
        }
        return list;
    }

    @Override
    public NonConflictBlock clone() {
        try {
            NonConflictBlock clone = (NonConflictBlock) super.clone();
            return clone;
        } catch (CloneNotSupportedException e) {
            throw new AssertionError();
        }
    }
}
