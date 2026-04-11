package ch.unibe.cs.mergeci.runner;

import ch.unibe.cs.mergeci.runner.maven.CompilationResult;
import ch.unibe.cs.mergeci.runner.maven.TestTotal;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Immutable result of a single variant build+test cycle.
 * Shared between pipeline (batch result maps) and plugin (UI callbacks).
 */
public record VariantOutcome(
        String variantKey,
        int variantIndex,
        Map<String, List<String>> patternAssignment,
        CompilationResult compilationResult,
        TestTotal testTotal,
        boolean testsRan,
        Duration elapsed,
        Path logFile,
        Path variantPath,
        boolean isDonor
) {}
