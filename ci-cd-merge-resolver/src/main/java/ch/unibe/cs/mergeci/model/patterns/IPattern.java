package ch.unibe.cs.mergeci.model.patterns;


import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.merge.MergeChunk;
import org.eclipse.jgit.merge.MergeResult;

import java.util.List;
import java.util.Map;

public interface IPattern {
    List<String> apply(MergeResult<RawText> mergeResult, Map<CheckoutCommand.Stage, MergeChunk> chunks);

    /** Canonical name of this pattern, e.g. "OURS", "THEIRS", or "OURS:BASE" for a compound. */
    String name();
}
