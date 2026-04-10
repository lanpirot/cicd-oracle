package org.example.cicdmergeoracle.cicdMergeTool.service;

import ch.unibe.cs.mergeci.model.ConflictBlock;
import ch.unibe.cs.mergeci.model.FixedTextBlock;
import ch.unibe.cs.mergeci.model.IMergeBlock;
import org.example.cicdmergeoracle.cicdMergeTool.model.BlockGroup;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared logic for overlaying manual-pinned chunks onto a file's merge blocks.
 * Used by both {@link PluginOrchestrator} (variant builds) and the UI layer
 * (applying a variant to the working tree).
 */
public final class ManualPinOverlay {

    /** Result of applying the overlay to one file's blocks. */
    public record Result(List<IMergeBlock> blocks, int globalIdx) {}

    /**
     * Walk a file's merge blocks and replace manual-pinned groups with
     * {@link FixedTextBlock}s. Non-manual {@link ConflictBlock}s are either
     * kept as-is (when {@code patterns} is null) or cloned with the
     * corresponding pattern applied.
     *
     * @param originalBlocks the file's current merge blocks
     * @param globalIdxStart global chunk index at the start of this file
     * @param manualTexts    map from global chunk index to manual text
     * @param groupMap       map from global chunk index to its BlockGroup
     * @param patterns       if non-null, apply pattern[i] to the i-th ConflictBlock;
     *                       if null, keep ConflictBlocks unchanged
     * @return the new block list and the global index after this file
     */
    public static Result apply(List<IMergeBlock> originalBlocks,
                               int globalIdxStart,
                               Map<Integer, String> manualTexts,
                               Map<Integer, BlockGroup> groupMap,
                               List<String> patterns) {
        List<IMergeBlock> newBlocks = new ArrayList<>();
        int globalIdx = globalIdxStart;
        int patternIdx = 0;

        // Collect manual groups whose members fall in this file's range
        int fileCBCount = (int) originalBlocks.stream()
                .filter(b -> b instanceof ConflictBlock).count();
        Set<BlockGroup> manualGroups = new HashSet<>();
        for (int gi = globalIdxStart; gi < globalIdxStart + fileCBCount; gi++) {
            if (manualTexts.containsKey(gi)) {
                BlockGroup g = groupMap.get(gi);
                if (g != null) manualGroups.add(g);
            }
        }

        int blockIdx = 0;
        while (blockIdx < originalBlocks.size()) {
            // Check if this block starts a manual group
            BlockGroup manualGroup = null;
            for (BlockGroup mg : manualGroups) {
                if (mg.startBlockIndex() == blockIdx) {
                    manualGroup = mg;
                    break;
                }
            }

            if (manualGroup != null) {
                int primaryIdx = manualGroup.memberGlobalIndices().get(0);
                String text = manualTexts.get(primaryIdx);
                newBlocks.add(new FixedTextBlock(text, manualGroup.memberGlobalIndices().size()));
                manualGroups.remove(manualGroup);
                for (int i = manualGroup.startBlockIndex(); i < manualGroup.endBlockIndex(); i++) {
                    if (originalBlocks.get(i) instanceof ConflictBlock) {
                        patternIdx++;
                        globalIdx++;
                    }
                }
                blockIdx = manualGroup.endBlockIndex();
                continue;
            }

            IMergeBlock block = originalBlocks.get(blockIdx);
            if (block instanceof ConflictBlock cb) {
                if (patterns != null) {
                    ConflictBlock clone = cb.clone();
                    clone.setPattern(
                            ch.unibe.cs.mergeci.model.patterns.PatternFactory.fromName(
                                    patterns.get(patternIdx)));
                    newBlocks.add(clone);
                } else {
                    newBlocks.add(block);
                }
                patternIdx++;
                globalIdx++;
            } else {
                newBlocks.add(block);
            }
            blockIdx++;
        }

        return new Result(newBlocks, globalIdx);
    }

    private ManualPinOverlay() {}
}
