package org.example.cicdmergeoracle.threeWayMergeTool;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;

public class CodeEditorDialog extends DialogWrapper {

    private final Project project;
    private final String initialContent;
    private Editor editor;

    public CodeEditorDialog(Project project, String initialContent) {
        super(true);
        this.project = project;
        this.initialContent = initialContent;
        init();  // Initializing the dialogue
        setTitle("Code Editor");
    }

    @Override
    protected JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout());

        // We get a factory for creating a document and an editor
        EditorFactory editorFactory = EditorFactory.getInstance();

        // Create a document with the original content
        Document document = editorFactory.createDocument(initialContent);

        // Get the file type for syntax highlighting (e.g. "Java", "Python", etc.)
        FileType fileType = FileTypeManager.getInstance().getFileTypeByExtension("java");

        // Create a document editor with syntax highlighting
        editor = editorFactory.createEditor(document, project, fileType, true);

        // access to advanced editor settings
        if (editor instanceof EditorEx) {
            EditorEx editorEx = (EditorEx) editor;
            editorEx.setViewer(false);  // This allows you to edit the text (not just for viewing)
            editorEx.getSettings().setLineNumbersShown(true);  // turn on line numbering
        }

        panel.add(editor.getComponent(), BorderLayout.CENTER);

        return panel;
    }

    @Override
    protected void dispose() {
        // When closing a window, it is necessary to free up the editor's resources.
        EditorFactory.getInstance().releaseEditor(editor);
        super.dispose();
    }
}