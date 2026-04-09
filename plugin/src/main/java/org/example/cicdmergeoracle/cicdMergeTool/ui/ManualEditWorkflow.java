package org.example.cicdmergeoracle.cicdMergeTool.ui;

import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.example.cicdmergeoracle.cicdMergeTool.service.OracleSession;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Manages the manual-edit lifecycle for a single conflict chunk:
 * open a temp file in the editor, let the user edit, then pin or cancel.
 * If the editor tab is closed without clicking Pin, prompts to save or discard.
 */
class ManualEditWorkflow {
    private static final Logger LOG = LoggerFactory.getLogger(ManualEditWorkflow.class);

    private final Project ideProject;
    private final Path projectPath;
    private final ChunkTableModel chunkModel;
    private final JButton pinManualButton;
    private final JButton cancelManualButton;
    private final Runnable onPinChanged;

    private int manualEditRow = -1;
    private Path manualEditFile;
    private OracleSession activeSession;
    private boolean cleaningUp;

    ManualEditWorkflow(Project ideProject, Path projectPath,
                       ChunkTableModel chunkModel,
                       JButton pinManualButton, JButton cancelManualButton,
                       Runnable onPinChanged) {
        this.ideProject = ideProject;
        this.projectPath = projectPath;
        this.chunkModel = chunkModel;
        this.pinManualButton = pinManualButton;
        this.cancelManualButton = cancelManualButton;
        this.onPinChanged = onPinChanged;

        ideProject.getMessageBus().connect().subscribe(
                FileEditorManagerListener.FILE_EDITOR_MANAGER, new FileEditorManagerListener() {
                    @Override
                    public void fileClosed(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
                        onEditorTabClosed(file);
                    }
                });
    }

    void startManualEdit(int row, OracleSession session) {
        if (manualEditRow >= 0) cancelManualEdit(session);

        this.activeSession = session;
        String initial;
        if (session.getManualTexts().containsKey(row)) {
            initial = session.getManualTexts().get(row);
        } else {
            initial = readConflictChunkFromFile(row);
        }

        String filePath = chunkModel.getFilePath(row);
        String ext = "";
        int dot = filePath.lastIndexOf('.');
        if (dot >= 0) ext = filePath.substring(dot);

        try {
            manualEditFile = Files.createTempFile("manual-chunk" + row + "-", ext);
            Files.writeString(manualEditFile, initial);
        } catch (IOException e) {
            LOG.warn("Failed to create temp file for manual edit", e);
            return;
        }

        manualEditRow = row;
        pinManualButton.setVisible(true);
        cancelManualButton.setVisible(true);

        VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(
                manualEditFile.toAbsolutePath().toString());
        if (vf != null) {
            FileEditorManager.getInstance(ideProject).openFile(vf, true);
        }
    }

    /** Reads the edited content and pins the chunk as MANUAL. Returns true if successful. */
    boolean confirmManualEdit(OracleSession session) {
        if (manualEditRow < 0 || manualEditFile == null) return false;

        try {
            String text = Files.readString(manualEditFile);
            session.getManualTexts().put(manualEditRow, text);
            session.getPinnedChunks().put(manualEditRow, "MANUAL");
            session.bumpManualVersion(manualEditRow);
        } catch (IOException e) {
            LOG.warn("Failed to read manual edit content", e);
        }

        cleanupManualEdit();
        return true;
    }

    /** Reverts the chunk resolution to (auto) and cleans up. */
    void cancelManualEdit(OracleSession session) {
        if (manualEditRow < 0) return;

        chunkModel.setResolution(manualEditRow, "(auto)");
        session.getManualTexts().remove(manualEditRow);
        session.getPinnedChunks().remove(manualEditRow);

        cleanupManualEdit();
    }

    private void onEditorTabClosed(VirtualFile file) {
        if (cleaningUp || manualEditRow < 0 || manualEditFile == null || activeSession == null) return;

        // Check if the closed file is our temp manual edit file
        String closedPath = file.getPath();
        if (!closedPath.equals(manualEditFile.toAbsolutePath().toString())) return;

        // The user closed the editor tab without clicking Pin or Cancel — prompt
        int choice = JOptionPane.showConfirmDialog(
                null,
                "Pin the manual edit for chunk " + manualEditRow + "?",
                "Manual Edit",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE);

        if (choice == JOptionPane.YES_OPTION) {
            confirmManualEdit(activeSession);
            onPinChanged.run();
        } else {
            cancelManualEdit(activeSession);
            onPinChanged.run();
        }
    }

    private String readConflictChunkFromFile(int row) {
        String filePath = chunkModel.getFilePath(row);
        int chunkIdx = chunkModel.getChunkIdx(row);
        try {
            List<String> lines = Files.readAllLines(projectPath.resolve(filePath));
            int conflictCount = 0;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).startsWith("<<<<<<<")) {
                    if (conflictCount == chunkIdx) {
                        StringBuilder sb = new StringBuilder();
                        for (int j = i; j < lines.size(); j++) {
                            sb.append(lines.get(j)).append('\n');
                            if (lines.get(j).startsWith(">>>>>>>")) break;
                        }
                        return sb.toString();
                    }
                    conflictCount++;
                }
            }
        } catch (IOException e) {
            // fall through
        }
        return "";
    }

    private void cleanupManualEdit() {
        cleaningUp = true;
        try {
            if (manualEditFile != null) {
                VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(
                        manualEditFile.toAbsolutePath().toString());
                if (vf != null) {
                    FileEditorManager.getInstance(ideProject).closeFile(vf);
                }
                try {
                    Files.deleteIfExists(manualEditFile);
                } catch (IOException e) {
                    // ignore
                }
            }
            manualEditRow = -1;
            manualEditFile = null;
            activeSession = null;
            pinManualButton.setVisible(false);
            cancelManualButton.setVisible(false);
        } finally {
            cleaningUp = false;
        }
    }
}
