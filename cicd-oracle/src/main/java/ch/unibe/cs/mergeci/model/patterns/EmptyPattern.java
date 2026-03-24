package ch.unibe.cs.mergeci.model.patterns;

import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.merge.MergeChunk;
import org.eclipse.jgit.merge.MergeResult;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class EmptyPattern implements IPattern {
    @Override
    public String name() { return "EMPTY"; }

    @Override
    public List<String> apply(MergeResult<RawText> mergeResult, Map<CheckoutCommand.Stage, MergeChunk> chunks) {
        return Collections.emptyList();
    }
}
