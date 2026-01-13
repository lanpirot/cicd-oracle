package org.example.demo.cicdMergeTool.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

import java.io.File;

public class MergeResolutionToolWindowFactory implements ToolWindowFactory {

    @Override
    public void createToolWindowContent(
            @NotNull Project project, @NotNull ToolWindow toolWindow) {

        MergeResolutionPanel panel =
                new MergeResolutionPanel(new File(project.getBasePath()), project);

        ContentFactory contentFactory =
                ContentFactory.getInstance();

        Content content = contentFactory.createContent(
                panel.getRoot(),
                "",
                false
        );

        toolWindow.getContentManager().addContent(content);
    }
}
