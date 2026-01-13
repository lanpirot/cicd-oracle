package org.example.demo.cicdMergeTool.model.patterns;

import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.merge.MergeChunk;
import org.eclipse.jgit.merge.MergeResult;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class TheirsPattern implements IPattern {
    @Override
    public List<String> apply(MergeResult<RawText> mergeResult, Map<CheckoutCommand.Stage, MergeChunk> chunks) {
        List<String> list = new LinkedList<>();
        MergeChunk theirs = chunks.get(CheckoutCommand.Stage.THEIRS);
        int seqIndex = theirs.getSequenceIndex();
        RawText rawText = mergeResult.getSequences().get(seqIndex);
        for (int i = theirs.getBegin(); i < theirs.getEnd(); i++) {
            list.add(rawText.getString(i));
        }
        return list;
    }
}
