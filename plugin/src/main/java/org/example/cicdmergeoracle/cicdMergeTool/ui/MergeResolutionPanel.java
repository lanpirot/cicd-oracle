package org.example.cicdmergeoracle.cicdMergeTool.ui;

import ch.unibe.cs.mergeci.model.ConflictBlock;
import ch.unibe.cs.mergeci.model.ConflictFile;
import ch.unibe.cs.mergeci.model.IMergeBlock;
import ch.unibe.cs.mergeci.present.VariantScore;
import ch.unibe.cs.mergeci.runner.maven.CompilationResult;
import ch.unibe.cs.mergeci.runner.maven.TestTotal;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import org.example.cicdmergeoracle.cicdMergeTool.model.ChunkKey;
import org.example.cicdmergeoracle.cicdMergeTool.service.ChunkConsensus;
import org.example.cicdmergeoracle.cicdMergeTool.service.ManualPattern;
import org.example.cicdmergeoracle.cicdMergeTool.service.OracleSession;
import org.example.cicdmergeoracle.cicdMergeTool.service.PluginOrchestrator;
import org.example.cicdmergeoracle.cicdMergeTool.service.VariantResult;
import org.example.cicdmergeoracle.cicdMergeTool.util.GitUtils;
import org.example.cicdmergeoracle.cicdMergeTool.util.MyFileUtils;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MergeResolutionPanel {
    private final Path projectPath;
    private final Project ideProject;
    private final JPanel root = new JPanel(new BorderLayout());

    // Status bar
    private final JLabel mergeInfoLabel = new JBLabel();
    private final JLabel variantCountLabel = createFixedWidthLabel("Variants: 0",
            "Variants: 9999/9999 (stopped)");
    private final JButton runStopButton = new JButton("Run");
    private boolean running;
    private final JButton applyVariantButton = new JButton("Apply Variant");
    private final JCheckBox showAllToggle = new JCheckBox("Ignore Pins");

    // Live dashboard table
    private final VariantTableModel dashboardModel = new VariantTableModel();
    private final JTable dashboardTable = new JTable(dashboardModel);

    // Chunk selector table
    private final ChunkTableModel chunkModel = new ChunkTableModel();
    private final JTable chunkTable = new JTable(chunkModel);

    // Manual edit state
    private final JButton pinManualButton = new JButton("Pin Manual");
    private final JButton cancelManualButton = new JButton("Cancel Manual");
    private int manualEditRow = -1;
    private Path manualEditFile;

    // State
    private OracleSession session;
    private PluginOrchestrator orchestrator;
    private String oursCommit;
    private String theirsCommit;
    private Map<String, ConflictFile> conflictFileMap;

    public MergeResolutionPanel(File projectPath, Project ideProject) {
        this.projectPath = projectPath.toPath();
        this.ideProject = ideProject;

        root.add(createStatusBar(), BorderLayout.NORTH);
        root.add(createCenterPanel(), BorderLayout.CENTER);

        MergeStateListener.register(ideProject, () -> updateMergeStatus(projectPath));
        updateMergeStatus(projectPath);

        applyVariantButton.setEnabled(false);
        pinManualButton.setVisible(false);
        cancelManualButton.setVisible(false);
        runStopButton.addActionListener(e -> {
            if (running) stopRun(); else startRun();
        });
        applyVariantButton.addActionListener(e -> applySelectedVariant());
        pinManualButton.addActionListener(e -> confirmManualEdit());
        cancelManualButton.addActionListener(e -> cancelManualEdit());
        showAllToggle.addActionListener(e -> {
            if (session == null) return;
            if (showAllToggle.isSelected()) {
                dashboardModel.showAll();
            } else {
                dashboardModel.applyFilter(session.getPinnedChunks(), session.getManualVersionSnapshot(), session.getChunkIndex());
            }
            updateVariantCountLabel();
        });
        dashboardTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                applyVariantButton.setEnabled(dashboardTable.getSelectedRow() >= 0);
            }
        });

        chunkTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = chunkTable.rowAtPoint(e.getPoint());
                    if (row >= 0) openChunkInEditor(row);
                }
            }
        });

        // Resolution dropdown editor
        JComboBox<String> resolutionCombo = new JComboBox<>(RESOLUTION_OPTIONS);
        chunkTable.getColumnModel().getColumn(5).setCellEditor(new DefaultCellEditor(resolutionCombo));
        chunkModel.addTableModelListener(e -> {
            if (e.getColumn() == 5 && e.getType() == javax.swing.event.TableModelEvent.UPDATE) {
                int row = e.getFirstRow();
                if (row >= 0 && row < chunkModel.getRowCount()) {
                    onResolutionChanged(row, chunkModel.getResolution(row));
                }
            }
        });
    }

    private JPanel createStatusBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        bar.add(mergeInfoLabel);
        bar.add(variantCountLabel);
        bar.add(showAllToggle);
        bar.add(runStopButton);
        bar.add(applyVariantButton);
        bar.add(pinManualButton);
        bar.add(cancelManualButton);
        return bar;
    }

    private JComponent createCenterPanel() {
        JBTabbedPane tabs = new JBTabbedPane();
        tabs.addTab("Variants", new JBScrollPane(dashboardTable));
        tabs.addTab("Chunks", new JBScrollPane(chunkTable));
        return tabs;
    }

    private void updateMergeStatus(File projectFile) {
        try {
            if (GitUtils.hasConflicts(projectFile)) {
                oursCommit = GitUtils.getOurs(projectFile);
                theirsCommit = GitUtils.getTheirs(projectFile);

                // Cancel any running orchestrator from the previous merge
                if (orchestrator != null) {
                    orchestrator.cancel();
                    orchestrator = null;
                }
                running = false;
                runStopButton.setText("Run");
                runStopButton.setEnabled(true);
                dashboardModel.clear();

                conflictFileMap = PluginOrchestrator.parseConflictFiles(
                        projectPath, oursCommit, theirsCommit);
                session = new OracleSession();
                populateChunkTable(conflictFileMap);

                int files = conflictFileMap.size();
                int chunks = (int) conflictFileMap.values().stream()
                        .flatMap(cf -> cf.getMergeBlocks().stream())
                        .filter(b -> b instanceof ConflictBlock)
                        .count();
                mergeInfoLabel.setText(String.format(
                        "%d file%s, %d chunk%s \u2014 ours: %s  theirs: %s",
                        files, files == 1 ? "" : "s",
                        chunks, chunks == 1 ? "" : "s",
                        oursCommit.substring(0, 8), theirsCommit.substring(0, 8)));
            } else {
                mergeInfoLabel.setText("No merge conflict");
                runStopButton.setEnabled(false);
                chunkModel.clear();
            }
        } catch (Exception e) {
            mergeInfoLabel.setText("Error detecting merge state");
            e.printStackTrace();
        }
    }

    private void startRun() {
        try {
            if (orchestrator != null && !orchestrator.isExhausted()) {
                // Resume a paused run — keep Apply enabled if a row is selected
                running = true;
                runStopButton.setText("Stop");
                updateVariantCountLabel();
                orchestrator.resume();
                return;
            }

            // First run (or previous run exhausted all variants — create fresh)
            int threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
            orchestrator = new PluginOrchestrator(
                    projectPath, session,
                    this::onVariantComplete,
                    this::onRunFinished,
                    this::onError,
                    true,
                    threads);

            running = true;
            runStopButton.setText("Stop");
            applyVariantButton.setEnabled(false);
            updateVariantCountLabel();

            orchestrator.start(oursCommit, theirsCommit);
        } catch (Exception e) {
            e.printStackTrace();
            mergeInfoLabel.setText("Run failed: " + e.getMessage());
            running = false;
            runStopButton.setText("Run");
        }
    }

    private void stopRun() {
        if (orchestrator != null) orchestrator.pause();
        running = false;
        runStopButton.setText("Run");
        applyVariantButton.setEnabled(dashboardTable.getSelectedRow() >= 0);
        updateVariantCountLabel();
    }

    /** Called on EDT by the orchestrator for each completed variant. */
    private void onVariantComplete(VariantResult result) {
        boolean isNewBest = session.addResult(result);
        if (showAllToggle.isSelected()) {
            dashboardModel.addResult(result);
        } else {
            dashboardModel.addIfMatches(result, session.getPinnedChunks(), session.getManualVersionSnapshot(), session.getChunkIndex());
        }
        updateVariantCountLabel();

        updateConsensusColumn();
    }

    private void updateVariantCountLabel() {
        int total = session.getHistory().size();
        int shown = dashboardModel.getRowCount();
        String base = total == shown
                ? "Variants: " + total
                : "Variants: " + shown + "/" + total;
        if (orchestrator != null && orchestrator.isExhausted()) {
            base += " (done)";
        } else if (!running && orchestrator != null) {
            base += " (paused)";
        }
        variantCountLabel.setText(base);
    }

    /** Called on EDT when the orchestrator encounters an error. */
    private void onError(String message) {
        running = false;
        runStopButton.setText("Run");
        mergeInfoLabel.setText("Error: " + message);
    }

    /** Called on EDT when the variant loop exits (exhausted or hard-cancelled). */
    private void onRunFinished(boolean exhausted) {
        running = false;
        runStopButton.setText("Run");
        if (exhausted) {
            runStopButton.setEnabled(false);
        }
        applyVariantButton.setEnabled(dashboardTable.getSelectedRow() >= 0);
        updateVariantCountLabel();
    }

    private static JLabel createFixedWidthLabel(String initialText, String widestExpected) {
        JLabel label = new JBLabel(initialText);
        FontMetrics fm = label.getFontMetrics(label.getFont());
        int width = fm.stringWidth(widestExpected) + 10; // small padding
        Dimension size = new Dimension(width, label.getPreferredSize().height);
        label.setPreferredSize(size);
        label.setMinimumSize(size);
        return label;
    }

    private void populateChunkTable(Map<String, ConflictFile> cfMap) {
        chunkModel.clear();
        List<ChunkKey> index = new ArrayList<>();

        // Scan actual working tree files for conflict marker previews
        Map<String, List<String[]>> filePreviews = new LinkedHashMap<>();
        for (String filePath : cfMap.keySet()) {
            filePreviews.put(filePath, scanConflictPreviews(projectPath.resolve(filePath)));
        }

        for (Map.Entry<String, ConflictFile> entry : cfMap.entrySet()) {
            String filePath = entry.getKey();
            List<String[]> previews = filePreviews.getOrDefault(filePath, List.of());
            int chunkIdx = 0;
            for (IMergeBlock block : entry.getValue().getMergeBlocks()) {
                if (block instanceof ConflictBlock cb) {
                    String ours = chunkIdx < previews.size() ? previews.get(chunkIdx)[0] : "";
                    String theirs = chunkIdx < previews.size() ? previews.get(chunkIdx)[1] : "";
                    chunkModel.addChunk(filePath, chunkIdx, cb, ours, theirs);
                    index.add(new ChunkKey(filePath, chunkIdx));
                    chunkIdx++;
                }
            }
        }
        List<ChunkKey> immutableIndex = List.copyOf(index);
        if (session != null) session.setChunkIndex(immutableIndex);
        dashboardModel.setChunkIndex(immutableIndex);
    }

    /**
     * Scan a working tree file for Git conflict markers and extract the first
     * non-empty OURS and THEIRS line for each conflict.
     * Returns a list of [oursPreview, theirsPreview] pairs, one per conflict.
     */
    private static List<String[]> scanConflictPreviews(Path file) {
        List<String[]> result = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                if (!lines.get(i).startsWith("<<<<<<<")) continue;

                String oursFirst = "";
                String theirsFirst = "";
                int j = i + 1;
                // Scan OURS side (between <<<<<<< and =======)
                for (; j < lines.size(); j++) {
                    if (lines.get(j).startsWith("=======")) break;
                    if (oursFirst.isEmpty() && !lines.get(j).isBlank()) {
                        oursFirst = lines.get(j).trim();
                    }
                }
                // Scan THEIRS side (between ======= and >>>>>>>)
                for (j = j + 1; j < lines.size(); j++) {
                    if (lines.get(j).startsWith(">>>>>>>")) break;
                    if (theirsFirst.isEmpty() && !lines.get(j).isBlank()) {
                        theirsFirst = lines.get(j).trim();
                    }
                }
                result.add(new String[]{oursFirst, theirsFirst});
            }
        } catch (IOException e) {
            // Fall back to empty previews
        }
        return result;
    }

    private void openChunkInEditor(int row) {
        String filePath = chunkModel.getFilePath(row);
        int chunkIdx = chunkModel.getChunkIdx(row);

        Path absolutePath = projectPath.resolve(filePath);
        VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(absolutePath.toString());
        if (vf == null) return;

        // Find the chunkIdx-th conflict marker in the file
        String content;
        try {
            content = new String(vf.contentsToByteArray(), vf.getCharset());
        } catch (IOException ex) {
            return;
        }
        String[] lines = content.split("\n", -1);
        int conflictCount = 0;
        int startLine = 0;
        int endLine = 0;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith("<<<<<<<")) {
                if (conflictCount == chunkIdx) {
                    startLine = i;
                    // find matching >>>>>>>
                    for (int j = i + 1; j < lines.length; j++) {
                        if (lines[j].startsWith(">>>>>>>")) {
                            endLine = j;
                            break;
                        }
                    }
                    break;
                }
                conflictCount++;
            }
        }

        OpenFileDescriptor descriptor = new OpenFileDescriptor(ideProject, vf, startLine, 0);
        Editor editor = FileEditorManager.getInstance(ideProject).openTextEditor(descriptor, true);
        if (editor != null && endLine > startLine) {
            int startOffset = editor.getDocument().getLineStartOffset(startLine);
            int endOffset = editor.getDocument().getLineEndOffset(endLine);
            editor.getSelectionModel().setSelection(startOffset, endOffset);
            editor.getCaretModel().moveToOffset(startOffset);
            editor.getScrollingModel().scrollToCaret(ScrollType.CENTER);
        }
    }

    private void appendLines(StringBuilder sb, ConflictBlock cb,
                             org.eclipse.jgit.api.CheckoutCommand.Stage stage) {
        var chunk = cb.getChunks().get(stage);
        if (chunk == null) return;
        var mergeResult = cb.getMergeResult();
        org.eclipse.jgit.diff.RawText rawText =
                (org.eclipse.jgit.diff.RawText) mergeResult.getSequences().get(chunk.getSequenceIndex());
        for (int i = chunk.getBegin(); i < chunk.getEnd(); i++) {
            sb.append(rawText.getString(i)).append('\n');
        }
    }

    private void updateConsensusColumn() {
        if (session == null || session.getChunkIndex().isEmpty()) return;
        Map<ChunkKey, Map<String, Double>> consensus =
                ChunkConsensus.compute(session.getHistory(), session.getChunkIndex());
        chunkModel.updateConsensus(consensus);
    }

    private void onResolutionChanged(int row, String resolution) {
        if (session == null) return;

        if ("MANUAL".equals(resolution)) {
            startManualEdit(row);
            return; // Pin/cancel handled by buttons
        } else if ("(auto)".equals(resolution)) {
            session.getPinnedChunks().remove(row);
            session.getManualTexts().remove(row);
        } else {
            session.getPinnedChunks().put(row, resolution);
            session.getManualTexts().remove(row);
        }

        // A pin change invalidates an exhausted generator — allow re-run
        if (orchestrator != null && orchestrator.isExhausted()) {
            orchestrator = null;
        }
        runStopButton.setEnabled(true);

        // Re-filter dashboard unless "Show All" is active
        if (!showAllToggle.isSelected()) {
            dashboardModel.applyFilter(session.getPinnedChunks(), session.getManualVersionSnapshot(), session.getChunkIndex());
        }
        updateVariantCountLabel();
    }

    private void startManualEdit(int row) {
        // Cancel any in-progress manual edit
        if (manualEditRow >= 0) cancelManualEdit();

        String initial;
        if (session.getManualTexts().containsKey(row)) {
            initial = session.getManualTexts().get(row);
        } else {
            // Read the full conflict chunk (with Git markers) from the working tree
            initial = readConflictChunkFromFile(row);
        }

        // Determine file extension from the conflict file path
        String filePath = chunkModel.getFilePath(row);
        String ext = "";
        int dot = filePath.lastIndexOf('.');
        if (dot >= 0) ext = filePath.substring(dot);

        try {
            manualEditFile = Files.createTempFile("manual-chunk" + row + "-", ext);
            Files.writeString(manualEditFile, initial);
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        manualEditRow = row;
        pinManualButton.setVisible(true);
        cancelManualButton.setVisible(true);

        // Open the temp file in IntelliJ's editor
        VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(
                manualEditFile.toAbsolutePath().toString());
        if (vf != null) {
            FileEditorManager.getInstance(ideProject).openFile(vf, true);
        }
    }

    private void confirmManualEdit() {
        if (manualEditRow < 0 || manualEditFile == null) return;

        try {
            String text = Files.readString(manualEditFile);
            session.getManualTexts().put(manualEditRow, text);
            session.getPinnedChunks().put(manualEditRow, "MANUAL");
            session.bumpManualVersion(manualEditRow);
        } catch (IOException e) {
            e.printStackTrace();
        }

        cleanupManualEdit();

        // A pin change invalidates an exhausted generator — allow re-run
        if (orchestrator != null && orchestrator.isExhausted()) {
            orchestrator = null;
        }
        runStopButton.setEnabled(true);

        if (!showAllToggle.isSelected()) {
            dashboardModel.applyFilter(session.getPinnedChunks(), session.getManualVersionSnapshot(), session.getChunkIndex());
        }
        updateVariantCountLabel();
    }

    private void cancelManualEdit() {
        if (manualEditRow < 0) return;

        chunkModel.setResolution(manualEditRow, "(auto)");
        session.getManualTexts().remove(manualEditRow);
        session.getPinnedChunks().remove(manualEditRow);

        cleanupManualEdit();

        if (!showAllToggle.isSelected()) {
            dashboardModel.applyFilter(session.getPinnedChunks(), session.getManualVersionSnapshot(), session.getChunkIndex());
        }
        updateVariantCountLabel();
    }

    /**
     * Read the full conflict chunk (including Git markers) from the working tree file.
     */
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
        // Close the editor tab and delete the temp file
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
        pinManualButton.setVisible(false);
        cancelManualButton.setVisible(false);
    }

    private void applySelectedVariant() {
        int row = dashboardTable.getSelectedRow();
        if (row < 0) return;
        applyVariant(dashboardModel.getResult(row));
    }

    private void applyVariant(VariantResult result) {
        if (result == null || result.patternAssignment() == null) return;
        var context = session.getBuildContext();
        if (context == null) return;

        // Build global chunk index → manual text lookup
        Map<Integer, String> manualTexts = session.getManualTexts();

        // Track global chunk index across files (must iterate in same order as chunkIndex)
        int globalIdx = 0;
        for (Map.Entry<String, ConflictFile> cfEntry : context.getConflictFileMap().entrySet()) {
            String filePath = cfEntry.getKey();
            ConflictFile cf = cfEntry.getValue();
            List<String> patterns = result.patternAssignment().get(filePath);
            if (patterns == null) {
                // Count chunks in this file to advance globalIdx
                for (IMergeBlock block : cf.getMergeBlocks()) {
                    if (block instanceof ConflictBlock) globalIdx++;
                }
                continue;
            }

            ConflictFile resolved = applyPatterns(cf, patterns, globalIdx, manualTexts);
            for (IMergeBlock block : cf.getMergeBlocks()) {
                if (block instanceof ConflictBlock) globalIdx++;
            }

            Path target = projectPath.resolve(filePath);
            try {
                Files.createDirectories(target.getParent());
                Files.writeString(target, resolved.toString());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        JOptionPane.showMessageDialog(root,
                "Applied variant #" + result.variantIndex() + " to working tree.",
                "Variant Applied", JOptionPane.INFORMATION_MESSAGE);
    }

    private ConflictFile applyPatterns(ConflictFile original, List<String> patterns,
                                       int globalIdxStart, Map<Integer, String> manualTexts) {
        ConflictFile resolved = new ConflictFile();
        resolved.setClassPath(original.getClassPath());
        List<IMergeBlock> blocks = new ArrayList<>();
        int patternIdx = 0;
        int globalIdx = globalIdxStart;
        for (IMergeBlock block : original.getMergeBlocks()) {
            if (block instanceof ConflictBlock cb) {
                ConflictBlock clone = cb.clone();
                String manualText = manualTexts.get(globalIdx);
                if (manualText != null) {
                    clone.setPattern(new ManualPattern(manualText));
                } else {
                    clone.setPattern(
                            ch.unibe.cs.mergeci.model.patterns.PatternFactory.fromName(
                                    patterns.get(patternIdx)));
                }
                patternIdx++;
                globalIdx++;
                blocks.add(clone);
            } else {
                blocks.add(block);
            }
        }
        resolved.setMergeBlocks(blocks);
        return resolved;
    }

    public JComponent getRoot() {
        return root;
    }

    // =========================================================================
    // Table models
    // =========================================================================

    static class VariantTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"#", "Status", "Modules", "Tests", "Score", "Time (s)", "Patterns"};
        private final List<VariantResult> allResults = new ArrayList<>();
        private final List<VariantResult> filtered = new ArrayList<>();
        private List<ChunkKey> chunkIndex = List.of();

        void setChunkIndex(List<ChunkKey> chunkIndex) {
            this.chunkIndex = chunkIndex;
        }

        void addResult(VariantResult r) {
            allResults.add(r);
            int insertAt = findSortedInsertionPoint(filtered, r);
            filtered.add(insertAt, r);
            fireTableRowsInserted(insertAt, insertAt);
        }

        void addIfMatches(VariantResult r, Map<Integer, String> pins,
                          Map<Integer, Integer> manualVersions, List<ChunkKey> chunkIndex) {
            allResults.add(r);
            if (matchesPins(r, pins, manualVersions, chunkIndex)) {
                int insertAt = findSortedInsertionPoint(filtered, r);
                filtered.add(insertAt, r);
                fireTableRowsInserted(insertAt, insertAt);
            }
        }

        /** Re-filter visible results based on current pins. A variant matches if,
         *  for every pinned chunk, the variant's assignment at that global index
         *  equals the pinned pattern. MANUAL pins require the variant's manual
         *  version to match the current version (hides stale manual variants). */
        void applyFilter(Map<Integer, String> pins, Map<Integer, Integer> manualVersions,
                         List<ChunkKey> chunkIndex) {
            int oldSize = filtered.size();
            filtered.clear();
            if (oldSize > 0) fireTableRowsDeleted(0, oldSize - 1);

            for (VariantResult r : allResults) {
                if (matchesPins(r, pins, manualVersions, chunkIndex)) {
                    int insertAt = findSortedInsertionPoint(filtered, r);
                    filtered.add(insertAt, r);
                    fireTableRowsInserted(insertAt, insertAt);
                }
            }
        }

        private boolean matchesPins(VariantResult r, Map<Integer, String> pins,
                                     Map<Integer, Integer> manualVersions,
                                     List<ChunkKey> chunkIndex) {
            if (pins.isEmpty() || r.patternAssignment() == null) return true;
            for (Map.Entry<Integer, String> pin : pins.entrySet()) {
                int globalIdx = pin.getKey();
                String pinnedPattern = pin.getValue();
                if ("MANUAL".equals(pinnedPattern)) {
                    // MANUAL pin: variant must have been built with the current manual version
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

        private int findSortedInsertionPoint(List<VariantResult> list, VariantResult r) {
            for (int i = 0; i < list.size(); i++) {
                if (compareVariants(r, list.get(i)) > 0) return i;
            }
            return list.size();
        }

        /** Compare two variants: scored beats unscored; among scored, delegate to
         *  {@link VariantScore#compareTo} (modules → tests → simplicity → variant index). */
        private int compareVariants(VariantResult a, VariantResult b) {
            if (a.score() != null && b.score() != null) return a.score().compareTo(b.score());
            if (a.score() != null) return 1;   // scored beats unscored
            if (b.score() != null) return -1;
            return 0;
        }

        /** Show all results, ignoring pin filters. */
        void showAll() {
            int oldSize = filtered.size();
            filtered.clear();
            if (oldSize > 0) fireTableRowsDeleted(0, oldSize - 1);

            for (VariantResult r : allResults) {
                int insertAt = findSortedInsertionPoint(filtered, r);
                filtered.add(insertAt, r);
                fireTableRowsInserted(insertAt, insertAt);
            }
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
                // Fallback if chunk index not yet available
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

    static final String[] RESOLUTION_OPTIONS = {"(auto)", "OURS", "THEIRS", "BASE", "EMPTY", "MANUAL"};

    static class ChunkTableModel extends AbstractTableModel {
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
            return col == 5; // Resolution column
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

}
