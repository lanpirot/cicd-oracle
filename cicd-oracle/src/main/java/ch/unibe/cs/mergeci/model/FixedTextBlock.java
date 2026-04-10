package ch.unibe.cs.mergeci.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * An IMergeBlock that returns fixed user-provided lines, bypassing JGit entirely.
 * Used to flatten an entire working-tree conflict region (which JGit may split
 * into multiple ConflictBlocks + NonConflictBlocks) into a single block when the
 * user manually resolves the conflict.
 */
public class FixedTextBlock implements IMergeBlock {
    private final List<String> lines;
    private final int replacedConflictBlockCount;

    public FixedTextBlock(String text, int replacedConflictBlockCount) {
        this.lines = Arrays.asList(text.split("\n", -1));
        this.replacedConflictBlockCount = replacedConflictBlockCount;
    }

    public FixedTextBlock(List<String> lines, int replacedConflictBlockCount) {
        this.lines = List.copyOf(lines);
        this.replacedConflictBlockCount = replacedConflictBlockCount;
    }

    @Override
    public List<String> getLines() {
        return lines;
    }

    /** Number of original ConflictBlocks this block replaces (for globalIdx tracking). */
    public int getReplacedConflictBlockCount() {
        return replacedConflictBlockCount;
    }

    @Override
    public FixedTextBlock clone() {
        return new FixedTextBlock(new ArrayList<>(lines), replacedConflictBlockCount);
    }
}
