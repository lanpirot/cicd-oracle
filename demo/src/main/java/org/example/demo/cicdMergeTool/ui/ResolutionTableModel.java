package org.example.demo.cicdMergeTool.ui;


import org.example.demo.cicdMergeTool.service.projectRunners.maven.ResolutionResultDTO;

import javax.swing.table.AbstractTableModel;
import java.util.LinkedHashMap;
import java.util.Map;

public class ResolutionTableModel extends AbstractTableModel {

    private final String[] columns = {
            "Variant", "Status", "Compilation", "Tests", "Time"
    };

    private final Map<String, ResolutionResultDTO.Variant> variants = new LinkedHashMap<>();

    @Override
    public int getRowCount() {
        return variants.size();
    }

    @Override
    public int getColumnCount() {
        return columns.length;
    }

    @Override
    public Object getValueAt(int row, int col) {
        ResolutionResultDTO.Variant v = variants.values().toArray(ResolutionResultDTO.Variant[]::new)[row];

        return switch (col) {
            case 0 -> v.getName();
            case 1 -> v.getStatus();
            case 2 -> v.getCompilationResult() == null
                    ? "-"
                    : v.getCompilationResult().getBuildStatus();
            case 3 -> v.getTestTotal() == null
                    ? "-"
                    : v.getTestTotal().getRunNum();
            case 4 -> "";
            default -> "";
        };
    }

    @Override
    public String getColumnName(int column) {
        return columns[column];
    }


    public void addVariant(ResolutionResultDTO.Variant variant) {
        variants.put(variant.getName(), variant);
        fireTableRowsInserted(variants.size() - 1, variants.size() - 1);
    }

    public void updateVariant(ResolutionResultDTO.Variant variant) {
        int index = findIndex(variant.getName());
        if (index >= 0) {
            fireTableRowsUpdated(index, index);
        }
    }

    public void clear() {
        variants.clear();
        fireTableDataChanged();
    }

    private int findIndex(String name) {
        int index = 0;
        for (String key : variants.keySet()) {
            if (key.equals(name)) {
                return index;
            }
            index++;
        }
        return -1;
    }
}