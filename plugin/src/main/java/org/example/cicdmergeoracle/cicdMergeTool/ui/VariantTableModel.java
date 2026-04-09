package org.example.cicdmergeoracle.cicdMergeTool.ui;

import ch.unibe.cs.mergeci.present.VariantScore;
import ch.unibe.cs.mergeci.runner.maven.CompilationResult;
import org.example.cicdmergeoracle.cicdMergeTool.model.ChunkKey;
import org.example.cicdmergeoracle.cicdMergeTool.service.VariantResult;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

class VariantTableModel extends AbstractTableModel {
    private static final String[] COLUMNS = {"#", "Status", "Modules", "Tests", "Score", "Time (s)", "Patterns"};
    /** Best-first: scored beats unscored; among scored, higher VariantScore wins. */
    private static final Comparator<VariantResult> BEST_FIRST = (a, b) -> {
        if (a.score() != null && b.score() != null) return b.score().compareTo(a.score());
        if (a.score() != null) return -1; // scored before unscored
        if (b.score() != null) return 1;
        return 0;
    };

    private final List<VariantResult> allResults = new ArrayList<>();
    private final List<VariantResult> filtered = new ArrayList<>();
    private List<ChunkKey> chunkIndex = List.of();

    void setChunkIndex(List<ChunkKey> chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    void addResult(VariantResult r) {
        allResults.add(r);
        int insertAt = Collections.binarySearch(filtered, r, BEST_FIRST);
        if (insertAt < 0) insertAt = -insertAt - 1;
        filtered.add(insertAt, r);
        fireTableRowsInserted(insertAt, insertAt);
    }

    void addIfMatches(VariantResult r, Map<Integer, String> pins,
                      Map<Integer, Integer> manualVersions, List<ChunkKey> chunkIndex) {
        allResults.add(r);
        if (matchesPins(r, pins, manualVersions, chunkIndex)) {
            int insertAt = Collections.binarySearch(filtered, r, BEST_FIRST);
            if (insertAt < 0) insertAt = -insertAt - 1;
            filtered.add(insertAt, r);
            fireTableRowsInserted(insertAt, insertAt);
        }
    }

    /** Re-filter visible results based on current pins, then sort once.
     *  O(N log N) with a single repaint event. */
    void applyFilter(Map<Integer, String> pins, Map<Integer, Integer> manualVersions,
                     List<ChunkKey> chunkIndex) {
        filtered.clear();
        for (VariantResult r : allResults) {
            if (matchesPins(r, pins, manualVersions, chunkIndex)) {
                filtered.add(r);
            }
        }
        filtered.sort(BEST_FIRST);
        fireTableDataChanged();
    }

    private boolean matchesPins(VariantResult r, Map<Integer, String> pins,
                                 Map<Integer, Integer> manualVersions,
                                 List<ChunkKey> chunkIndex) {
        if (pins.isEmpty() || r.patternAssignment() == null) return true;
        for (Map.Entry<Integer, String> pin : pins.entrySet()) {
            int globalIdx = pin.getKey();
            String pinnedPattern = pin.getValue();
            if ("MANUAL".equals(pinnedPattern)) {
                Integer currentVer = manualVersions.get(globalIdx);
                Integer variantVer = r.manualVersions() != null
                        ? r.manualVersions().get(globalIdx) : null;
                if (currentVer != null && !currentVer.equals(variantVer)) return false;
                continue;
            }
            if (globalIdx >= chunkIndex.size()) continue;
            ChunkKey key = chunkIndex.get(globalIdx);
            List<String> filePatterns = r.patternAssignment().get(key.filePath());
            if (filePatterns == null) return false;
            if (key.indexWithinFile() >= filePatterns.size()) return false;
            if (!pinnedPattern.equals(filePatterns.get(key.indexWithinFile()))) return false;
        }
        return true;
    }

    /** Show all results, ignoring pin filters. */
    void showAll() {
        filtered.clear();
        filtered.addAll(allResults);
        filtered.sort(BEST_FIRST);
        fireTableDataChanged();
    }

    void clear() {
        allResults.clear();
        int size = filtered.size();
        if (size > 0) {
            filtered.clear();
            fireTableRowsDeleted(0, size - 1);
        }
    }

    VariantResult getResult(int row) {
        return filtered.get(row);
    }

    @Override public int getRowCount() { return filtered.size(); }
    @Override public int getColumnCount() { return COLUMNS.length; }
    @Override public String getColumnName(int col) { return COLUMNS[col]; }

    @Override
    public Object getValueAt(int row, int col) {
        VariantResult r = filtered.get(row);
        return switch (col) {
            case 0 -> r.variantIndex();
            case 1 -> statusText(r);
            case 2 -> r.compilationResult() != null ? r.compilationResult().getSuccessfulModules()
                    + "/" + r.compilationResult().getTotalModules() : "-";
            case 3 -> r.testResult() != null && r.testResult().isHasData()
                    ? r.testResult().getPassedTests() + "/" + r.testResult().getRunNum() : "-";
            case 4 -> r.score() != null
                    ? r.score().successfulModules() + "m " + r.score().passedTests() + "t" : "-";
            case 5 -> String.format("%.1f", r.elapsed().toMillis() / 1000.0);
            case 6 -> summarizePatterns(r.patternAssignment(), chunkIndex);
            default -> "";
        };
    }

    private String statusText(VariantResult r) {
        if (r.compilationResult() == null) return "ERROR";
        CompilationResult.Status s = r.compilationResult().getBuildStatus();
        if (s == CompilationResult.Status.SUCCESS) return "\u2713";
        if (s == CompilationResult.Status.TIMEOUT) return "TIMEOUT";
        return "FAIL";
    }

    private String summarizePatterns(Map<String, List<String>> patterns,
                                     List<ChunkKey> index) {
        if (patterns == null) return "";
        if (index.isEmpty()) {
            return patterns.values().stream()
                    .flatMap(List::stream)
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");
        }
        StringBuilder sb = new StringBuilder();
        for (ChunkKey key : index) {
            List<String> filePatterns = patterns.get(key.filePath());
            if (filePatterns != null && key.indexWithinFile() < filePatterns.size()) {
                if (!sb.isEmpty()) sb.append(", ");
                sb.append(filePatterns.get(key.indexWithinFile()));
            }
        }
        return sb.toString();
    }
}
