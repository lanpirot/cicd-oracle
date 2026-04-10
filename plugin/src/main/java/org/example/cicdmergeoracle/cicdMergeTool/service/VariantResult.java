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
            for (String m : failedModules) {
                String moduleName = m.contains(" (") ? m.substring(0, m.indexOf(" (")) : m;
                sb.append("&nbsp;&nbsp;<a href=\"module:").append(escape(moduleName)).append("\">")
                  .append(escape(m)).append("</a><br>");
            }
        }
        if (hasTestFailures) {
            if (hasModuleFailures) sb.append("<br>");
            String prefix = commonPrefix(testFailures);
            String label = prefix.isEmpty() ? "Failed tests:" : "Failed tests (…" + escape(prefix) + "):";
            sb.append("<b>").append(label).append("</b><br>");
            for (String t : testFailures) {
                String className = t.contains("#") ? t.substring(0, t.indexOf('#')) : t;
                String display = prefix.isEmpty() ? t : t.substring(prefix.length());
                sb.append("&nbsp;&nbsp;<a href=\"test:").append(escape(className)).append("\">")
                  .append(escape(display)).append("</a><br>");
            }
        }
        sb.append("</html>");
        return sb.toString();
    }

    /** Find the longest common prefix up to the last '.' boundary (package-level). */
    private static String commonPrefix(List<String> items) {
        if (items.size() < 2) return "";
        String first = items.get(0);
        int len = first.length();
        for (int i = 1; i < items.size(); i++) {
            String s = items.get(i);
            len = Math.min(len, s.length());
            for (int j = 0; j < len; j++) {
                if (first.charAt(j) != s.charAt(j)) { len = j; break; }
            }
        }
        // Snap back to last '.' so we truncate at a package boundary
        String raw = first.substring(0, len);
        int dot = raw.lastIndexOf('.');
        return dot > 0 ? raw.substring(0, dot + 1) : "";
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
