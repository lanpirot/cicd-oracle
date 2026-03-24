package ch.unibe.cs.mergeci.model;

import ch.unibe.cs.mergeci.model.patterns.IPattern;
import lombok.Getter;
import lombok.Setter;
import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.merge.MergeChunk;
import org.eclipse.jgit.merge.MergeResult;

import java.util.List;
import java.util.Map;

@Getter
@Setter
public class ConflictBlock implements IMergeBlock{
    private final MergeResult<RawText> mergeResult;
    private final Map<CheckoutCommand.Stage, MergeChunk> chunks;
    private IPattern pattern;

    public ConflictBlock(MergeResult<RawText> mergeResult, Map<CheckoutCommand.Stage, MergeChunk> chunks) {
        this.mergeResult = mergeResult;
        this.chunks = chunks;
    }

    @Override
    public List<String> getLines() {
        return pattern.apply(mergeResult, chunks);
    }

    @Override
    public ConflictBlock clone() {
        try {
            // TODO: copy mutable state here, so the clone can't change the internals of the original
            return (ConflictBlock) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new AssertionError();
        }
    }
}
