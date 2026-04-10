package org.example.cicdmergeoracle.cicdMergeTool.service;

import org.example.cicdmergeoracle.cicdMergeTool.model.BlockGroup;
import org.example.cicdmergeoracle.cicdMergeTool.service.BlockGroupComputer.BlockEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BlockGroupComputerTest {

    // ---- helpers ----

    private static BlockEntry conflict(String... lines) {
        return new BlockEntry(List.of(lines), true);
    }

    private static BlockEntry nonConflict(String... lines) {
        return new BlockEntry(List.of(lines), false);
    }

    // ---- fallback ----

    @Test
    void fallback_eachConflictGetsOwnGroup() {
        // NCB - CB - NCB - CB
        List<BlockEntry> blocks = List.of(
                nonConflict("shared1"),
                conflict("oursA"),
                nonConflict("shared2"),
                conflict("oursB")
        );

        List<BlockGroup> groups = BlockGroupComputer.compute(
                blocks, List.of(), 0, "File.java");

        assertEquals(2, groups.size());
        // First conflict at block index 1
        assertEquals(1, groups.get(0).startBlockIndex());
        assertEquals(2, groups.get(0).endBlockIndex());
        assertEquals(List.of(0), groups.get(0).memberGlobalIndices());
        // Second conflict at block index 3
        assertEquals(3, groups.get(1).startBlockIndex());
        assertEquals(4, groups.get(1).endBlockIndex());
        assertEquals(List.of(1), groups.get(1).memberGlobalIndices());
    }

    @Test
    void fallback_respectsGlobalIdxStart() {
        List<BlockEntry> blocks = List.of(conflict("a"), conflict("b"));
        List<BlockGroup> groups = BlockGroupComputer.compute(
                blocks, List.of(), 5, "File.java");

        assertEquals(2, groups.size());
        assertEquals(List.of(5), groups.get(0).memberGlobalIndices());
        assertEquals(List.of(6), groups.get(1).memberGlobalIndices());
    }

    // ---- single conflict, single JGit block (1:1 mapping) ----

    @Test
    void singleConflict_singleBlock() {
        List<BlockEntry> blocks = List.of(
                nonConflict("header"),
                conflict("oursLine"),
                nonConflict("footer")
        );
        List<String> wtOurs = List.of("oursLine");

        List<BlockGroup> groups = BlockGroupComputer.compute(
                blocks, wtOurs, 0, "File.java");

        assertEquals(1, groups.size());
        BlockGroup g = groups.get(0);
        assertEquals(0, g.workingTreeChunkIndex());
        assertEquals(1, g.startBlockIndex());
        assertEquals(2, g.endBlockIndex());
        assertEquals(List.of(0), g.memberGlobalIndices());
        assertFalse(g.isMultiBlock());
    }

    // ---- JGit splits one WT conflict into two ConflictBlocks with NCB between ----

    @Test
    void oneWtConflict_twoJGitConflictBlocks() {
        // JGit sees: CB(oursA) - NCB(shared) - CB(oursB)
        // Working tree sees one conflict with OURS = "oursA\nshared\noursB"
        List<BlockEntry> blocks = List.of(
                conflict("oursA"),
                nonConflict("shared"),
                conflict("oursB")
        );
        List<String> wtOurs = List.of("oursA\nshared\noursB");

        List<BlockGroup> groups = BlockGroupComputer.compute(
                blocks, wtOurs, 0, "File.java");

        assertEquals(1, groups.size());
        BlockGroup g = groups.get(0);
        assertEquals(0, g.workingTreeChunkIndex());
        assertEquals(0, g.startBlockIndex());
        assertEquals(3, g.endBlockIndex());
        assertEquals(List.of(0, 1), g.memberGlobalIndices());
        assertTrue(g.isMultiBlock());
    }

    // ---- two WT conflicts, each with one JGit block ----

    @Test
    void twoWtConflicts_eachSingleBlock() {
        List<BlockEntry> blocks = List.of(
                nonConflict("header"),
                conflict("oursA"),
                nonConflict("between"),
                conflict("oursB"),
                nonConflict("footer")
        );
        List<String> wtOurs = List.of("oursA", "oursB");

        List<BlockGroup> groups = BlockGroupComputer.compute(
                blocks, wtOurs, 0, "File.java");

        assertEquals(2, groups.size());
        assertEquals(List.of(0), groups.get(0).memberGlobalIndices());
        assertEquals(1, groups.get(0).startBlockIndex());
        assertEquals(List.of(1), groups.get(1).memberGlobalIndices());
        assertEquals(3, groups.get(1).startBlockIndex());
    }

    // ---- multi-line OURS content ----

    @Test
    void multiLineOurs() {
        List<BlockEntry> blocks = List.of(
                conflict("line1", "line2", "line3")
        );
        List<String> wtOurs = List.of("line1\nline2\nline3");

        List<BlockGroup> groups = BlockGroupComputer.compute(
                blocks, wtOurs, 0, "File.java");

        assertEquals(1, groups.size());
        assertEquals(List.of(0), groups.get(0).memberGlobalIndices());
    }

    // ---- mixed: one grouped WT conflict + one simple WT conflict ----

    @Test
    void mixedGroupedAndSimple() {
        // WT conflict 0: spans CB0 + NCB + CB1  (OURS = "a\nshared\nb")
        // WT conflict 1: just CB2  (OURS = "c")
        List<BlockEntry> blocks = List.of(
                conflict("a"),
                nonConflict("shared"),
                conflict("b"),
                nonConflict("gap"),
                conflict("c")
        );
        List<String> wtOurs = List.of("a\nshared\nb", "c");

        List<BlockGroup> groups = BlockGroupComputer.compute(
                blocks, wtOurs, 0, "File.java");

        assertEquals(2, groups.size());

        BlockGroup g0 = groups.get(0);
        assertEquals(0, g0.startBlockIndex());
        assertEquals(3, g0.endBlockIndex());
        assertEquals(List.of(0, 1), g0.memberGlobalIndices());

        BlockGroup g1 = groups.get(1);
        assertEquals(4, g1.startBlockIndex());
        assertEquals(5, g1.endBlockIndex());
        assertEquals(List.of(2), g1.memberGlobalIndices());
    }

    // ---- WT section not found — ungrouped blocks get single-block groups ----

    @Test
    void missingWtSection_ungroupedBlocksGetFallback() {
        List<BlockEntry> blocks = List.of(
                conflict("oursA"),
                nonConflict("between"),
                conflict("oursB")
        );
        // WT has a section that doesn't match anything in the JGit view
        List<String> wtOurs = List.of("noMatch");

        List<BlockGroup> groups = BlockGroupComputer.compute(
                blocks, wtOurs, 0, "File.java");

        // Both conflicts become ungrouped single-block groups
        assertEquals(2, groups.size());
        assertEquals(List.of(0), groups.get(0).memberGlobalIndices());
        assertEquals(List.of(1), groups.get(1).memberGlobalIndices());
    }

    // ---- globalIdxStart offset with grouped blocks ----

    @Test
    void globalIdxStart_offsetApplied() {
        List<BlockEntry> blocks = List.of(
                conflict("x"),
                nonConflict("mid"),
                conflict("y")
        );
        List<String> wtOurs = List.of("x\nmid\ny");

        List<BlockGroup> groups = BlockGroupComputer.compute(
                blocks, wtOurs, 10, "File.java");

        assertEquals(1, groups.size());
        assertEquals(List.of(10, 11), groups.get(0).memberGlobalIndices());
    }

    // ---- three JGit blocks in one WT conflict ----

    @Test
    void threeConflictBlocksInOneGroup() {
        List<BlockEntry> blocks = List.of(
                conflict("a"),
                nonConflict("s1"),
                conflict("b"),
                nonConflict("s2"),
                conflict("c")
        );
        List<String> wtOurs = List.of("a\ns1\nb\ns2\nc");

        List<BlockGroup> groups = BlockGroupComputer.compute(
                blocks, wtOurs, 0, "File.java");

        assertEquals(1, groups.size());
        BlockGroup g = groups.get(0);
        assertEquals(0, g.startBlockIndex());
        assertEquals(5, g.endBlockIndex());
        assertEquals(List.of(0, 1, 2), g.memberGlobalIndices());
    }

    // ---- only non-conflict blocks ----

    @Test
    void noConflictBlocks() {
        List<BlockEntry> blocks = List.of(
                nonConflict("a"), nonConflict("b")
        );
        List<String> wtOurs = List.of();

        List<BlockGroup> groups = BlockGroupComputer.compute(
                blocks, wtOurs, 0, "File.java");

        assertTrue(groups.isEmpty());
    }

    // ---- partial WT match: one found, one not ----

    @Test
    void partialMatch_foundAndNotFound() {
        List<BlockEntry> blocks = List.of(
                conflict("alpha"),
                nonConflict("gap"),
                conflict("beta")
        );
        // First WT conflict matches, second doesn't
        List<String> wtOurs = List.of("alpha", "noMatch");

        List<BlockGroup> groups = BlockGroupComputer.compute(
                blocks, wtOurs, 0, "File.java");

        // "alpha" matched → grouped; "beta" unmatched → ungrouped fallback
        assertEquals(2, groups.size());
        assertEquals(List.of(0), groups.get(0).memberGlobalIndices());
        assertEquals(0, groups.get(0).startBlockIndex());
        assertEquals(List.of(1), groups.get(1).memberGlobalIndices());
        assertEquals(2, groups.get(1).startBlockIndex());
    }
}
