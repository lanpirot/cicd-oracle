package ch.unibe.cs.mergeci.model.patterns;

import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.merge.MergeChunk;
import org.eclipse.jgit.merge.MergeResult;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class TheirsOursPattern implements IPattern {
    @Override
    public List<String> apply(MergeResult<RawText> mergeResult, Map<CheckoutCommand.Stage, MergeChunk> chunks) {
        List<String> list = new LinkedList<>();
        MergeChunk theirsChunk = chunks.get(CheckoutCommand.Stage.THEIRS);
        MergeChunk oursChunk = chunks.get(CheckoutCommand.Stage.OURS);
        int seqIndex = oursChunk.getSequenceIndex();
        RawText rawText = mergeResult.getSequences().get(seqIndex);
        for (int i = theirsChunk.getBegin(); i < theirsChunk.getEnd(); i++) {
            list.add(rawText.getString(i));
        }
        for (int i = oursChunk.getBegin(); i < oursChunk.getEnd(); i++) {
            list.add(rawText.getString(i));
        }
        return list;
    }
}
