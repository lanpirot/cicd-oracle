package org.example.cicdmergeoracle.cicdMergeTool.ui;

import ch.unibe.cs.mergeci.model.ConflictBlock;
import ch.unibe.cs.mergeci.model.ConflictFile;
import ch.unibe.cs.mergeci.model.IMergeBlock;
import ch.unibe.cs.mergeci.model.patterns.OursPattern;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.ui.popup.Balloon;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import org.example.cicdmergeoracle.cicdMergeTool.model.BlockGroup;
import org.example.cicdmergeoracle.cicdMergeTool.model.ChunkKey;
import org.example.cicdmergeoracle.cicdMergeTool.service.BlockGroupComputer;
import org.example.cicdmergeoracle.cicdMergeTool.service.ChunkConsensus;
import org.example.cicdmergeoracle.cicdMergeTool.service.ManualPinOverlay;
import org.example.cicdmergeoracle.cicdMergeTool.service.OracleSession;
import org.example.cicdmergeoracle.cicdMergeTool.service.PluginOrchestrator;
import org.example.cicdmergeoracle.cicdMergeTool.service.VariantResult;
import org.example.cicdmergeoracle.cicdMergeTool.util.GitUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MergeResolutionPanel {
    private static final Logger LOG = LoggerFactory.getLogger(MergeResolutionPanel.class);
    private static final String[] SPINNER_FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};

    private final Path projectPath;
    private final Project ideProject;
    private final JPanel root = new JPanel(new BorderLayout());

    // Status bar — left
    private final JLabel mergeInfoLabel = new JBLabel();
    private final JLabel variantCountLabel = createFixedWidthLabel("Variants: 0",
            "Variants: 9999/9999 (stopped)");
    private final JButton runStopButton = new JButton("Run");
    private boolean running;
    private final JButton applyVariantButton = new JButton("Apply Variant");
    private final JCheckBox showAllToggle = new JCheckBox("Ignore Pins");
    private boolean suppressResolutionListener;

    // Status bar — right (progress)
    private final JLabel inFlightLabel = new JBLabel();
    private int spinnerIndex;
    private int currentInFlight;
    private final Timer spinnerTimer;

    // Live dashboard table
    private final VariantTableModel dashboardModel = new VariantTableModel();
    private final JTable dashboardTable = new JTable(dashboardModel);

    // Chunk selector table
    private final ChunkTableModel chunkModel = new ChunkTableModel();
    private final JTable chunkTable = new JTable(chunkModel);

    // Manual edit
    private final JButton pinManualButton = new JButton("Pin Manual");
    private final JButton cancelManualButton = new JButton("Cancel Manual");
    private final ManualEditWorkflow manualEdit;

    // State
    private OracleSession session;
    private PluginOrchestrator orchestrator;
    private String oursCommit;
    private String theirsCommit;
    private Map<String, ConflictFile> conflictFileMap;

    // Tooltip balloon
    private Balloon tooltipBalloon;
    private int lastBalloonRow = -1;
    private String pendingBalloonHtml;
    private Timer balloonTimer;
    private Timer dismissTimer;
    private boolean dismissPending;

    public MergeResolutionPanel(File projectPath, Project ideProject) {
        this.projectPath = projectPath.toPath();
        this.ideProject = ideProject;
        this.manualEdit = new ManualEditWorkflow(
                ideProject, this.projectPath, chunkModel, pinManualButton, cancelManualButton,
                this::onPinChanged);
        this.spinnerTimer = new Timer(100, e -> {
            spinnerIndex = (spinnerIndex + 1) % SPINNER_FRAMES.length;
            updateInFlightLabel(currentInFlight);
        });

        configureDashboardColumns();
        configureChunkColumns();

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
        pinManualButton.addActionListener(e -> {
            if (session != null) confirmManualEdit();
        });
        cancelManualButton.addActionListener(e -> {
            if (session != null) cancelManualEdit();
        });
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

        // Tooltip balloon for dashboard — shows clickable links to failed tests/modules
        balloonTimer = new Timer(400, e -> showTooltipBalloon());
        balloonTimer.setRepeats(false);
        dismissTimer = new Timer(1000, e -> {
            dismissBalloon();
            dismissPending = false;
        });
        dismissTimer.setRepeats(false);
        dashboardTable.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int row = dashboardTable.rowAtPoint(e.getPoint());
                if (row == lastBalloonRow) {
                    // Back on the same row — cancel pending dismiss
                    dismissTimer.stop();
                    dismissPending = false;
                    return;
                }
                if (dismissPending) return; // grace period — block new tooltips
                if (tooltipBalloon != null) {
                    // Start 1-second grace period before dismissing
                    dismissPending = true;
                    dismissTimer.restart();
                    balloonTimer.stop();
                    return;
                }
                balloonTimer.stop();
                lastBalloonRow = row;
                if (row < 0 || row >= dashboardModel.getRowCount()) return;
                String html = dashboardModel.getResult(row).buildTooltip();
                if (html == null) return;
                pendingBalloonHtml = html;
                balloonTimer.restart();
            }
        });

        // Bold rows where consensus > 75%
        chunkTable.setDefaultRenderer(Object.class, new javax.swing.table.DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                    boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                if (row < chunkModel.getRowCount() && chunkModel.getMaxConsensusPct(row) > 75) {
                    c.setFont(c.getFont().deriveFont(Font.BOLD));
                } else {
                    c.setFont(c.getFont().deriveFont(Font.PLAIN));
                }
                return c;
            }
        });

        // Resolution dropdown editor
        JComboBox<String> resolutionCombo = new JComboBox<>(ChunkTableModel.RESOLUTION_OPTIONS);
        chunkTable.getColumnModel().getColumn(5).setCellEditor(new DefaultCellEditor(resolutionCombo));
        chunkModel.addTableModelListener(e -> {
            if (suppressResolutionListener) return;
            if (e.getColumn() == 5 && e.getType() == javax.swing.event.TableModelEvent.UPDATE) {
                int row = e.getFirstRow();
                if (row >= 0 && row < chunkModel.getRowCount()) {
                    onResolutionChanged(row, chunkModel.getResolution(row));
                }
            }
        });
    }

    /** Variant table: compact fixed columns, Patterns gets all remaining space. */
    private void configureDashboardColumns() {
        dashboardTable.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        var cm = dashboardTable.getColumnModel();
        // #, Modules, Tests, Time (s) — small fixed; Patterns expands
        int[] pref = {40, 75, 75, 75};
        for (int i = 0; i < pref.length && i < cm.getColumnCount(); i++) {
            var col = cm.getColumn(i);
            col.setPreferredWidth(pref[i]);
            col.setMaxWidth(pref[i] + 25);
        }
    }

    /** Chunk table: File and Consensus wide, Chunk and Resolution narrow, dropdown indicator on Resolution. */
    private void configureChunkColumns() {
        chunkTable.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
        var cm = chunkTable.getColumnModel();
        // File=0, Chunk=1, OURS=2, THEIRS=3, Consensus=4, Resolution=5
        int[] pref = {200, 45, 140, 140, 180, 90};
        int[] max  = {500, 60, 300, 300, 400, 110};
        for (int i = 0; i < pref.length && i < cm.getColumnCount(); i++) {
            var col = cm.getColumn(i);
            col.setPreferredWidth(pref[i]);
            col.setMaxWidth(max[i]);
        }

        // Resolution column: append ▾ to hint that it's a dropdown
        cm.getColumn(5).setCellRenderer(new javax.swing.table.DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                    boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                setText(value + " \u25BE");
                return c;
            }
        });
    }

    private JPanel createStatusBar() {
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        left.add(mergeInfoLabel);
        left.add(variantCountLabel);
        left.add(showAllToggle);
        left.add(runStopButton);
        left.add(applyVariantButton);
        left.add(pinManualButton);
        left.add(cancelManualButton);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        right.add(inFlightLabel);

        JPanel bar = new JPanel(new BorderLayout());
        bar.add(left, BorderLayout.WEST);
        bar.add(right, BorderLayout.EAST);
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
            LOG.warn("Failed to detect merge state", e);
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
            int threads = Math.max(1, (Runtime.getRuntime().availableProcessors() + 1) / 2);
            orchestrator = new PluginOrchestrator(
                    projectPath, session,
                    this::onVariantComplete,
                    this::onRunFinished,
                    this::onError,
                    this::onInFlightChanged,
                    true,
                    threads);

            running = true;
            runStopButton.setText("Stop");
            applyVariantButton.setEnabled(false);
            updateVariantCountLabel();

            orchestrator.start(oursCommit, theirsCommit);
        } catch (Exception e) {
            LOG.error("Failed to start variant run", e);
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
        onInFlightChanged(0);
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
        onInFlightChanged(0);
    }

    /** Called on EDT when the number of in-flight (building) variants changes. */
    private void onInFlightChanged(int inFlight) {
        currentInFlight = inFlight;
        updateInFlightLabel(inFlight);
        if (inFlight > 0 && !spinnerTimer.isRunning()) {
            spinnerTimer.start();
        } else if (inFlight == 0 && spinnerTimer.isRunning()) {
            spinnerTimer.stop();
        }
    }

    private void updateInFlightLabel(int inFlight) {
        if (inFlight > 0) {
            inFlightLabel.setText(SPINNER_FRAMES[spinnerIndex] + " [" + inFlight + "] Variants building");
        } else {
            inFlightLabel.setText("");
        }
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
        Map<Integer, BlockGroup> blockGroupMap = new LinkedHashMap<>();

        // Scan actual working tree files for conflict marker previews
        Map<String, List<String[]>> filePreviews = new LinkedHashMap<>();
        for (String filePath : cfMap.keySet()) {
            filePreviews.put(filePath, scanConflictPreviews(projectPath.resolve(filePath)));
        }

        int globalIdx = 0;
        for (Map.Entry<String, ConflictFile> entry : cfMap.entrySet()) {
            String filePath = entry.getKey();
            ConflictFile cf = entry.getValue();
            List<String[]> previews = filePreviews.getOrDefault(filePath, List.of());

            List<BlockGroup> groups = computeBlockGroups(filePath, cf, previews.size(), globalIdx);

            for (IMergeBlock block : cf.getMergeBlocks()) {
                if (block instanceof ConflictBlock cb) {
                    BlockGroup group = blockGroupMap.containsKey(globalIdx)
                            ? blockGroupMap.get(globalIdx) : findGroup(groups, globalIdx);
                    int wtIdx = group != null ? group.workingTreeChunkIndex() : 0;
                    String ours = wtIdx < previews.size() ? previews.get(wtIdx)[0] : "";
                    String theirs = wtIdx < previews.size() ? previews.get(wtIdx)[1] : "";
                    chunkModel.addChunk(filePath, wtIdx, cb, ours, theirs);
                    index.add(new ChunkKey(filePath, wtIdx));
                    if (group != null) blockGroupMap.put(globalIdx, group);
                    globalIdx++;
                }
            }
        }
        List<ChunkKey> immutableIndex = List.copyOf(index);
        if (session != null) {
            session.setChunkIndex(immutableIndex);
            session.setBlockGroupMap(blockGroupMap);
        }
        dashboardModel.setChunkIndex(immutableIndex);
    }

    private static BlockGroup findGroup(List<BlockGroup> groups, int globalIdx) {
        for (BlockGroup g : groups) {
            if (g.memberGlobalIndices().contains(globalIdx)) return g;
        }
        return null;
    }

    /**
     * Compute block groups: map each JGit ConflictBlock to the working-tree
     * conflict it belongs to. Delegates to {@link BlockGroupComputer}.
     */
    private List<BlockGroup> computeBlockGroups(String filePath, ConflictFile cf,
                                                 int wtConflictCount, int globalIdxStart) {
        List<IMergeBlock> blocks = cf.getMergeBlocks();
        List<String> wtOursSections = extractOursSections(projectPath.resolve(filePath));
        OursPattern oursPattern = new OursPattern();

        List<BlockGroupComputer.BlockEntry> entries = new ArrayList<>();
        for (IMergeBlock block : blocks) {
            if (block instanceof ConflictBlock cb) {
                entries.add(new BlockGroupComputer.BlockEntry(
                        oursPattern.apply(cb.getMergeResult(), cb.getChunks()), true));
            } else {
                entries.add(new BlockGroupComputer.BlockEntry(block.getLines(), false));
            }
        }

        return BlockGroupComputer.compute(entries, wtOursSections, globalIdxStart, filePath);
    }

    /**
     * Extract the OURS text for each conflict in a working-tree file.
     * Handles both merge style ({@code <<<<<<<}...{@code =======}) and
     * diff3 style ({@code <<<<<<<}...{@code |||||||}...{@code =======}).
     */
    private static List<String> extractOursSections(Path file) {
        List<String> sections = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                if (!lines.get(i).startsWith("<<<<<<<")) continue;
                List<String> oursLines = new ArrayList<>();
                for (int j = i + 1; j < lines.size(); j++) {
                    if (lines.get(j).startsWith("=======")
                            || lines.get(j).startsWith("|||||||")) break;
                    oursLines.add(lines.get(j));
                }
                sections.add(String.join("\n", oursLines));
            }
        } catch (IOException e) {
            // Fall back to empty
        }
        return sections;
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

    // ---- Tooltip balloon with navigable links ----

    private void showTooltipBalloon() {
        dismissBalloon();
        if (pendingBalloonHtml == null || lastBalloonRow < 0
                || lastBalloonRow >= dashboardModel.getRowCount()) return;

        tooltipBalloon = JBPopupFactory.getInstance()
                .createHtmlTextBalloonBuilder(pendingBalloonHtml, MessageType.INFO, e -> {
                    if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                        handleTooltipNavigation(e.getDescription());
                        dismissBalloon();
                    }
                })
                .setHideOnClickOutside(true)
                .setHideOnKeyOutside(true)
                .createBalloon();

        Rectangle cellRect = dashboardTable.getCellRect(lastBalloonRow, 0, true);
        RelativePoint rp = new RelativePoint(dashboardTable,
                new Point(cellRect.x + cellRect.width / 2, cellRect.y + cellRect.height));
        tooltipBalloon.show(rp, Balloon.Position.below);
    }

    private void dismissBalloon() {
        dismissTimer.stop();
        dismissPending = false;
        lastBalloonRow = -1;
        if (tooltipBalloon != null) {
            tooltipBalloon.hide();
            tooltipBalloon = null;
        }
    }

    private void handleTooltipNavigation(String href) {
        if (href.startsWith("test:")) {
            navigateToTest(href.substring(5));
        } else if (href.startsWith("module:")) {
            navigateToModule(href.substring(7));
        }
    }

    private void navigateToTest(String fqClassName) {
        String simpleClassName = fqClassName.substring(fqClassName.lastIndexOf('.') + 1);
        String fileName = simpleClassName + ".java";
        String pathSuffix = "/" + fqClassName.replace('.', '/') + ".java";

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            VirtualFile target = ReadAction.compute(() -> {
                Collection<VirtualFile> files = FilenameIndex.getVirtualFilesByName(
                        fileName, GlobalSearchScope.projectScope(ideProject));
                for (VirtualFile vf : files) {
                    if (vf.getPath().endsWith(pathSuffix)) return vf;
                }
                return files.isEmpty() ? null : files.iterator().next();
            });
            if (target != null) {
                ApplicationManager.getApplication().invokeLater(() ->
                        new OpenFileDescriptor(ideProject, target).navigate(true));
            }
        });
    }

    private void navigateToModule(String moduleName) {
        VirtualFile projectRoot = LocalFileSystem.getInstance().findFileByPath(projectPath.toString());
        if (projectRoot == null) return;
        VirtualFile moduleDir = findModuleDir(projectRoot, moduleName);
        if (moduleDir != null) {
            VirtualFile pomXml = moduleDir.findChild("pom.xml");
            VirtualFile target = pomXml != null ? pomXml : moduleDir;
            new OpenFileDescriptor(ideProject, target).navigate(true);
        }
    }

    private static VirtualFile findModuleDir(VirtualFile root, String moduleName) {
        VirtualFile child = root.findChild(moduleName);
        if (child != null && child.isDirectory()) return child;
        for (VirtualFile c : root.getChildren()) {
            if (c.isDirectory() && !c.getName().startsWith(".")) {
                VirtualFile found = findModuleDir(c, moduleName);
                if (found != null) return found;
            }
        }
        return null;
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
            manualEdit.startManualEdit(row, session);
            return;
        } else if ("(auto)".equals(resolution)) {
            // If this row is part of a multi-CB group with MANUAL, unpin the whole group
            BlockGroup group = session.getBlockGroupMap().get(row);
            if (group != null && group.isMultiBlock()
                    && "MANUAL".equals(session.getPinnedChunks().get(row))) {
                for (int idx : group.memberGlobalIndices()) {
                    session.getPinnedChunks().remove(idx);
                    session.getManualTexts().remove(idx);
                }
                updateGroupMemberResolutions(row, "(auto)");
            } else {
                session.getPinnedChunks().remove(row);
                session.getManualTexts().remove(row);
            }
        } else {
            // Non-manual pin: if group was MANUAL, unpin the group's MANUAL first
            BlockGroup group = session.getBlockGroupMap().get(row);
            if (group != null && group.isMultiBlock()
                    && "MANUAL".equals(session.getPinnedChunks().get(row))) {
                for (int idx : group.memberGlobalIndices()) {
                    session.getManualTexts().remove(idx);
                    if (idx != row) {
                        session.getPinnedChunks().remove(idx);
                    }
                }
                updateGroupMemberResolutions(row, "(auto)");
            }
            session.getPinnedChunks().put(row, resolution);
            session.getManualTexts().remove(row);
        }

        onPinChanged();
    }

    private void confirmManualEdit() {
        int row = manualEdit.getManualEditRow();
        manualEdit.confirmManualEdit(session);
        // Update UI for group members without triggering the listener
        updateGroupMemberResolutions(row, "MANUAL");
        onPinChanged();
    }

    private void cancelManualEdit() {
        int row = manualEdit.getManualEditRow();
        String prev = manualEdit.getPreviousResolution();
        manualEdit.cancelManualEdit(session);
        // Restore the resolution dropdown for this row and group members
        suppressResolutionListener = true;
        try {
            chunkModel.setResolution(row, prev != null ? prev : "(auto)");
        } finally {
            suppressResolutionListener = false;
        }
        updateGroupMemberResolutions(row, prev != null ? prev : "(auto)");
        onPinChanged();
    }

    /** Set resolution display for all group members, suppressing the listener. */
    private void updateGroupMemberResolutions(int row, String resolution) {
        if (session == null || row < 0) return;
        BlockGroup group = session.getBlockGroupMap().get(row);
        if (group == null || !group.isMultiBlock()) return;
        suppressResolutionListener = true;
        try {
            for (int idx : group.memberGlobalIndices()) {
                if (idx != row) chunkModel.setResolution(idx, resolution);
            }
        } finally {
            suppressResolutionListener = false;
        }
    }

    private void onPinChanged() {
        if (orchestrator != null && orchestrator.isExhausted()) {
            orchestrator = null;
        }
        runStopButton.setEnabled(true);

        if (!showAllToggle.isSelected()) {
            dashboardModel.applyFilter(session.getPinnedChunks(), session.getManualVersionSnapshot(), session.getChunkIndex());
        }
        updateVariantCountLabel();
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
                LOG.warn("Failed to write resolved file: {}", target, e);
            }
        }
        JOptionPane.showMessageDialog(root,
                "Applied variant #" + result.variantIndex() + " to working tree.",
                "Variant Applied", JOptionPane.INFORMATION_MESSAGE);
    }

    private ConflictFile applyPatterns(ConflictFile original, List<String> patterns,
                                       int globalIdxStart, Map<Integer, String> manualTexts) {
        Map<Integer, BlockGroup> groupMap = session != null
                ? session.getBlockGroupMap() : Map.of();
        ManualPinOverlay.Result r = ManualPinOverlay.apply(
                original.getMergeBlocks(), globalIdxStart, manualTexts, groupMap, patterns);
        ConflictFile resolved = new ConflictFile();
        resolved.setClassPath(original.getClassPath());
        resolved.setMergeBlocks(r.blocks());
        return resolved;
    }

    public JComponent getRoot() {
        return root;
    }

    /** Called when the tool window is disposed (IDE shutdown or project close). */
    void dispose() {
        spinnerTimer.stop();
        if (balloonTimer != null) balloonTimer.stop();
        if (dismissTimer != null) dismissTimer.stop();
        dismissBalloon();
        if (orchestrator != null) {
            orchestrator.cancel();
            orchestrator.cleanupTempDir();
        }
    }
}
