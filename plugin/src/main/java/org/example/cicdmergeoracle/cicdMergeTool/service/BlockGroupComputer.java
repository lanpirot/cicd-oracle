package org.example.cicdmergeoracle.cicdMergeTool.service;

import org.example.cicdmergeoracle.cicdMergeTool.model.BlockGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Computes {@link BlockGroup}s by matching JGit blocks to working-tree conflict
 * regions. Extracted from MergeResolutionPanel for testability.
 */
public final class BlockGroupComputer {

    private static final Logger LOG = LoggerFactory.getLogger(BlockGroupComputer.class);

    /** Abstraction of one JGit merge block: its OURS-view lines and conflict status. */
    public record BlockEntry(List<String> oursLines, boolean isConflict) {}

    /**
     * Map JGit blocks to working-tree conflict groups using offset-based
     * string matching.
     *
     * @param blocks          JGit blocks as (oursLines, isConflict) pairs
     * @param wtOursSections  OURS text of each working-tree conflict marker region
     * @param globalIdxStart  global chunk index at the start of this file
     * @param filePath        file path for logging only
     * @return list of BlockGroups, one per working-tree conflict
     */
    public static List<BlockGroup> compute(List<BlockEntry> blocks,
                                           List<String> wtOursSections,
                                           int globalIdxStart,
                                           String filePath) {
        if (wtOursSections.isEmpty()) {
            return buildFallbackGroups(blocks, globalIdxStart);
        }

        // Build the full OURS-view of the file and track each block's char range
        StringBuilder fullOurs = new StringBuilder();
        int[] blockStart = new int[blocks.size()];
        int[] blockEnd = new int[blocks.size()];
        int[] blockGlobalIdx = new int[blocks.size()]; // -1 for NCBs
        int gIdx = globalIdxStart;

        for (int i = 0; i < blocks.size(); i++) {
            blockStart[i] = fullOurs.length();
            BlockEntry entry = blocks.get(i);
            if (entry.isConflict()) {
                blockGlobalIdx[i] = gIdx++;
            } else {
                blockGlobalIdx[i] = -1;
            }
            for (int j = 0; j < entry.oursLines().size(); j++) {
                if (fullOurs.length() > 0) fullOurs.append('\n');
                fullOurs.append(entry.oursLines().get(j));
            }
            blockEnd[i] = fullOurs.length();
        }

        String fullOursStr = fullOurs.toString();

        // Find each WT OURS section in the full text and map to block ranges
        List<BlockGroup> groups = new ArrayList<>();
        int searchFrom = 0;

        for (int wt = 0; wt < wtOursSections.size(); wt++) {
            String wtOurs = wtOursSections.get(wt);
            if (wtOurs.isEmpty()) continue;

            int pos = fullOursStr.indexOf(wtOurs, searchFrom);
            if (pos < 0) {
                LOG.warn("Could not find WT OURS section {} in JGit OURS view for {}; skipping",
                        wt, filePath);
                continue;
            }
            int end = pos + wtOurs.length();
            searchFrom = end;

            // Find blocks that overlap [pos, end]
            int startBlock = -1, endBlock = -1;
            List<Integer> cbIndices = new ArrayList<>();
            for (int i = 0; i < blocks.size(); i++) {
                if (blockEnd[i] > pos && blockStart[i] < end) {
                    if (startBlock < 0) startBlock = i;
                    endBlock = i + 1;
                    if (blockGlobalIdx[i] >= 0) {
                        cbIndices.add(blockGlobalIdx[i]);
                    }
                }
            }

            if (startBlock >= 0 && !cbIndices.isEmpty()) {
                groups.add(new BlockGroup(wt, startBlock, endBlock, cbIndices));
            }
        }

        // Assign ungrouped ConflictBlocks to their own single-block groups
        Set<Integer> groupedGlobalIndices = new HashSet<>();
        for (BlockGroup g : groups) {
            groupedGlobalIndices.addAll(g.memberGlobalIndices());
        }
        int ungroupedWtIdx = groups.size();
        for (int i = 0; i < blocks.size(); i++) {
            if (blockGlobalIdx[i] >= 0 && !groupedGlobalIndices.contains(blockGlobalIdx[i])) {
                groups.add(new BlockGroup(ungroupedWtIdx++, i, i + 1,
                        List.of(blockGlobalIdx[i])));
            }
        }

        return groups;
    }

    /** Fallback: each ConflictBlock is its own single-block group. */
    static List<BlockGroup> buildFallbackGroups(List<BlockEntry> blocks, int globalIdxStart) {
        List<BlockGroup> groups = new ArrayList<>();
        int globalIdx = globalIdxStart;
        int wtIdx = 0;
        for (int i = 0; i < blocks.size(); i++) {
            if (blocks.get(i).isConflict()) {
                groups.add(new BlockGroup(wtIdx++, i, i + 1, List.of(globalIdx++)));
            }
        }
        return groups;
    }

    private BlockGroupComputer() {}
}
