package org.example.cicdmergeoracle.threeWayMergeTool;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;

import javax.swing.JComponent;

public class MergeConflictDialog extends DialogWrapper {

    private final ThreeWayMergePanel mergePanel;

    public MergeConflictDialog(Project project, MergeContent mergeContent) {
        super(true);
        mergePanel = new ThreeWayMergePanel(project, mergeContent, this);
        setTitle("Resolve Merge Conflict");
        init();
    }

    @Override
    protected JComponent createCenterPanel() {
        return mergePanel;
    }

    @Override
    protected void dispose() {
        super.dispose();
        mergePanel.disposeEditors();  // Release editor resources
    }
}