package org.example.demo.threeWayMergeTool;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.testframework.TestStatusListener;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.MarkupModel;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.extensions.ExtensionPoint;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.TextRange;
import com.intellij.ui.JBColor;
import com.intellij.ui.LightColors;
import org.jetbrains.annotations.NotNull;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.List;

public class ThreeWayMergePanel extends JPanel implements CompilationListener {

    private final JLabel compilationStatusLabel;
    private boolean skipReleaseOnClose = true;

    private final DialogWrapper dialog;

    private final Editor leftEditor;
    private final Editor rightEditor;
    private final Editor resultEditor;
    private MergeContent mergeContent;
    private final Project project;
    private final Document gitDocument;
    private Editor initialEditor;
    List<ConflictRange> conflictRangeList;
    private int currentConflictIndex = 0;
    private final JLabel passedLabel;
    private final JLabel failedLabel;

    public ThreeWayMergePanel(Project project, MergeContent mergeContent, DialogWrapper dialog) {
        this.mergeContent = mergeContent;
        this.project = project;
        this.dialog = dialog;

        setLayout(new BorderLayout()); // Using BorderLayout to Place Panels

        JPanel topPanel = new JPanel(new BorderLayout());
        // Panel with buttons on top
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT)); // Panel with buttons
        JButton acceptLeftButton = new JButton("Accept Left");
        JButton acceptRightButton = new JButton("Accept Right");
        JButton runTestsButton = new JButton("Test it!");


        buttonPanel.add(acceptLeftButton);
        buttonPanel.add(acceptRightButton);
        buttonPanel.add(runTestsButton);

        JPanel infoPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT)); // Panel with info/statuses
        // Add a panel on the right to display information about tests
        JPanel testResultsPanel = new JPanel();
        //testResultsPanel.setLayout(new BoxLayout(testResultsPanel, BoxLayout.Y_AXIS));
        testResultsPanel.setLayout(new FlowLayout(FlowLayout.RIGHT));

        testResultsPanel.setBorder(BorderFactory.createTitledBorder("Test Results"));

        passedLabel = new JLabel("Passed tests: 0");
        failedLabel = new JLabel("Failed tests: 0");
        passedLabel.setFont(new Font("Arial", Font.BOLD, 14));
        failedLabel.setFont(new Font("Arial", Font.BOLD, 14));
        passedLabel.setForeground(JBColor.GREEN);
        failedLabel.setForeground(JBColor.RED);

        testResultsPanel.add(passedLabel);
        testResultsPanel.add(failedLabel);

        compilationStatusLabel = new JLabel();
        compilationStatusLabel.setText("");
        compilationStatusLabel.setFont(new Font("Arial", Font.BOLD, 14));

        infoPanel.add(compilationStatusLabel);
        infoPanel.add(testResultsPanel);

        topPanel.add(buttonPanel, BorderLayout.WEST);
        topPanel.add(infoPanel, BorderLayout.EAST);


        JPanel editorsPanel = new JPanel(new GridLayout(1, 3)); // Three columns for three editors

        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));

        mainPanel.add(topPanel, BorderLayout.CENTER);

        // Header Panel
        JPanel headersPanel = new JPanel(new GridLayout(1, 3)); // Grid layout with 3 columns for headers
        headersPanel.add(new JLabel("Your Version", SwingConstants.CENTER));
        JLabel baseVersionLabel = new JLabel("Base Version", SwingConstants.CENTER);
        headersPanel.add(baseVersionLabel);
        headersPanel.add(new JLabel("Their Version", SwingConstants.CENTER));

        mainPanel.add(headersPanel, BorderLayout.CENTER);

        EditorFactory editorFactory = EditorFactory.getInstance();

        // Create documents for each editor
        Document leftDocument = editorFactory.createDocument(mergeContent.getYourContent());
        Document resultDocument = editorFactory.createDocument(mergeContent.getBaseContent());
        Document rightDocument = editorFactory.createDocument(mergeContent.getTheirContent());


        FileType fileType = FileTypeManager.getInstance().getFileTypeByExtension("java");

        // Create editors
        leftEditor = editorFactory.createEditor(leftDocument, project, fileType, true);
        resultEditor = editorFactory.createEditor(resultDocument, project, fileType, false);
        rightEditor = editorFactory.createEditor(rightDocument, project, fileType, true);


        leftEditor.getSettings().setLineNumbersShown(true);
        resultEditor.getSettings().setLineNumbersShown(true);
        rightEditor.getSettings().setLineNumbersShown(true);

        // Add the editor components to the panel
        editorsPanel.add(leftEditor.getComponent());
        editorsPanel.add(resultEditor.getComponent()); // Result editor in the middle
        editorsPanel.add(rightEditor.getComponent());

        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        int width = (int) (screenSize.width * 0.8);
        int height = (int) (screenSize.height * 0.5);

        editorsPanel.setPreferredSize(new Dimension(width, height));


        mainPanel.add(editorsPanel, BorderLayout.CENTER);


        add(mainPanel, BorderLayout.CENTER);
        //highlightLineInRed(leftEditor, 7);

        JPanel bottomPanel = new JPanel(new BorderLayout());
        JButton saveButton = new JButton("save");
        saveButton.setOpaque(true);
        saveButton.setContentAreaFilled(false);
        saveButton.setBackground(JBColor.GREEN);
        bottomPanel.add(saveButton, BorderLayout.EAST);
        add(bottomPanel, BorderLayout.AFTER_LAST_LINE);

        acceptLeftButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                System.out.println("leftCommitButton");
                baseVersionLabel.setText("Result Version");
                applyConflictResolution(true);
                updateMainEditor();
                CompilationHelper.compileProject(project, ThreeWayMergePanel.this);
            }
        });

        acceptRightButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                System.out.println("rightCommitButton");
                baseVersionLabel.setText("Result Version");
                applyConflictResolution(false);
                updateMainEditor();
                CompilationHelper.compileProject(project, ThreeWayMergePanel.this);
            }
        });

        runTestsButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                System.out.println("runTestsButton");
                CompilationHelper.compileProject(project, ThreeWayMergePanel.this);
            }
        });

        saveButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                skipReleaseOnClose = false;
                dialog.close(DialogWrapper.OK_EXIT_CODE);
            }
        });

        FileEditorManager editorManager = FileEditorManager.getInstance(project);
        initialEditor = editorManager.getSelectedTextEditor();
        this.gitDocument = EditorFactory.getInstance().createDocument(initialEditor.getDocument().getText());
        updateMainEditor();


        conflictRangeList = detectConflicts(gitDocument);
        highlightCurrentConflict();

        TestResultCounter testResultCounter = getResultCounterExtensionPoint();
        testResultCounter.setMergePanel(this);

    }

    private TestResultCounter getResultCounterExtensionPoint() {
        ExtensionPoint<TestStatusListener> listeners = ApplicationManager.getApplication().getExtensionArea().getExtensionPoint("com.intellij.testStatusListener");
        for (TestStatusListener listener : listeners.getExtensionList()) {
            if (listener instanceof TestResultCounter) {
                return (TestResultCounter) listener;
            }
        }
        return null;
    }

    private void runTheTests() {
        try {
            TestRunner.runJUnitTests(project);
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    public void disposeEditors() {
        if (skipReleaseOnClose) {
            WriteCommandAction.runWriteCommandAction(project, () ->
                    initialEditor.getDocument().setText(gitDocument.getText())
            );
            new Task.Backgroundable(project, "chekcout process") {
                @Override
                public void run(@NotNull ProgressIndicator indicator) {
                    GitUtils.checkoutWithMergeConflict(project);
                }
            }.queue();
        }
        EditorFactory.getInstance().releaseEditor(leftEditor);
        EditorFactory.getInstance().releaseEditor(rightEditor);
        EditorFactory.getInstance().releaseEditor(resultEditor);
    }

    private void highlightLine(Editor editor, int startLine, int endLine, JBColor color) {
        Document document = editor.getDocument();
        int lineStartOffset = document.getLineStartOffset(startLine);
        int lineEndOffset = document.getLineEndOffset(endLine);

        // Create attributes for the color red
        TextAttributes attributes = new TextAttributes();
        attributes.setBackgroundColor(color);

        // Getting MarkupModel for the editor
        MarkupModel markupModel = editor.getMarkupModel();

        // Add selection to the specified range
        markupModel.addRangeHighlighter(lineStartOffset, lineEndOffset, HighlighterLayer.ADDITIONAL_SYNTAX, attributes, HighlighterTargetArea.EXACT_RANGE);
    }

    private void disableHighlightLine(Editor editor) {
        Document document = editor.getDocument();

        // Create attributes
        TextAttributes attributes = new TextAttributes();

        // Getting MarkupModel for the editor
        MarkupModel markupModel = editor.getMarkupModel();

        markupModel.removeAllHighlighters();
    }

    private void disableHConflictHighlightLine() {
        disableHighlightLine(leftEditor);
        disableHighlightLine(resultEditor);
        disableHighlightLine(rightEditor);
    }

    private void highlightLineInRed(Editor editor, int lineNumber) {
        JBColor color = new JBColor(LightColors.RED, LightColors.RED);
        highlightLine(editor, lineNumber, lineNumber, color);
    }

    private List<ConflictRange> detectConflicts(Document doc) {
        List<ConflictRange> conflicts = new ArrayList<>();
        String[] gitMergeLines = doc.getText().split("\n");
        String[] leftLines = leftEditor.getDocument().getText().split("\n");
        String[] rightLines = rightEditor.getDocument().getText().split("\n");
        String[] baseLines = resultEditor.getDocument().getText().split("\n");

        ConflictRange.GitConflict gitConflict = new ConflictRange.GitConflict(
                new ConflictRange.Conflict(-1, -1),
                new ConflictRange.Conflict(-1, -1),
                new ConflictRange.Conflict(-1, -1));
        ConflictRange.Conflict leftEditorConflict = new ConflictRange.Conflict(-1, -1);
        ConflictRange.Conflict rightEditorConflict = new ConflictRange.Conflict(-1, -1);
        ConflictRange.Conflict baseEditorConflict = new ConflictRange.Conflict(-1, -1);

        boolean inConflict = false;
        ConflictRange.ConflictStatus conflictStatus = ConflictRange.ConflictStatus.NONE;

        for (int gitFileIndex = 0, baseIndex = 0, leftIndex = 0, rightIndex = 0; gitFileIndex < gitMergeLines.length; gitFileIndex++) {
            String line = gitMergeLines[gitFileIndex];

            if (line.startsWith("<<<<<<<")) {
                // start of conflict
                conflictStatus = ConflictRange.ConflictStatus.LEFT;
                inConflict = true;
                gitConflict.leftConflict.startLine = gitFileIndex + 1;
                leftEditorConflict.startLine = leftIndex;
            } else if (line.startsWith("|||||||") && inConflict) {
                gitConflict.leftConflict.endLine = gitFileIndex - 1;
                gitConflict.baseConflict.startLine = gitFileIndex + 1;
                baseEditorConflict.startLine = baseIndex;
            } else if (line.startsWith("=======") && inConflict) {
                // End of HEAD conflict
                conflictStatus = ConflictRange.ConflictStatus.RIGHT;
                gitConflict.baseConflict.endLine = gitFileIndex - 1;
                gitConflict.rightConflict.startLine = gitFileIndex + 1;
                rightEditorConflict.startLine = rightIndex;
            } else if (line.startsWith(">>>>>>>") && inConflict) {
                // End of right conflict
                conflictStatus = ConflictRange.ConflictStatus.NONE;
                gitConflict.rightConflict.endLine = gitFileIndex - 1;

                leftEditorConflict.endLine = leftEditorConflict.startLine + gitConflict.leftConflict.endLine - gitConflict.leftConflict.startLine;
                baseEditorConflict.endLine = baseEditorConflict.startLine + gitConflict.baseConflict.endLine - gitConflict.baseConflict.startLine;
                rightEditorConflict.endLine = rightEditorConflict.startLine + gitConflict.rightConflict.endLine - gitConflict.rightConflict.startLine;
                System.out.println("left=" + leftEditorConflict);
                System.out.println("right=" + rightEditorConflict);
                conflicts.add(new ConflictRange(gitConflict, baseEditorConflict, leftEditorConflict, rightEditorConflict));
                System.out.println("base=" + baseEditorConflict);
                leftIndex = leftEditorConflict.endLine+1;
                baseIndex = baseEditorConflict.endLine+1;
                rightIndex = rightEditorConflict.endLine+1;
                // Reset flags and variables
                inConflict = false;
                gitConflict = new ConflictRange.GitConflict(
                        new ConflictRange.Conflict(-1, -1),
                        new ConflictRange.Conflict(-1, -1),
                        new ConflictRange.Conflict(-1, -1));
                leftEditorConflict = new ConflictRange.Conflict(-1, -1);
                rightEditorConflict = new ConflictRange.Conflict(-1, -1);
                baseEditorConflict = new ConflictRange.Conflict(-1, -1);

            }else if (!inConflict) {
                baseIndex++;
                leftIndex++;
                rightIndex++;
            }
        }
        System.out.println(conflicts);
        return conflicts;
    }

    private void applyConflictResolution(boolean acceptLeft) {
        System.out.println("applyConflictResolution");
        if (currentConflictIndex < conflictRangeList.size()) {
            ConflictRange conflict = conflictRangeList.get(currentConflictIndex);
            String selectedText = acceptLeft
                    ? gitDocument.getText(new TextRange(
                    gitDocument.getLineStartOffset(conflict.gitConflict.leftConflict.startLine),
                    gitDocument.getLineEndOffset(conflict.gitConflict.leftConflict.endLine)))
                    : gitDocument.getText(new TextRange(
                    gitDocument.getLineStartOffset(conflict.gitConflict.rightConflict.startLine),
                    gitDocument.getLineEndOffset(conflict.gitConflict.rightConflict.endLine)));

//            resultEditor.getDocument().replaceString(conflict.leftStartLine, conflict.rightEndLine, selectedText);
            WriteCommandAction.runWriteCommandAction(project, () ->
                    resultEditor.getDocument().replaceString(
                            resultEditor.getDocument().getLineStartOffset(conflict.baseConflict.startLine),
                            resultEditor.getDocument().getLineEndOffset(conflict.baseConflict.endLine), selectedText)
            );
            currentConflictIndex++;
            System.out.println(conflictRangeList);
            for (int i = currentConflictIndex; i < conflictRangeList.size(); i++) {
                int diffLinesCount =selectedText.split("\n").length - conflictRangeList.get(currentConflictIndex).baseConflict.endLine -
                        conflictRangeList.get(currentConflictIndex).baseConflict.startLine;
                conflictRangeList.get(currentConflictIndex).baseConflict.startLine += selectedText.split("\n").length-1;
                conflictRangeList.get(currentConflictIndex).baseConflict.endLine += selectedText.split("\n").length-1;
            }
            System.out.println(conflictRangeList);
            disableHConflictHighlightLine();
            highlightCurrentConflict();

        }
    }

    private int findBaseLine(String[] baseLines, String[] conflictLines, int startLine, int endLine) {
        for (int baseIndex = 0; baseIndex <= baseLines.length - (endLine - startLine + 1); baseIndex++) {
            boolean match = true;

            for (int offset = 0; offset <= endLine - startLine; offset++) {
                if (!baseLines[baseIndex + offset].trim().equals(conflictLines[startLine + offset].trim())) {
                    match = false;
                    break;
                }
            }

            if (match) {
                return baseIndex;
            }
        }
        return -1;
    }

    private void highlightCurrentConflict() {
        JBColor red = new JBColor(new Color(255, 0, 0, 20), new Color(255, 0, 0, 20));
        if (currentConflictIndex < conflictRangeList.size()) {
            ConflictRange conflict = conflictRangeList.get(currentConflictIndex);
            highlightLine(leftEditor, conflict.leftConflict.startLine, conflict.leftConflict.endLine, red);
            highlightLine(rightEditor, conflict.rightConflict.startLine, conflict.rightConflict.endLine, red);
            highlightLine(resultEditor, conflict.baseConflict.startLine, conflict.baseConflict.endLine, red);
        }
    }

    @Override
    public void onCompilationFinished(boolean success) {
        if (success) {
            compilationStatusLabel.setText("Compilation complete.");
            compilationStatusLabel.setIcon(UIManager.getIcon("OptionPane.informationIcon"));
            //If the compilation is successful, we run the tests
            runTheTests();
        } else {
            compilationStatusLabel.setText("Compilation failed.");
            compilationStatusLabel.setIcon(UIManager.getIcon("OptionPane.errorIcon"));
        }
    }

    public void startCompilation(Project project) {
        CompilationHelper.compileProject(project, this);
    }


    private static class ConflictRange {
        private final GitConflict gitConflict;
        private final Conflict baseConflict;
        private final Conflict leftConflict;
        private final Conflict rightConflict;

        public ConflictRange(GitConflict gitConflict, Conflict baseConflict, Conflict leftConflict, Conflict rightConflict) {
            this.gitConflict = gitConflict;
            this.baseConflict = baseConflict;
            this.leftConflict = leftConflict;
            this.rightConflict = rightConflict;
        }

        @Override
        public String toString() {
            return "ConflictRange{" +
                    "gitConflict=" + gitConflict +
                    ", \nbaseConflict=" + baseConflict +
                    ", leftConflict=" + leftConflict +
                    ", rightConflict=" + rightConflict +
                    '}';
        }

        private static class GitConflict {
            private final Conflict leftConflict;
            private final Conflict baseConflict;
            private final Conflict rightConflict;

            public GitConflict(Conflict leftConflict, Conflict baseConflict,Conflict rightConflict) {
                this.leftConflict = leftConflict;
                this.baseConflict = baseConflict;
                this.rightConflict = rightConflict;
            }

            @Override
            public String toString() {
                return "gitConflict{" +
                        "leftConflict=" + leftConflict +
                        ", rightConflict=" + rightConflict +
                        '}';
            }
        }

        private static class Conflict {
            public Conflict(int startLine, int endLine) {
                this.startLine = startLine;
                this.endLine = endLine;
            }

            private int startLine;
            private int endLine;

            @Override
            public String toString() {
                return "Conflict{" +
                        "startLine=" + startLine +
                        ", endLine=" + endLine +
                        '}';
            }
        }

        private enum ConflictStatus {
            NONE,   // No conflict
            LEFT,   // Conflict is in the left branch
            RIGHT   // Conflict is in the right branch
        }
    }

    private void updateMainEditor() {
        WriteCommandAction.runWriteCommandAction(project, () ->
                initialEditor.getDocument().setText(resultEditor.getDocument().getText())
        );
    }

    //Method of updating information on the panel
    public void updateTestResults(int passed, int failed) {
        passedLabel.setText("Passed tests: " + passed);
        failedLabel.setText("Failed tests: " + failed);
    }
}