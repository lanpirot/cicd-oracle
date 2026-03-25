package org.example.cicdmergeoracle.cicdMergeTool.ui;


import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.treeStructure.Tree;
import git4idea.actions.GitAbortOperationAction;
import org.example.cicdmergeoracle.cicdMergeTool.model.patterns.BasePattern;
import org.example.cicdmergeoracle.cicdMergeTool.model.patterns.CombinePattern;
import org.example.cicdmergeoracle.cicdMergeTool.model.patterns.EmptyPattern;
import org.example.cicdmergeoracle.cicdMergeTool.model.patterns.IPattern;
import org.example.cicdmergeoracle.cicdMergeTool.model.patterns.OursPattern;
import org.example.cicdmergeoracle.cicdMergeTool.model.patterns.TheirsPattern;
import org.example.cicdmergeoracle.cicdMergeTool.service.projectRunners.maven.CompilationResult;
import org.example.cicdmergeoracle.cicdMergeTool.service.projectRunners.maven.ConflictResolutionService;
import org.example.cicdmergeoracle.cicdMergeTool.service.projectRunners.maven.ResolutionResultDTO;
import org.example.cicdmergeoracle.cicdMergeTool.service.projectRunners.maven.TestTotal;
import org.example.cicdmergeoracle.cicdMergeTool.util.MyFileUtils;
import org.example.cicdmergeoracle.cicdMergeTool.util.GitUtils;
import org.example.cicdmergeoracle.cicdMergeTool.util.model.MergeInfo;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

public class MergeResolutionPanel {
    private final Path projectPath;
    private ResolutionResultDTO resolutionResultDTO;
    private String oursCommit;
    private String theirsCommit;
    private final JPanel root = new JPanel(new BorderLayout());

    private final JLabel statusLabel = new JBLabel();
    private final JLabel oursLabel = new JBLabel();
    private final JLabel theirsLabel = new JBLabel();
    private final JLabel numOfConflictChunksLabel = new JBLabel();
    private final JLabel numOfConflictFilesLabel = new JBLabel();
    private final JLabel numOfVariantsLabel = new JBLabel();
    private Map<String, Integer> mapConflicts;
    private final JButton runButton = new JButton("Run Conflict Resolution");
    private final JButton sortButton = new JButton("Sort by tests");
    private final List<JCheckBox> patternsCheckBoxes = getPatternSelectionCheckBox();
    private final JLabel totalExecutionTimeLabel = new JBLabel();
    private final JLabel executionStatus = new JBLabel();
    private final JCheckBox isUseDaemonMavenCheckBox = new JCheckBox("Use Maven Daemon");
    private final JCheckBox isUseCache = new JCheckBox("Use Cache");
    private ConflictResolutionService conflictResolutionService;
    private final ComboBox<ConflictDetectionMode> conflictDetectionModeComboBox = new ComboBox<>(ConflictDetectionMode.values());

//    private final ResolutionTableModel model = new ResolutionTableModel();
//    private final JBTable table = new JBTable(model);

    DefaultMutableTreeNode treeRoot = new DefaultMutableTreeNode("Conflict Resolutions");
    DefaultTreeModel treeModel = new DefaultTreeModel(treeRoot);
    Tree tree = new Tree(treeModel);

    public MergeResolutionPanel(File projectPath, Project project) {
        tree.setCellRenderer(new MyCustomTreeRenderer());
        resolutionResultDTO = new ResolutionResultDTO();
//        ResolutionResultDTO.setResolutionTableModel(model);
        resolutionResultDTO.setVisitor(this::updateVariant);
        this.projectPath = projectPath.toPath();
        System.out.printf("Project path %s%n", projectPath.getAbsolutePath());

        tree.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 16));
        root.add(createStatusPanel(projectPath), BorderLayout.NORTH);
