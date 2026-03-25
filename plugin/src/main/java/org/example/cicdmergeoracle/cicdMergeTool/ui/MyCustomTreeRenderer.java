package org.example.cicdmergeoracle.cicdMergeTool.ui;

import org.example.cicdmergeoracle.cicdMergeTool.service.projectRunners.maven.CompilationResult;
import org.example.cicdmergeoracle.cicdMergeTool.service.projectRunners.maven.ResolutionResultDTO;
import org.example.cicdmergeoracle.cicdMergeTool.service.projectRunners.maven.TestTotal;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import java.awt.*;

public class MyCustomTreeRenderer extends DefaultTreeCellRenderer {
    @Override
    public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded, boolean leaf, int row, boolean hasFocus) {
        super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
        ImageIcon folderIcon = new ImageIcon(getClass().getResource("/icons/folder.png"));
        ImageIcon testIcon = new ImageIcon(getClass().getResource("/icons/tests.png"));
        ImageIcon buildIcon = new ImageIcon(getClass().getResource("/icons/build.png"));
        ImageIcon resolutionPatterns = new ImageIcon(getClass().getResource("/icons/resolutions.png"));

        DefaultMutableTreeNode node = (DefaultMutableTreeNode)value;
        Object object = node.getUserObject();
        if (object instanceof ResolutionResultDTO.Variant) {
            setIcon(folderIcon);
        } else if (object instanceof CompilationResult) {
            setIcon(buildIcon);
        } else if (object instanceof TestTotal) {
            setIcon(testIcon);
        } else if (object instanceof ResolutionResultDTO.Variant.ResolutionPatterns) {
            setIcon(resolutionPatterns);
        }

        return this;
    }
}
