package org.example.cicdmergeoracle.cicdMergeTool.ui;

import ch.unibe.cs.mergeci.model.ConflictBlock;
import org.example.cicdmergeoracle.cicdMergeTool.model.ChunkKey;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class ChunkTableModel extends AbstractTableModel {
    static final String[] RESOLUTION_OPTIONS = {"(auto)", "OURS", "THEIRS", "BASE", "EMPTY", "MANUAL"};

    private static final String[] COLUMNS = {"File", "Chunk", "OURS (1st line)", "THEIRS (1st line)", "Consensus", "Resolution"};
    private final List<ChunkRow> rows = new ArrayList<>();

    static class ChunkRow {
        final String filePath;
        final int chunkIdx;
        final ConflictBlock block;
        final String oursPreview;
        final String theirsPreview;
        String consensus;
        String resolution;

        ChunkRow(String filePath, int chunkIdx, ConflictBlock block,
                 String oursPreview, String theirsPreview, String consensus, String resolution) {
            this.filePath = filePath;
            this.chunkIdx = chunkIdx;
            this.block = block;
            this.oursPreview = oursPreview;
            this.theirsPreview = theirsPreview;
            this.consensus = consensus;
            this.resolution = resolution;
        }
    }

    void addChunk(String filePath, int chunkIdx, ConflictBlock cb,
                 String oursPreview, String theirsPreview) {
        rows.add(new ChunkRow(filePath, chunkIdx, cb, oursPreview, theirsPreview, "", "(auto)"));
        fireTableRowsInserted(rows.size() - 1, rows.size() - 1);
    }

    void clear() {
        int size = rows.size();
        if (size > 0) {
            rows.clear();
            fireTableRowsDeleted(0, size - 1);
        }
    }

    ConflictBlock getConflictBlock(int row) {
        return rows.get(row).block;
    }

    String getFilePath(int row) {
        return rows.get(row).filePath;
    }

    int getChunkIdx(int row) {
        return rows.get(row).chunkIdx;
    }

    String getResolution(int row) {
        return rows.get(row).resolution;
    }

    void setResolution(int row, String resolution) {
        rows.get(row).resolution = resolution;
        fireTableCellUpdated(row, 5);
    }

    void updateConsensus(Map<ChunkKey, Map<String, Double>> consensus) {
        for (int i = 0; i < rows.size(); i++) {
            ChunkRow r = rows.get(i);
            ChunkKey key = new ChunkKey(r.filePath, r.chunkIdx);
            Map<String, Double> pcts = consensus.get(key);
            r.consensus = pcts != null ? formatConsensus(pcts) : "";
        }
        if (!rows.isEmpty()) fireTableRowsUpdated(0, rows.size() - 1);
    }

    private String formatConsensus(Map<String, Double> pcts) {
        StringBuilder sb = new StringBuilder();
        pcts.forEach((pattern, pct) -> {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(pattern).append(": ").append(String.format("%.0f%%", pct));
        });
        return sb.toString();
    }

    @Override public int getRowCount() { return rows.size(); }
    @Override public int getColumnCount() { return COLUMNS.length; }
    @Override public String getColumnName(int col) { return COLUMNS[col]; }

    @Override
    public boolean isCellEditable(int row, int col) {
        return col == 5;
    }

    @Override
    public Object getValueAt(int row, int col) {
        ChunkRow r = rows.get(row);
        return switch (col) {
            case 0 -> r.filePath;
            case 1 -> r.chunkIdx;
            case 2 -> r.oursPreview;
            case 3 -> r.theirsPreview;
            case 4 -> r.consensus;
            case 5 -> r.resolution;
            default -> "";
        };
    }

    @Override
    public void setValueAt(Object value, int row, int col) {
        if (col == 5) {
            rows.get(row).resolution = (String) value;
            fireTableCellUpdated(row, col);
        }
    }
}
