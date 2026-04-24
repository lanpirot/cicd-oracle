package org.example.cicdmergeoracle.cicdMergeTool.ui;

import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.util.List;
import java.util.Map;


/**
 * Renders the Patterns column as a row of fixed-width boxes,
 * each showing an abbreviated resolution name (e.g. "O" for OURS, "B:O:T" for BASE:OURS:THEIRS).
 */
class PatternsCellRenderer extends JPanel implements TableCellRenderer {

    /** Only abbreviate compounds and EMPTY; atomics stay readable. */
    private static final Map<String, String> COMPOUND_ABBREV = Map.of(
            "OURS", "O",
            "THEIRS", "T",
            "BASE", "B"
    );

    private static final int BOX_WIDTH = 52;
    private static final int BOX_HEIGHT = 18;
    private static final int GAP = 3;

    PatternsCellRenderer() {
        setOpaque(true);
        setLayout(new FlowLayout(FlowLayout.LEFT, GAP, 1));
    }

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value,
                                                   boolean isSelected, boolean hasFocus,
                                                   int row, int column) {
        removeAll();

        if (isSelected) {
            setBackground(table.getSelectionBackground());
        } else {
            setBackground(table.getBackground());
        }

        if (value instanceof List<?> patterns) {
            for (Object p : patterns) {
                String full = String.valueOf(p);
                String abbr = abbreviate(full);
                add(createBox(abbr, full, isSelected,
                        table.getSelectionBackground(), table.getSelectionForeground()));
            }
        }
        return this;
    }

    private static String abbreviate(String pattern) {
        if ("EMPTY".equals(pattern)) return "--";
        // Atomic patterns stay full: OURS, THEIRS, BASE, MANUAL
        if (!pattern.contains(":")) return pattern;
        // Compound like "OURS:THEIRS:BASE" → "O:T:B"
        String[] parts = pattern.split(":");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!sb.isEmpty()) sb.append(':');
            sb.append(COMPOUND_ABBREV.getOrDefault(part, part.substring(0, 1)));
        }
        return sb.toString();
    }

    private static JLabel createBox(String abbr, String fullName, boolean selected,
                                     Color selBg, Color selFg) {
        JLabel label = new JLabel(abbr, SwingConstants.CENTER);
        label.setOpaque(true);
        label.setFont(label.getFont().deriveFont(Font.PLAIN, JBUI.scaleFontSize(10)));
        label.setPreferredSize(JBUI.size(BOX_WIDTH, BOX_HEIGHT));
        label.setMinimumSize(JBUI.size(BOX_WIDTH, BOX_HEIGHT));
        label.setMaximumSize(JBUI.size(BOX_WIDTH, BOX_HEIGHT));
        if (selected) {
            label.setBackground(selBg);
            label.setForeground(selFg);
            label.setBorder(BorderFactory.createLineBorder(selFg, 1));
        } else {
            // Light: grey boxes on white table; Dark: lighter boxes on dark table
            label.setBackground(new JBColor(new Color(210, 215, 222), new Color(80, 85, 90)));
            label.setForeground(new JBColor(new Color(30, 30, 30), new Color(220, 220, 220)));
            label.setBorder(BorderFactory.createLineBorder(
                    new JBColor(new Color(170, 175, 180), new Color(100, 105, 110)), 1));
        }
        label.setToolTipText(fullName);
        return label;
    }
}
