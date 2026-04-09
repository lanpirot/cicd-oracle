package org.example.cicdmergeoracle.cicdMergeTool.service;

import ch.unibe.cs.mergeci.present.VariantScore;
import ch.unibe.cs.mergeci.runner.maven.CompilationResult;
import ch.unibe.cs.mergeci.runner.maven.TestTotal;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of a completed variant build, passed from the background
 * orchestrator thread to the EDT for UI display.
 *
 * @param variantIndex       1-based index of this variant within the current run
 * @param patternAssignment  file path -> ordered list of pattern names per chunk
 * @param compilationResult  parsed Maven compilation log (null if build could not start)
 * @param testResult         aggregated surefire/failsafe results
 * @param score              lexicographic quality score (null if build failed / timed out)
 * @param elapsed            wall-clock time for this variant's build + test
 * @param logFile            path to the Maven compilation log file
 */
public record VariantResult(
        int variantIndex,
        Map<String, List<String>> patternAssignment,
        CompilationResult compilationResult,
        TestTotal testResult,
        VariantScore score,
        Duration elapsed,
        Path logFile,
        Map<Integer, Integer> manualVersions,
        List<String> failedModules,
        List<String> testFailures
) {
    /** Build a multi-line tooltip from failed modules and test failures. Returns null if everything passed. */
    public String buildTooltip() {
        boolean hasModuleFailures = failedModules != null && !failedModules.isEmpty();
        boolean hasTestFailures = testFailures != null && !testFailures.isEmpty();
        if (!hasModuleFailures && !hasTestFailures) return null;

        StringBuilder sb = new StringBuilder("<html>");
        if (hasModuleFailures) {
            sb.append("<b>Failed modules:</b><br>");
            for (String m : failedModules) sb.append("&nbsp;&nbsp;").append(escape(m)).append("<br>");
        }
        if (hasTestFailures) {
            if (hasModuleFailures) sb.append("<br>");
            sb.append("<b>Failed tests:</b><br>");
            for (String t : testFailures) sb.append("&nbsp;&nbsp;").append(escape(t)).append("<br>");
        }
        sb.append("</html>");
        return sb.toString();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
