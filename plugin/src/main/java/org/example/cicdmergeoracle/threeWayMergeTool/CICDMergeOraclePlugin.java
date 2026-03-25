package org.example.cicdmergeoracle.threeWayMergeTool;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public class CICDMergeOraclePlugin extends AnAction {
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        new Task.Backgroundable(e.getProject(), "Loading Merge Data", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                Project project = e.getProject();
                GitUtils.checkoutWithDiff3Conflict(project);
                FileEditorManager fileEditorManager = FileEditorManager.getInstance(Objects.requireNonNull(project));
                VirtualFile currentFile = fileEditorManager.getSelectedFiles()[0];

                String branch = GitUtils.getBranchCode(Objects.requireNonNull(project), "bla");

                if (currentFile != null) {
//            fileEditorManager.closeFile(currentFile);
//            Messages.showMessageDialog(e.getProject(), "File was successfully closed!" + "branch: " + branch, "CI/CD Merge Oracle", Messages.getInformationIcon());
                }

                FileEditorManager editorManager = FileEditorManager.getInstance(e.getProject());
                Editor editor = editorManager.getSelectedTextEditor();

    /*    if (editor != null) {
            Document document = editor.getDocument();  // Получаем документ
            String text = document.getText();
            CodeEditorDialog dialog = new CodeEditorDialog(e.getProject(), text);
            dialog.show();
            *//*MergeContent mergeContent;
            try {
                mergeContent = GitUtils.getMergeContent(project);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
            MergeConflictDialog mergeConflictDialog = new MergeConflictDialog(project, mergeContent.getTheirContent(), mergeContent.getBaseContent(),mergeContent.getYourContent());
            mergeConflictDialog.show();*//*
        }*/
                if (editor != null) {
                    Document document = editor.getDocument();  // get the document

                    // Run a background task to retrieve merge data

                    try {
                        // Getting merge data in a background thread
                        MergeContent mergeContent = GitUtils.getMergeContent(project);

                        // Switch to the main thread to show the dialog
                        ApplicationManager.getApplication().invokeLater(() -> {
                            MergeConflictDialog mergeConflictDialog = new MergeConflictDialog(
                                    project,
                                    mergeContent
                            );
                            //Setting up non-modal behavior
                            mergeConflictDialog.setModal(false);
                            mergeConflictDialog.show();
                            /*ThreeWayMergePanel threeWayMergePanel = new ThreeWayMergePanel(project, mergeContent);
                            threeWayMergePanel.setVisible(true);*/
                        });

                    } catch (Exception ex) {
                        // In case of an error, we display a message
                        ApplicationManager.getApplication().invokeLater(() -> {
                            Messages.showErrorDialog(project, "Failed to load merge data: " + ex.getMessage(), "Error");
                        });
                    }
                }
            }
        }.queue(); // Launching a background task
    }
}
