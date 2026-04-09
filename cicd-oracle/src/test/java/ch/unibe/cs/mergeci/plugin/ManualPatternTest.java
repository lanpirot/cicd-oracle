package ch.unibe.cs.mergeci.plugin;

import ch.unibe.cs.mergeci.model.patterns.IPattern;
import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.merge.MergeChunk;
import org.eclipse.jgit.merge.MergeResult;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the manual pattern concept: a pattern that returns user-provided
 * lines verbatim, ignoring the merge result.
 */
class ManualPatternTest {

    /** Minimal manual pattern for testing (mirrors plugin's ManualPattern). */
    static class ManualPattern implements IPattern {
        private final List<String> lines;

        ManualPattern(String text) {
            this.lines = Arrays.asList(text.split("\n", -1));
        }

        ManualPattern(List<String> lines) {
            this.lines = List.copyOf(lines);
        }

        @Override
        public List<String> apply(MergeResult<RawText> mergeResult,
                                  Map<CheckoutCommand.Stage, MergeChunk> chunks) {
            return lines;
        }

        @Override
        public String name() {
            return "MANUAL";
        }
    }

    @Test
    void applyReturnsUserProvidedLines() {
        var pattern = new ManualPattern("line1\nline2\nline3");
        assertEquals(List.of("line1", "line2", "line3"), pattern.apply(null, null));
    }

    @Test
    void applyFromListConstructor() {
        var pattern = new ManualPattern(List.of("a", "b"));
        assertEquals(List.of("a", "b"), pattern.apply(null, null));
    }

    @Test
    void nameReturnsManual() {
        assertEquals("MANUAL", new ManualPattern("x").name());
    }

    @Test
    void emptyTextProducesSingleEmptyLine() {
        assertEquals(List.of(""), new ManualPattern("").apply(null, null));
    }
}
