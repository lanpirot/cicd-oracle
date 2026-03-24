package ch.unibe.cs.mergeci.model.patterns;

import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.merge.MergeChunk;
import org.eclipse.jgit.merge.MergeResult;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CompoundPatternTest {

    @Test
    void testCompoundPatternRequiresAtLeastOne() {
        // Empty list should throw
        assertThrows(IllegalArgumentException.class, () -> {
            new CompoundPattern(new ArrayList<>());
        });

        // Null should throw
        assertThrows(IllegalArgumentException.class, () -> {
            new CompoundPattern(null);
        });
    }

    @Test
    void testCompoundPatternWithSingleAtomic() {
        List<IPattern> patterns = List.of(new OursPattern());
        CompoundPattern compound = new CompoundPattern(patterns);

        assertEquals(1, compound.getAtomicPatterns().size());
        assertInstanceOf(OursPattern.class, compound.getAtomicPatterns().get(0));
    }

    @Test
    void testCompoundPatternWithMultipleAtomics() {
        List<IPattern> patterns = List.of(
            new OursPattern(),
            new TheirsPattern(),
            new BasePattern()
        );
        CompoundPattern compound = new CompoundPattern(patterns);

        assertEquals(3, compound.getAtomicPatterns().size());
        assertInstanceOf(OursPattern.class, compound.getAtomicPatterns().get(0));
        assertInstanceOf(TheirsPattern.class, compound.getAtomicPatterns().get(1));
        assertInstanceOf(BasePattern.class, compound.getAtomicPatterns().get(2));
    }

    @Test
    void testCompoundPatternApplyConcatenates() {
        // Create mock merge result and chunks
        MergeResult<RawText> mergeResult = createMockMergeResult();
        Map<CheckoutCommand.Stage, MergeChunk> chunks = createMockChunks();

        // Create compound pattern with OURS then THEIRS
        List<IPattern> patterns = List.of(new OursPattern(), new TheirsPattern());
        CompoundPattern compound = new CompoundPattern(patterns);

        // Apply pattern
        List<String> result = compound.apply(mergeResult, chunks);

        // Should have lines from OURS followed by lines from THEIRS
        assertNotNull(result);
        assertTrue(result.size() > 0);

        // Result should contain both OURS and THEIRS content
        String combined = String.join("\n", result);
        assertTrue(combined.contains("OURS") || combined.contains("Feature A"));
        assertTrue(combined.contains("THEIRS") || combined.contains("Feature B"));
    }

    @Test
    void testCompoundPatternWithEmptyPattern() {
        // Create mock merge result and chunks
        MergeResult<RawText> mergeResult = createMockMergeResult();
        Map<CheckoutCommand.Stage, MergeChunk> chunks = createMockChunks();

        // Compound with OURS and EMPTY
        List<IPattern> patterns = List.of(new OursPattern(), new EmptyPattern());
        CompoundPattern compound = new CompoundPattern(patterns);

        List<String> result = compound.apply(mergeResult, chunks);

        // Should have OURS lines plus nothing from EMPTY
        assertNotNull(result);
        // EmptyPattern adds nothing, so result size = OURS lines only
    }

    @Test
    void testCompoundPatternGetAtomicPatternsReturnsDefensiveCopy() {
        List<IPattern> patterns = new ArrayList<>();
        patterns.add(new OursPattern());
        patterns.add(new TheirsPattern());

        CompoundPattern compound = new CompoundPattern(patterns);
        List<IPattern> retrieved = compound.getAtomicPatterns();

        // Modifying retrieved list should not affect compound
        retrieved.clear();
        assertEquals(2, compound.getAtomicPatterns().size());
    }

    @Test
    void testCompoundPatternToString() {
        List<IPattern> patterns = List.of(
            new OursPattern(),
            new TheirsPattern()
        );
        CompoundPattern compound = new CompoundPattern(patterns);

        String str = compound.toString();
        assertTrue(str.contains("CompoundPattern"));
        assertTrue(str.contains("OURS"));
        assertTrue(str.contains("THEIRS"));
    }

    /**
     * Create a mock MergeResult for testing.
     * This creates a simple merge with OURS, THEIRS, and BASE chunks.
     */
    private MergeResult<RawText> createMockMergeResult() {
        // Create RawText sequences for OURS, BASE, and THEIRS
        String oursContent = "    // Feature A\n    return a + b;\n";
        String baseContent = "    return a + b;\n";
        String theirsContent = "    // Feature B\n    return a + b;\n";

        RawText oursText = new RawText(oursContent.getBytes(StandardCharsets.UTF_8));
        RawText baseText = new RawText(baseContent.getBytes(StandardCharsets.UTF_8));
        RawText theirsText = new RawText(theirsContent.getBytes(StandardCharsets.UTF_8));

        // Create mock MergeResult
        @SuppressWarnings("unchecked")
        MergeResult<RawText> mergeResult = mock(MergeResult.class);
        when(mergeResult.getSequences()).thenReturn(List.of(oursText, baseText, theirsText));

        return mergeResult;
    }

    /**
     * Create mock merge chunks for OURS, BASE, and THEIRS.
     */
    private Map<CheckoutCommand.Stage, MergeChunk> createMockChunks() {
        Map<CheckoutCommand.Stage, MergeChunk> chunks = new HashMap<>();

        // OURS chunk (sequence index 0, lines 0-2)
        MergeChunk oursChunk = mock(MergeChunk.class);
        when(oursChunk.getSequenceIndex()).thenReturn(0);
        when(oursChunk.getBegin()).thenReturn(0);
        when(oursChunk.getEnd()).thenReturn(2);
        chunks.put(CheckoutCommand.Stage.OURS, oursChunk);

        // BASE chunk (sequence index 1, lines 0-1)
        MergeChunk baseChunk = mock(MergeChunk.class);
        when(baseChunk.getSequenceIndex()).thenReturn(1);
        when(baseChunk.getBegin()).thenReturn(0);
        when(baseChunk.getEnd()).thenReturn(1);
        chunks.put(CheckoutCommand.Stage.BASE, baseChunk);

        // THEIRS chunk (sequence index 2, lines 0-2)
        MergeChunk theirsChunk = mock(MergeChunk.class);
        when(theirsChunk.getSequenceIndex()).thenReturn(2);
        when(theirsChunk.getBegin()).thenReturn(0);
        when(theirsChunk.getEnd()).thenReturn(2);
        chunks.put(CheckoutCommand.Stage.THEIRS, theirsChunk);

        return chunks;
    }
}