//        root.add(new JBScrollPane(table), BorderLayout.CENTER);
        root.add(new JBScrollPane(tree), BorderLayout.CENTER);
        MergeStateListener.register(project, () -> updateMergeStatusOfProject(projectPath));

        runButton.addActionListener(e -> makeResolution());

        sortButton.addActionListener(e -> {
                    sortVariants(Comparator.comparingInt(((ResolutionResultDTO.Variant x) -> calculateNumberOfPassedTest(
                            x.getTestTotal()))).reversed());

                }
        );
        sortButton.setEnabled(false);

        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                if (path == null) return;

                tree.setSelectionPath(path);
                DefaultMutableTreeNode node =
                        (DefaultMutableTreeNode) path.getLastPathComponent();

                createPopupMenu(node).show(e.getComponent(), e.getX(), e.getY());


            }
        });
    }

    private List<JCheckBox> getPatternSelectionCheckBox() {

        List<JCheckBox> jCheckBoxes = new ArrayList<>();
        List<Class<? extends IPattern>> patterns = getPatterns();
        for (Class<? extends IPattern> pattern : patterns) {
            JCheckBox jCheckBox = new JCheckBox(pattern.getSimpleName());
            jCheckBox.addChangeListener(e -> {
                int numOfConflictChunks = mapConflicts.values().stream().reduce(0, Integer::sum);
                int numberOfPatters = Math.toIntExact(patternsCheckBoxes.stream().filter(AbstractButton::isSelected).count());
                numOfVariantsLabel.setText("# variants: " + (int)Math.pow(numberOfPatters,numOfConflictChunks));
            });
            jCheckBoxes.add(jCheckBox);
        }
        return jCheckBoxes;
    }

    private void expandAll(DefaultMutableTreeNode defaultMutableTreeNode) {
        Enumeration<?> e = defaultMutableTreeNode.children();
        while (e.hasMoreElements()) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) e.nextElement();
            tree.expandPath(new TreePath(node.getPath()));
            expandAll(node);
        }
    }


    private JPopupMenu createPopupMenu(DefaultMutableTreeNode node) {

        JPopupMenu menu = new JPopupMenu();

        if (node.getUserObject() instanceof ResolutionResultDTO.Variant variant) {
            if (variant.getStatus() == ResolutionResultDTO.variantStatus.FINISHED) {

                JMenuItem applyVariant = new JMenuItem("Apply variant");
                applyVariant.addActionListener(e -> {
                    System.out.printf("Apply variant %s%n", variant.getName());
                    Path variantPath = variant.getPath();
                    List<Path> conflictFiles = variant.getResolutionPatterns().getConflictPatterns().keySet().stream().map(Paths::get).toList();
                    for (Path conflictFile : conflictFiles) {
                        Path fullPathOrigin = variantPath.resolve(conflictFile);
                        Path fullPathTarget = projectPath.resolve(conflictFile);
                        System.out.printf("Copying %s to %s%n", fullPathOrigin, fullPathTarget);
                        try {
                            MyFileUtils.copyDirectoryCompatibityMode(fullPathOrigin.toFile(), fullPathTarget.toFile());
                        } catch (IOException ex) {
                            ex.printStackTrace();
                            throw new RuntimeException(ex);
                        }
                    }
                });
                menu.add(applyVariant);
            }
        }

        JMenuItem expand = new JMenuItem("Expand");
        expand.addActionListener(e -> expandAll(node));
        menu.add(expand);

        JMenuItem collapse = new JMenuItem("Collapse");
        collapse.addActionListener(e -> tree.collapsePath(new TreePath(node.getPath())));
        menu.add(collapse);


        return menu;
    }

    private JPanel createStatusPanel(File projectPath) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        updateMergeStatusOfProject(projectPath);

        JPanel topPanel = new JPanel();
        topPanel.setLayout(new FlowLayout(FlowLayout.LEFT));
//        topPanel.add(conflictDetectionModeComboBox);
        panel.add(topPanel);

        JPanel statusPanel = new JPanel();
        statusPanel.setLayout(new GridLayout(1, 2, 100, 0));

        JPanel mergeInfo = new JPanel();
        mergeInfo.setLayout(new BoxLayout(mergeInfo, BoxLayout.Y_AXIS));
        mergeInfo.add(statusLabel);
        mergeInfo.add(oursLabel);
        mergeInfo.add(theirsLabel);
        mergeInfo.add(numOfConflictChunksLabel);
        mergeInfo.add(numOfConflictFilesLabel);
        mergeInfo.add(numOfVariantsLabel);

        statusPanel.add(mergeInfo);

        JPanel executionInfo = new JPanel();
        executionInfo.setLayout(new BoxLayout(executionInfo, BoxLayout.Y_AXIS));
        executionInfo.add(executionStatus);
        executionInfo.add(totalExecutionTimeLabel);

        statusPanel.add(executionInfo);

        panel.add(statusPanel);
        panel.add(Box.createVerticalStrut(8));

        JPanel controlButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controlButtons.add(runButton);
        controlButtons.add(sortButton);

        panel.add(Box.createVerticalStrut(8));
        panel.add(controlButtons);


        JButton dropdownButton = new JButton("Patterns");
        JPopupMenu jPopupMenu = new JPopupMenu();
        for (JCheckBox checkBox : patternsCheckBoxes) {
            jPopupMenu.add(checkBox);
        }
        dropdownButton.addActionListener(e -> jPopupMenu.show(dropdownButton, 0, dropdownButton.getHeight()));
        controlButtons.add(dropdownButton);
        controlButtons.add(isUseDaemonMavenCheckBox);
        controlButtons.add(isUseCache);
