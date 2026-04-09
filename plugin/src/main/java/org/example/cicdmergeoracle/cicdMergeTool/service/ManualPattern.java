package org.example.cicdmergeoracle.cicdMergeTool.service;

import ch.unibe.cs.mergeci.model.patterns.IPattern;
import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.merge.MergeChunk;
import org.eclipse.jgit.merge.MergeResult;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * A pattern that returns user-provided lines verbatim,
 * ignoring the merge result entirely.
 */
public class ManualPattern implements IPattern {
    private final List<String> lines;

    public ManualPattern(String text) {
        this.lines = Arrays.asList(text.split("\n", -1));
    }

    public ManualPattern(List<String> lines) {
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
