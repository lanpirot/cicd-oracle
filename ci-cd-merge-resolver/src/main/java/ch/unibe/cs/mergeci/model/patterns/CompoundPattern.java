package ch.unibe.cs.mergeci.model.patterns;

import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.merge.MergeChunk;
import org.eclipse.jgit.merge.MergeResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Composite pattern that applies multiple atomic patterns in sequence.
 * The resulting lines are the concatenation of all atomic pattern results.
 */
public class CompoundPattern implements IPattern {
    private final List<IPattern> atomicPatterns;

    public CompoundPattern(List<IPattern> atomicPatterns) {
        if (atomicPatterns == null || atomicPatterns.isEmpty()) {
            throw new IllegalArgumentException("Compound pattern must have at least one atomic pattern");
        }
        this.atomicPatterns = new ArrayList<>(atomicPatterns);
    }

    @Override
    public List<String> apply(MergeResult<RawText> mergeResult, Map<CheckoutCommand.Stage, MergeChunk> chunks) {
        List<String> result = new ArrayList<>();
        for (IPattern pattern : atomicPatterns) {
            result.addAll(pattern.apply(mergeResult, chunks));
        }
        return result;
    }

    public List<IPattern> getAtomicPatterns() {
        return new ArrayList<>(atomicPatterns);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("CompoundPattern[");
        for (int i = 0; i < atomicPatterns.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(atomicPatterns.get(i).getClass().getSimpleName());
        }
        sb.append("]");
        return sb.toString();
    }
}