/*
        JMenu jMenu = new JMenu("Patterns");
        jMenu.add(jCheckBoxMenuItem);
        panel.add(jMenu, BorderLayout.PAGE_END);
*/

        return panel;
    }

    private void updateMergeStatusOfProject(File projectPath) {
        try {
            if (GitUtils.hasConflicts(projectPath)) {
                oursCommit = GitUtils.getOurs(projectPath);
                theirsCommit = GitUtils.getTheirs(projectPath);
                statusLabel.setText("\u2705 MERGE CONFLICT DETECTED");
                oursLabel.setText("Ours:   " + oursCommit);
                theirsLabel.setText("Theirs: " + theirsCommit);
                mapConflicts = GitUtils.countConflictChunks(oursCommit, theirsCommit, GitUtils.getGit(projectPath));
                int numOfConflictChunks = mapConflicts.values().stream().reduce(0, Integer::sum);
                int numOfConflictFiles = mapConflicts.size();
                int numberOfPatters = Math.toIntExact(patternsCheckBoxes.stream().filter(AbstractButton::isSelected).count());
                numOfConflictChunksLabel.setText("# conflict chunks: " + numOfConflictChunks);
                numOfConflictFilesLabel.setText("# conflict files: " + numOfConflictFiles);
                numOfVariantsLabel.setText("# variants: " + (int)Math.pow(numberOfPatters,numOfConflictChunks));

                runButton.setEnabled(true);
            } else if (statusLabel.getText() != "\u2705 MERGE CONFLICT DETECTED") {
                statusLabel.setText("NO MERGE CONFLICT");
                oursLabel.setText("");
                theirsLabel.setText("");
                runButton.setEnabled(false);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public JComponent getRoot() {
        return root;
    }

    public void makeResolution() {
        treeRoot.removeAllChildren();
        treeModel.reload();
        resolutionResultDTO.setVariants(new ArrayList<>());
        new Task.Backgroundable(null, "Running Conflict Resolution", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {

                List<IPattern> patterns = getPatternsFromCheckboxes();
                conflictResolutionService = new ConflictResolutionService(projectPath.toFile(),
                        Paths.get("\\mytemp"),
                        resolutionResultDTO,
                        patterns);
                try {
                    executionStatus.setText("Prepare projects for conflict resolution");
                    totalExecutionTimeLabel.setText("");
                    conflictResolutionService.buildProjects(oursCommit, theirsCommit);

                    executionStatus.setText("Status: Running tests...");
                    Instant start = Instant.now();
                    conflictResolutionService.runTests(isUseDaemonMavenCheckBox.isSelected(), isUseCache.isSelected());
                    Instant end = Instant.now();
                    totalExecutionTimeLabel.setText("Total execution time: " + Duration.between(start, end).toMillis() / 1000d + " seconds");

                    executionStatus.setText("Status: Collecting results...");
                    for (ResolutionResultDTO.Variant variant : resolutionResultDTO.getVariants()) {
                        variant.setStatus(ResolutionResultDTO.variantStatus.ANALYZING);

                        Map<String, CompilationResult> compilationResultMap = conflictResolutionService.collectCompilationResults();
                        Map<String, TestTotal> tests = conflictResolutionService.collectTestResults();
                        variant.setCompilationResult(compilationResultMap.get(variant.getName()));
                        variant.setTestTotal(tests.get(variant.getName()));
                        variant.setStatus(ResolutionResultDTO.variantStatus.FINISHED);
                        executionStatus.setText("Status: Finished");
                    }
                    sortButton.setEnabled(true);
                } catch (Exception e) {
                    e.printStackTrace();
                    throw new RuntimeException(e);
                }
            }
        }.queue();

    }

    public void updateVariant(ResolutionResultDTO.Variant v) {
        SwingUtilities.invokeLater(() -> {

            Enumeration<?> e = treeRoot.children();
            while (e.hasMoreElements()) {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) e.nextElement();
                ResolutionResultDTO.Variant data = (ResolutionResultDTO.Variant) node.getUserObject();

                if (data.getName().equals(v.getName())) {
                    node.removeAllChildren();

                    node.add(new DefaultMutableTreeNode(
                            "Status: " + v.getStatus()
                    ));

                    if (v.getResolutionPatterns() != null) {
                        DefaultMutableTreeNode resolutionPatternsNode = new DefaultMutableTreeNode(v.getResolutionPatterns());
                        for (Map.Entry<String, List<String>> entry : v.getResolutionPatterns().getConflictPatterns().entrySet()) {
                            DefaultMutableTreeNode fileResolutionNode = new DefaultMutableTreeNode(entry.getKey());
                            for (String pattern : entry.getValue()) {
                                fileResolutionNode.add(new DefaultMutableTreeNode(pattern));
                            }
                            resolutionPatternsNode.add(fileResolutionNode);
                        }
//                    node.add(new DefaultMutableTreeNode(v.getResolutionPatterns()));
                        node.add(resolutionPatternsNode);
                    }

                    if (v.getCompilationResult() != null) {
                        CompilationResult compilationResult = v.getCompilationResult();
//                    node.add(new DefaultMutableTreeNode(v.getCompilationResult()));
                        DefaultMutableTreeNode compilationNode = new DefaultMutableTreeNode(compilationResult);
                        compilationNode.add(new DefaultMutableTreeNode("modules:" + compilationResult.getModuleResults()));
                        compilationNode.add(new DefaultMutableTreeNode("buildStatus:" + compilationResult.getBuildStatus()));
                        compilationNode.add(new DefaultMutableTreeNode("TotalTIme:" + compilationResult.getTotalTime()));

                        node.add(compilationNode);
                    }

                    if (v.getTestTotal() != null) {
                        DefaultMutableTreeNode testsNode = new DefaultMutableTreeNode(v.getTestTotal());
                        testsNode.add(new DefaultMutableTreeNode("RunNum: " + v.getTestTotal().getRunNum()));
                        testsNode.add(new DefaultMutableTreeNode("FailureNum: " + v.getTestTotal().getFailuresNum()));
                        testsNode.add(new DefaultMutableTreeNode("ErrorsNum: " + v.getTestTotal().getErrorsNum()));
                        testsNode.add(new DefaultMutableTreeNode("SkippedNum: " + v.getTestTotal().getSkippedNum()));
                        testsNode.add(new DefaultMutableTreeNode("Elapsed Time: " + v.getTestTotal().getElapsedTime()));
//                    node.add(new DefaultMutableTreeNode(v.getTestTotal()));
                        node.add(testsNode);
                    }

                    treeModel.reload(node);
                    return;
                }
            }
            DefaultMutableTreeNode variantNode =
                    new DefaultMutableTreeNode(v);
            treeRoot.add(variantNode);
            treeModel.reload();
        });
    }

    private void sortVariants(Comparator<ResolutionResultDTO.Variant> comparator) {
        List<DefaultMutableTreeNode> nodes = new ArrayList<>();

        Enumeration<?> e = treeRoot.children();
        while (e.hasMoreElements()) {
            nodes.add((DefaultMutableTreeNode) e.nextElement());
        }

        nodes.sort((n1, n2) -> {
            ResolutionResultDTO.Variant v1 =
                    (ResolutionResultDTO.Variant) n1.getUserObject();
            ResolutionResultDTO.Variant v2 =
                    (ResolutionResultDTO.Variant) n2.getUserObject();
            return comparator.compare(v1, v2);
        });

        treeRoot.removeAllChildren();
        for (DefaultMutableTreeNode n : nodes) {
            treeRoot.add(n);
        }

        treeModel.reload(treeRoot);
    }

    public int calculateNumberOfPassedTest(TestTotal testTotal) {
        return testTotal.getRunNum() - testTotal.getSkippedNum() - testTotal.getFailuresNum() - testTotal.getErrorsNum();
    }

    private List<Class<? extends IPattern>> getPatterns() {
        return List.of(OursPattern.class, TheirsPattern.class, BasePattern.class, EmptyPattern.class, CombinePattern.class);
    }

    private List<IPattern> getPatternsFromCheckboxes() {
        List<IPattern> patterns = new ArrayList<>();
        for (JCheckBox checkBox : patternsCheckBoxes) {
            if (checkBox.isSelected()) {
                Class<? extends IPattern> patternClass = getPatterns().stream()
                        .filter(x -> x.getSimpleName().equals(checkBox.getText())).findFirst().orElseThrow();

                IPattern pattern = null;
                try {
                    pattern = patternClass.getDeclaredConstructor().newInstance();
                } catch (Exception e) {
                    e.printStackTrace();
                    throw new RuntimeException(e);
                }
                patterns.add(pattern);
            }
        }

        return patterns;
    }
}
