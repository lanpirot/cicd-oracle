package org.example.cicdmergeoracle.cicdMergeTool.model;

import java.util.List;

/**
 * Groups consecutive JGit merge blocks that belong to a single working-tree
 * conflict ({@code <<<<<<<}...{@code >>>>>>>} region).
 * <p>
 * JGit's 3-way merge is finer-grained than Git CLI: it may decompose one
 * working-tree conflict into multiple ConflictBlocks separated by
 * NonConflictBlocks (shared lines). This record captures that grouping so
 * that manual edits can flatten the entire region.
 *
 * @param workingTreeChunkIndex 0-based index of the {@code <<<<<<<} marker in the file
 * @param startBlockIndex       inclusive index into {@code ConflictFile.getMergeBlocks()}
 * @param endBlockIndex         exclusive index into {@code ConflictFile.getMergeBlocks()}
 * @param memberGlobalIndices   global chunk indices of the ConflictBlocks in this group
 */
public record BlockGroup(
        int workingTreeChunkIndex,
        int startBlockIndex,
        int endBlockIndex,
        List<Integer> memberGlobalIndices
) {
    public BlockGroup {
        memberGlobalIndices = List.copyOf(memberGlobalIndices);
    }

    /** True if this group contains more than one ConflictBlock. */
    public boolean isMultiBlock() {
        return memberGlobalIndices.size() > 1;
    }

    /** True if the given block index falls within this group's range. */
    public boolean containsBlockIndex(int blockIndex) {
        return blockIndex >= startBlockIndex && blockIndex < endBlockIndex;
    }
}
